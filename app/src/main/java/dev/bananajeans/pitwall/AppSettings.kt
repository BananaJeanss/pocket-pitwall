package dev.bananajeans.pitwall

import android.content.Context

data class AppSettings(
    val theme: String = "System",
    val dynamicColors: Boolean = true,
    val fullscreen: Boolean = false,
    val autoUpdates: Boolean = true,
    val defaultTrack: String = "Motorcity · Underground",
    val defaultReverse: Boolean = false
) {
    fun save(context: Context) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("theme", theme).putBoolean("dynamic", dynamicColors)
            .putBoolean("fullscreen", fullscreen).putBoolean("updates", autoUpdates)
            .putString("track", defaultTrack).putBoolean("reverse", defaultReverse).apply()
    }
    companion object {
        fun read(context: Context): AppSettings {
            val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            return AppSettings(p.getString("theme", "System") ?: "System", p.getBoolean("dynamic", true),
                p.getBoolean("fullscreen", false), p.getBoolean("updates", true),
                p.getString("track", "Motorcity · Underground") ?: "Motorcity · Underground", p.getBoolean("reverse", false))
        }
    }
}
