package io.github.idrisokys.quickshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Arka planda sürekli dinler. Kalıcı bildirim: bekleme / "Gönderiliyor: dosya" / "Alınıyor: dosya" + ilerleme.
 * Gönderimler de burada (tek iş parçacığında, sırayla) yapılır; paylaş ekranı hemen kapanabilir.
 */
class ReceiverService : Service() {
    private var rx: Receiver? = null
    private var disc: Discovery? = null
    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    private val back = Runnable { show(getString(R.string.notif_title), -1) }

    // PendingIntent ve Builder bir kez kurulur; ilerleme güncellemelerinde yeniden üretilmez.
    private val stopPi by lazy {
        PendingIntent.getService(this, 0, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
    }
    private val openPi by lazy {
        PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java).putExtra("folder", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private val nb by lazy {
        Notification.Builder(this, "qs_run")
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.tile_label))
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_tile), getString(R.string.notif_stop), stopPi).build())
    }

    companion object {
        @Volatile var running = false
        const val ACTION_STOP = "io.github.idrisokys.quickshare.STOP"
        const val ACTION_SEND = "io.github.idrisokys.quickshare.SEND"

        private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("cfg", 0)
        fun enabled(c: Context) = prefs(c).getBoolean("on", true)

        /** Bildirim izni (Android 13+) ve dosya erişimi verilmiş mi? */
        fun ready(c: Context): Boolean {
            val notif = Build.VERSION.SDK_INT < 33 ||
                c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
            return notif && SaveDir.direct(c)
        }

        fun start(c: Context) {
            prefs(c).edit().putBoolean("on", true).apply()
            c.startForegroundService(Intent(c, ReceiverService::class.java))
        }

        fun stop(c: Context) {
            prefs(c).edit().putBoolean("on", false).apply()
            c.stopService(Intent(c, ReceiverService::class.java))
        }

        /**
         * "Kapat" düğmesi: dinlemeyi/gönderimi durdurur, bildirimi kaldırır ve işlemi tamamen sonlandırır.
         * Servisin onDestroy'u (mDNS kaydını kaldırma, soket kapatma) çalışsın diye işlem kısa bir gecikmeyle öldürülür.
         * "on" kapatıldığı için açılışta kendiliğinden başlamaz; uygulamayı ya da kutucuğu açınca yeniden başlar.
         */
        fun quit(c: Context) {
            stop(c)
            Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
        }

        fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.deleteNotificationChannel("qs")
            nm.createNotificationChannel(NotificationChannel("qs_run", c.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel("qs_recv", c.getString(R.string.notif_recv_channel), NotificationManager.IMPORTANCE_HIGH))
        }

        /** Dosya geldiğinde ayrı, açılır bildirim. Dokununca Link klasörü açılır. */
        fun notifyReceived(c: Context, name: String) {
            val i = Intent(c, MainActivity::class.java).putExtra("folder", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(c, name.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, "qs_recv")
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(c.getString(R.string.notif_received))
                .setContentText(name)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            c.getSystemService(NotificationManager::class.java).notify(1000 + (System.nanoTime() % 100000).toInt().let { if (it < 0) -it else it }, n)
        }
    }

    override fun onBind(i: Intent?) = null

    private fun show(text: String, pct: Int) {
        try {
            val n = nb.setContentText(text).setProgress(if (pct >= 0) 100 else 0, pct.coerceAtLeast(0), false).build()
            getSystemService(NotificationManager::class.java).notify(1, n)
        } catch (e: Exception) { }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        Identity.init(this)
        channels(this)
        startForeground(1, nb.setContentText(getString(R.string.notif_title)).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        // Gönderme/alma ilerlemesini bildirime yansıt
        Bus.sink = { s, p ->
            h.post {
                h.removeCallbacks(back)
                if (s.isNotEmpty()) show(s, p)
                if (p < 0) h.postDelayed(back, 4000)
            }
        }

        rx = Receiver(this).also { it.start() }
        disc = Discovery(this).also { it.register(rx!!.port); it.browse() }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val it = i ?: return START_STICKY   // sistem yeniden başlatınca intent null gelir
        when (it.action) {
            ACTION_STOP -> {
                getSharedPreferences("cfg", 0).edit().putBoolean("on", false).apply()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SEND -> {
                val cd = it.clipData
                val uris = (0 until (cd?.itemCount ?: 0)).mapNotNull { n -> cd?.getItemAt(n)?.uri }
                val only = it.getStringExtra("only")
                if (uris.isNotEmpty()) {
                    pending.incrementAndGet()
                    io.execute {
                        try { Sender.run(this, only, uris) }
                        finally {
                            // Kullanıcı dinlemeyi kapattıysa, gönderim bitince servis de kapanır.
                            if (pending.decrementAndGet() == 0 && !enabled(this)) stopSelf()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Bus.sink = null
        h.removeCallbacksAndMessages(null)
        io.shutdown()
        disc?.stop(); rx?.stop()
        super.onDestroy()
    }
}
