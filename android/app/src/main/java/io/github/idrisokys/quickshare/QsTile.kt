package io.github.idrisokys.quickshare

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Hızlı ayarlar kutucuğu: dokun = dinlemeyi aç/kapat, uzun bas = uygulamayı aç. */
class QsTile : TileService() {

    override fun onStartListening() { render() }

    override fun onClick() {
        super.onClick()
        if (!ReceiverService.ready(this)) {      // izinler eksikse uygulamayı aç
            openApp(); return
        }
        if (ReceiverService.running) ReceiverService.stop(this) else ReceiverService.start(this)
        // servis durumu birkaç ms içinde değişir; kutucuğu hemen güncelle
        qsTile?.apply { state = if (ReceiverService.running) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE; updateTile() }
    }

    private fun render() {
        qsTile?.apply {
            state = if (ReceiverService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE))
        } else startActivityAndCollapse(i)
    }
}
