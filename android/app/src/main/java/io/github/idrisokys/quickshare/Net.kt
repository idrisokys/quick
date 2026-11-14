package io.github.idrisokys.quickshare

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TYPE = "_qshare._tcp."
private const val BUF = 1 shl 20
/** Alıcıda ağ okuma ile disk yazmayı ayıran tampon havuzu: SLOTS × CH = 4 MB. */
private const val CH = 512 * 1024
private const val SLOTS = 8
private class Chunk(val b: ByteArray, val n: Int)   // n < 0: bitti işareti

/** Alınan dosyaların varsayılan konumu: <ana dizin>/Link */
object SaveDir {
    fun dir() = File(android.os.Environment.getExternalStorageDirectory(), "Link")
    /** Android 11+: "Tüm dosyalara erişim", Android 10: depolama izni verilmişse ana dizine doğrudan yazılabilir. */
    fun direct(c: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** mDNS (NsdManager): kendini duyurur ve çevredeki cihazları bulur. */
class Discovery(c: Context) {
    private val app = c.applicationContext
    private val nsd = c.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var rl: NsdManager.RegistrationListener? = null
    private var dl: NsdManager.DiscoveryListener? = null
    private val q = ArrayDeque<NsdServiceInfo>()
    private var busy = false
    private val tries = HashMap<String, Int>()
    private val myName get() = "QS" + Identity.id.take(10)

    fun register(port: Int) {
        val i = NsdServiceInfo().apply {
            serviceName = myName
            serviceType = TYPE
            this.port = port
            setAttribute("n", Identity.name)
            setAttribute("i", Identity.id)
        }
        rl = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(s: NsdServiceInfo?, e: Int) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo?, e: Int) {}
            override fun onServiceRegistered(s: NsdServiceInfo?) {}
            override fun onServiceUnregistered(s: NsdServiceInfo?) {}
        }
        nsd.registerService(i, NsdManager.PROTOCOL_DNS_SD, rl)
    }

    fun browse() {
        dl = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) {}
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceFound(s: NsdServiceInfo) { if (s.serviceName != myName) enqueue(s) }
            override fun onServiceLost(s: NsdServiceInfo) { Peers.del(s.serviceName); Shortcuts.update(app) }
        }
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, dl)
    }

    fun stop() {
        try { rl?.let { nsd.unregisterService(it) } } catch (e: Exception) { }
        try { dl?.let { nsd.stopServiceDiscovery(it) } } catch (e: Exception) { }
    }

    @Synchronized private fun enqueue(s: NsdServiceInfo) { q.addLast(s); pump() }

    @Synchronized private fun pump() {
        if (busy) return
        val s = q.removeFirstOrNull() ?: return
        busy = true
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo?, e: Int) {
                // NsdManager çözümlemesi sık sık geçici hata verir; cihaz listeden düşmesin diye 2 kez daha dene.
                synchronized(this@Discovery) {
                    val n = tries[s.serviceName] ?: 0
                    if (n < 2) { tries[s.serviceName] = n + 1; q.addLast(s) }
                }
                done()
            }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val f = i.attributes["i"]?.toString(Charsets.UTF_8)
                val n = i.attributes["n"]?.toString(Charsets.UTF_8)
                if (f != null && f != Identity.id && i.host != null)
                    { Peers.put(i.serviceName, Peer(f, n ?: "?", i.host, i.port)); Shortcuts.update(app) }
                synchronized(this@Discovery) { tries.remove(s.serviceName) }
                done()
            }
        })
    }

    @Synchronized private fun done() { busy = false; pump() }
}

/** TCP sunucu: ağdaki cihazlardan gelen dosyaları kabul eder. */
class Receiver(private val ctx: Context) {
    private var ss: ServerSocket? = null
    val port get() = ss!!.localPort

