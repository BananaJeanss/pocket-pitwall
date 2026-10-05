package dev.bananajeans.pitwall.wear

import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/** Play services can start this listener after Wear OS has reclaimed our process. */
class WatchDataLayerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == TransferQueue.PATH_PULL) {
            // Keep the service callback alive while its background IO drains
            // the queue; the app need not have an Activity or recorder running.
            runBlocking { (application as PitwallWatchApplication).transferQueue.serveAll()?.join() }
            return
        }
        // Keep control decisions on the same thread as the recorder callbacks.
        Handler(Looper.getMainLooper()).post {
            (application as PitwallWatchApplication).connection.onMessage(event)
        }
    }
}
