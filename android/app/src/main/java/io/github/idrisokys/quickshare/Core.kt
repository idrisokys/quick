package io.github.idrisokys.quickshare

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.net.wifi.WifiManager
import android.provider.Settings
import java.net.InetAddress
import java.util.UUID

class Peer(val id: String, val name: String, val host: InetAddress, val port: Int)

/** UI'ya durum bildirimi (ana thread'e taşır). */
object Bus {
    private val h = Handler(Looper.getMainLooper())
    @Volatile var listener: (() -> Unit)? = null
    @Volatile var status = ""
    @Volatile var pct = -1
    /** true ise durum yazısı "Kaydedildi" mesajıdır; tıklayınca klasör açılır. */
    @Volatile var saved = false
    fun changed() { h.post { listener?.invoke() } }
    /** Arka plan servisi: bildirimi güncellemek için. */
    @Volatile var sink: ((String, Int) -> Unit)? = null
    fun set(s: String, p: Int = -1, saved: Boolean = false) { status = s; pct = p; this.saved = saved; changed(); sink?.invoke(s, p) }

    private var lastT = 0L
    /** İlerleme güncellemesi: en fazla ~4/sn (arayüz ve bildirim boğulmasın), %100 her zaman geçer. */
    fun progress(s: String, p: Int) {
        val now = SystemClock.elapsedRealtime()
        if (p < 100 && now - lastT < 250) return
        lastT = now
        set(s, p)
    }
}

/** Aktarım sürerken Wi-Fi ve CPU'yu uyanık tutar; aktarım bitince bırakır (boşta pil harcamaz). */
object Hold {
    private var n = 0
    private var wl: WifiManager.WifiLock? = null
    private var pl: PowerManager.WakeLock? = null

    @Synchronized fun acquire(c: Context) {
        if (n++ > 0) return
        val app = c.applicationContext
        wl = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "qs").apply { setReferenceCounted(false); acquire() }
        pl = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qs:xfer").apply { setReferenceCounted(false); acquire(30 * 60 * 1000L) }
    }

    @Synchronized fun release() {
        if (n == 0 || --n > 0) return
        try { wl?.release() } catch (e: Exception) { }
        try { pl?.release() } catch (e: Exception) { }
        wl = null; pl = null
    }
}

/** Ağda bulunan cihazlar. */
object Peers {
    private val m = LinkedHashMap<String, Peer>()
    @Synchronized fun put(svc: String, p: Peer) { m[svc] = p; Bus.changed() }
    @Synchronized fun del(svc: String) { m.remove(svc); Bus.changed() }
    @Synchronized fun list(): List<Peer> = m.values.distinctBy { it.id }
}

/** Cihaz kimliği: ilk açılışta üretilen rastgele kimlik (şifreleme / sertifika yok). */
object Identity {
    lateinit var id: String
    lateinit var name: String
    private var ok = false

    @Synchronized
    fun init(c: Context) {
        if (ok) return
        val app = c.applicationContext
        name = Settings.Global.getString(c.contentResolver, "device_name") ?: Build.MODEL
        val sp = app.getSharedPreferences("identity", 0)
        id = sp.getString("id", null) ?: UUID.randomUUID().toString().replace("-", "")
            .also { sp.edit().putString("id", it).apply() }
        ok = true
    }
}

/** Ağdaki cihazları paylaş menüsünde doğrudan hedef olarak gösterir. */
object Shortcuts {
    fun update(c: Context) {
        try {
            val sm = c.getSystemService(ShortcutManager::class.java)
            sm.dynamicShortcuts = Peers.list().take(4).map {
                ShortcutInfo.Builder(c, it.id)
                    .setShortLabel(it.name.ifBlank { c.getString(R.string.device_default) }.take(25))
                    .setIcon(Icon.createWithResource(c, R.drawable.ic))
                    .setCategories(setOf("io.github.idrisokys.quickshare.TARGET"))
                    .setLongLived(true)
                    .setIntent(Intent(c, ShareActivity::class.java).setAction("io.github.idrisokys.quickshare.SHORT"))
                    .build()
            }
        } catch (e: Exception) { }
    }
}
