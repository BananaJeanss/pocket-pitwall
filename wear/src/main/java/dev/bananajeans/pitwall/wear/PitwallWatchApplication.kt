package dev.bananajeans.pitwall.wear

import android.app.Application

/**
 * Watch application: owns the process-lifetime WearConnection and the
 * TransferQueue (Data Layer listeners must be registered whenever the phone
 * might start a session or request a log, not only while the UI is open).
 */
class PitwallWatchApplication : Application() {
    lateinit var connection: WearConnection
        private set
    lateinit var transferQueue: TransferQueue
        private set

    override fun onCreate() {
        super.onCreate()
        WatchLogStore(this).recover()
        transferQueue = TransferQueue(this).also { it.start() }
        connection = WearConnection(this, transferQueue).also { it.start() }
    }
}