    fun start() {
        // Tampon, bind'dan ÖNCE ayarlanmalı; yoksa büyük TCP penceresi (hız) devreye girmez.
        val s = ServerSocket().apply {
            reuseAddress = true
            receiveBufferSize = BUF
            bind(InetSocketAddress(0))
        }
        ss = s
        thread(isDaemon = true, name = "qs-accept") {
            while (true) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true, name = "qs-rx") { handle(c) }
            }
        }
    }

    fun stop() { try { ss?.close() } catch (e: Exception) { } }

    /** Başlık satırı okur (en fazla 8 KB; sonsuz satıra karşı korumalı). */
    private fun line(i: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val c = i.read()
            if (c < 0 || c == 10) break
            if (c != 13) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun reply(s: Socket, st: String) {
        s.outputStream.write("HTTP/1.1 $st\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        s.outputStream.flush()
    }

    private fun handle(s: Socket) {
        Hold.acquire(ctx)
        try {
            s.use {
                s.soTimeout = 30000
                s.tcpNoDelay = true
                // Başlıklar bayt bayt okunduğu için tamponlu akış (sistem çağrısı sayısını düşürür)
                val ins = BufferedInputStream(s.inputStream, 64 * 1024)
                line(ins)
                var len = -1L
                var name = "file"
                while (true) {
                    val l = line(ins)
                    if (l.isEmpty()) break
                    val k = l.substringBefore(':').lowercase()
                    val v = l.substringAfter(':').trim()
                    if (k == "content-length") len = v.toLongOrNull() ?: -1L
                    else if (k == "x-name") name = URLDecoder.decode(v, "UTF-8")
                }
                if (len < 0) { reply(s, "400 Bad Request"); return }
                val clean = name.substringAfterLast('/').substringAfterLast('\\')
                    .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trimStart('.').take(120).ifBlank { "file" }

                // Kayıt yeri yalnızca <ana dizin>/Link; izin yoksa ya da yazılamazsa alma reddedilir.
                if (!SaveDir.direct(ctx)) { reply(s, "500 Error"); Bus.set(ctx.getString(R.string.recv_error)); return }
                val d = SaveDir.dir().also { it.mkdirs() }
                if (!d.isDirectory || d.usableSpace < len) { reply(s, "507 Insufficient Storage"); Bus.set(ctx.getString(R.string.recv_error)); return }

                // Önce .part dosyasına yazılır, başarılı olunca asıl ada çevrilir (aynı adlı dosyanın üzerine yazar).
                // Aynı adlı iki aktarım birbirinin yarım dosyasını bozmasın diye .part adı benzersizdir.
                val file = File(d, "$clean.${java.util.UUID.randomUUID().toString().take(8)}.part")
                var ok = false
                try {
                    FileOutputStream(file).use { out ->
                        // Okuyucu (bu iş parçacığı) ağdan okur, yazıcı iş parçacığı diske yazar; yavaş depolama
                        // (ör. TV) ağı bekletmez. Havuz boşalınca okuyucu doğal olarak yavaşlar (taşma yok).
                        val free = ArrayBlockingQueue<ByteArray>(SLOTS).also { q -> repeat(SLOTS) { q.add(ByteArray(CH)) } }
                        val full = ArrayBlockingQueue<Chunk>(SLOTS + 1)
                        val werr = AtomicBoolean(false)
                        val wr = thread(isDaemon = true, name = "qs-write") {
                            while (true) {
                                val c = try { full.take() } catch (e: InterruptedException) { break }
                                if (c.n < 0) break
                                // Hata olsa da kuyruk boşaltılmaya devam eder; okuyucu asılı kalmaz.
                                if (!werr.get()) try { out.write(c.b, 0, c.n) } catch (e: Exception) { werr.set(true) }
                                free.add(c.b)
                            }
                        }
                        var left = len
                        var last = -1
                        try {
                            while (left > 0 && !werr.get()) {
                                val b = free.poll(30, TimeUnit.SECONDS) ?: break
                                val want = minOf(CH.toLong(), left).toInt()
                                var fill = 0
                                while (fill < want) {
                                    val n = ins.read(b, fill, want - fill)
                                    if (n < 0) break
                                    fill += n
                                }
                                if (fill > 0) full.put(Chunk(b, fill)) else free.add(b)
                                left -= fill
                                val p = ((len - left) * 100 / len).toInt()
                                if (p != last) { last = p; Bus.progress(ctx.getString(R.string.receiving, clean), p) }
                                if (fill < want) break
                            }
                        } finally {
                            full.put(Chunk(ByteArray(0), -1))
                            wr.join()   // bütün yazmalar bitmeden dosya kapanmaz / yeniden adlandırılmaz
                        }
                        ok = left == 0L && !werr.get()
                    }
                } finally {
                    if (ok) {
                        val dest = File(d, clean)
                        dest.delete()
                        if (file.renameTo(dest)) MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null)
                        else { file.delete(); ok = false }
                    } else file.delete()
                }
                reply(s, if (ok) "200 OK" else "500 Error")
                Bus.set(if (ok) ctx.getString(R.string.saved, clean) else ctx.getString(R.string.recv_partial), -1, ok)
                if (ok) ReceiverService.notifyReceived(ctx, clean)
            }
        } catch (e: Exception) {
            Bus.set(ctx.getString(R.string.recv_error))
        } finally {
            Hold.release()
        }
    }
}

