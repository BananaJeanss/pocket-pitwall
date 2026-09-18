package dev.bananajeans.pitwall

import android.content.Context

data class AppSettings(
    val theme: String = "System",
    val dynamicColors: Boolean = true,
    val fullscreen: Boolean = false,
    val autoUpdates: Boolean = true,
    val defaultTrack: String = "Motorcity · Underground",
    val defaultReverse: Boolean = false,
    val backupTreeUri: String = "",
    val autoBackups: Boolean = true
) {
    fun save(context: Context) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("theme", theme)
            .putBoolean("dynamic", dynamicColors)
            .putBoolean("fullscreen", fullscreen)
            .putBoolean("updates", autoUpdates)
            .putString("track", defaultTrack)
            .putBoolean("reverse", defaultReverse)
            .putString("backup_tree_uri", backupTreeUri)
            .putBoolean("auto_backups", autoBackups)
            .apply()
    }

    companion object {
        fun read(context: Context): AppSettings {
            val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            return AppSettings(
                theme=p.getString("theme", "System") ?: "System",
                dynamicColors=p.getBoolean("dynamic", true),
                fullscreen=p.getBoolean("fullscreen", false),
                autoUpdates=p.getBoolean("updates", true),
                defaultTrack=p.getString("track", "Motorcity · Underground") ?: "Motorcity · Underground",
                defaultReverse=p.getBoolean("reverse", false),
                backupTreeUri=p.getString("backup_tree_uri", "") ?: "",
                autoBackups=p.getBoolean("auto_backups", true)
            )
        }
    }
}
