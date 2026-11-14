package io.github.idrisokys.quickshare

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import android.widget.Toast

/** Sistem paylaş menüsünden gelen dosyaları servise devreder ve hemen kapanır. */
class ShareActivity : Activity() {
    private var d: Discovery? = null
    private val h = Handler(Looper.getMainLooper())
    private val t0 = SystemClock.uptimeMillis()
    private var found = -1L
    private var uris: List<Uri> = emptyList()
    private var only: String? = null

    private fun peers() = Peers.list().filter { only == null || it.id == only }

    @Suppress("DEPRECATION")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Identity.init(this)
        uris =
            if (intent.action == Intent.ACTION_SEND_MULTIPLE)
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        if (uris.isEmpty()) { finish(); return }
        only = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)

        // Servis zaten çalışıp cihazları bulduysa beklemeden gönder.
        if (peers().isNotEmpty()) { send(); return }

        setContentView(TextView(this).apply { setText(R.string.share_searching); textSize = 18f; setPadding(48, 48, 48, 48) })
        d = Discovery(this).also { it.browse() }
        poll()
    }

    /** Cihaz bulununca kısa süre daha bekler (birden çok cihaz varsa hepsi listeye girsin), bulunamazsa 3 sn'de vazgeçer. */
    private fun poll() {
        if (isFinishing) return
        val now = SystemClock.uptimeMillis()
        if (found < 0 && peers().isNotEmpty()) found = now
        when {
            found >= 0 && now - found >= 500 -> send()
            found < 0 && now - t0 >= 3000 -> send()
            else -> h.postDelayed({ poll() }, 150)
        }
    }

    private fun send() {
        if (peers().isEmpty()) Toast.makeText(this, R.string.no_device, Toast.LENGTH_LONG).show()
        else Sender.submit(this, uris, only)
        finish()
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); d?.stop(); super.onDestroy() }
}
