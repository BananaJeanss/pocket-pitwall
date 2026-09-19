package dev.bananajeans.pitwall.wear

import android.app.Application

/**
 * Watch application: owns the process-lifetime WearConnection (Data Layer
 * listeners must be registered whenever the phone might start a session,
 * not only while the UI is open).
 */
class PitwallWatchApplication : Application() {
    lateinit var connection: WearConnection
        private set

    override fun onCreate() {
        super.onCreate()
        connection = WearConnection(this).also { it.start() }
    }
}
