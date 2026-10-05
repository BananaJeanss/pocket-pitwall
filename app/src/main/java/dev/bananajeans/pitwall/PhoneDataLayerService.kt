package dev.bananajeans.pitwall

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/** Receives completed recordings even when no phone Activity is running. */
class PhoneDataLayerService : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        SessionRepository.initialize(applicationContext)
        WatchLink.initialize(applicationContext)
        // WLS callbacks run on a background thread. Hold this callback until
        // durable import finishes so Android retains the service during IO.
        runBlocking { WatchTransferManager.getInstance(this@PhoneDataLayerService).receive(channel)?.join() }
    }
}
