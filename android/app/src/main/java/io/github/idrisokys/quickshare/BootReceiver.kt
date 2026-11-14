package io.github.idrisokys.quickshare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Telefon açılınca / uygulama güncellenince, açık bıraktıysan dinlemeyi yeniden başlatır. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (ReceiverService.enabled(c) && ReceiverService.ready(c)) {
            try { ReceiverService.start(c) } catch (e: Exception) { }
        }
    }
}