object Sender {
    /**
     * Gönderimi arka plan servisine devreder; böylece paylaş ekranı hemen kapanır, aktarım
     * bildirimde ilerleme çubuğuyla sürer. Dosya okuma izni servise de verilir (ClipData + grant).
     * only verilirse sadece o cihaza, verilmezse bulunan tüm cihazlara sırayla gönderir.
     */
    fun submit(c: Context, uris: List<Uri>, only: String?) {
        if (uris.isEmpty()) return
        val clip = ClipData.newRawUri(null, uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        // Not: Intent.setClipData() Intent döndürmez (void), bu yüzden zincirlenemez.
        val i = Intent(c, ReceiverService::class.java).apply {
            action = ReceiverService.ACTION_SEND
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("only", only)
        }
        c.startForegroundService(i)
    }

    private fun toast(c: Context, m: String) =
        Handler(Looper.getMainLooper()).post { Toast.makeText(c, m, Toast.LENGTH_LONG).show() }

    /** Servisin arka plan iş parçacığında çalışır. */
    fun run(c: Context, only: String?, uris: List<Uri>) {
        val ps = Peers.list().filter { only == null || it.id == only }
        if (ps.isEmpty()) {
            val m = c.getString(R.string.no_device)
            Bus.set(m); toast(c, m)
            return
        }
        Hold.acquire(c)
        try {
            var err: String? = null
            for (p in ps) for (u in uris) {
                try { one(c, p, u) }
                catch (e: Exception) {
                    if (err == null) err = c.getString(R.string.error_fmt, "${p.name}: ${e.message ?: c.getString(R.string.err_connection)}")
                }
            }
            val msg = err ?: c.getString(R.string.sent)
            Bus.set(msg); toast(c, msg)
        } finally { Hold.release() }
    }

    private fun one(c: Context, p: Peer, u: Uri) {
        val cr = c.contentResolver
        var name = "file"
        cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) {
                val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) it.getString(i)?.let { n -> name = n }
            }
        }
        val afd = cr.openAssetFileDescriptor(u, "r") ?: error(c.getString(R.string.err_open))
        var len = afd.length
        if (len < 0) {
            // Bazı sağlayıcılar (bulut, bazı uygulamalar) uzunluğu AFD'de vermez; SIZE sütununa bak.
            cr.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) len = it.getLong(0)
            }
        }
        var spool: File? = null
        val src: InputStream
        if (len < 0) {
            // Boyut hiç bilinmiyorsa önce geçici dosyaya al, sonra boyutuyla gönder.
            afd.close()
            val t = File.createTempFile("tx", null, c.cacheDir).also { spool = it }
            try {
                (cr.openInputStream(u) ?: error(c.getString(R.string.err_open))).use { i -> t.outputStream().use { o -> i.copyTo(o, BUF) } }
            } catch (e: Exception) { t.delete(); throw e }
            len = t.length()
            src = t.inputStream()
        } else src = afd.createInputStream()
        try {
            transfer(c, p, name, len, src)
        } finally {
            try { src.close() } catch (e: Exception) { }
            try { afd.close() } catch (e: Exception) { }
            spool?.delete()
        }
    }

    private fun transfer(c: Context, p: Peer, name: String, len: Long, ins: InputStream) {
        // Tampon, connect'ten ÖNCE ayarlanmalı (TCP pencere ölçekleme)
        val s = Socket().apply { sendBufferSize = BUF; tcpNoDelay = true; soTimeout = 30000 }
        s.use {
            s.connect(InetSocketAddress(p.host, p.port), 10000)
            val out = s.outputStream
            out.write(
                ("POST /u HTTP/1.1\r\nX-Name: ${URLEncoder.encode(name, "UTF-8")}\r\n" +
                    "Content-Length: $len\r\nConnection: close\r\n\r\n").toByteArray()
            )
            val b = ByteArray(BUF)
            var sent = 0L
            var last = -1
            while (sent < len) {
                // Dosya gönderim sırasında büyürse protokolü bozmamak için bildirilen uzunluğu aşma.
                val n = ins.read(b, 0, minOf(b.size.toLong(), len - sent).toInt())
                if (n < 0) break
                out.write(b, 0, n)
                sent += n
                val pc = if (len > 0) (sent * 100 / len).toInt() else 0
                if (pc != last) { last = pc; Bus.progress(c.getString(R.string.sending, name), pc) }
            }
            // Dosya kısaldıysa alıcıyı 30 sn bekletmek yerine hemen hata ver.
            require(sent == len) { c.getString(R.string.err_open) }
            out.flush()
            val l = BufferedReader(InputStreamReader(s.inputStream)).readLine()
            require(l?.contains(" 200") == true) { c.getString(R.string.err_rejected) }
        }
    }
}
