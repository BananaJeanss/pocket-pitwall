package dev.bananajeans.pitwall

import android.content.Context
import dev.bananajeans.pitwall.core.ReleaseVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data object NoRelease : UpdateState
    data class Available(val version: String, val url: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

object UpdateChecker {
    const val RELEASES = "https://github.com/BananaJeanss/pocket-pitwall/releases"
    fun cached(context: Context): UpdateState {
        val prefs=context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val tag=prefs.getString("version", null)
        val url=prefs.getString("url", null)
        return if (tag != null && url != null && url.startsWith("$RELEASES/tag/") && ReleaseVersion.newer(tag, BuildConfig.VERSION_NAME))
            UpdateState.Available(tag,url) else UpdateState.Idle
    }
    fun due(context: Context): Boolean = System.currentTimeMillis() -
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).getLong("checked", 0) > 86_400_000

    suspend fun check(context: Context): UpdateState = withContext(Dispatchers.IO) {
        val connection = URL("https://api.github.com/repos/BananaJeanss/pocket-pitwall/releases/latest").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "PocketPitwall/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            val result = when (status) {
                404 -> UpdateState.NoRelease
                403, 429 -> UpdateState.Failed("GitHub is busy. Try again later.")
                200 -> {
                    val body = connection.inputStream.bufferedReader().use { reader ->
                        val out = StringBuilder(); val chunk = CharArray(4096)
                        while (true) {
                            val count = reader.read(chunk)
                            if (count < 0) break
                            require(out.length + count <= 1_000_000) { "Release response too large" }
                            out.append(chunk, 0, count)
                        }
                        out.toString()
                    }
                    val release = JSONObject(body)
                    val tag = release.getString("tag_name")
                    val url = release.getString("html_url")
                    val assets = release.optJSONArray("assets")
                    val hasApk = assets != null && (0 until assets.length()).any { assets.getJSONObject(it).optString("name").endsWith(".apk") }
                    if (release.optBoolean("draft") || release.optBoolean("prerelease") || !hasApk) UpdateState.NoRelease
                    else if (ReleaseVersion.newer(tag, BuildConfig.VERSION_NAME) && url.startsWith("$RELEASES/tag/")) UpdateState.Available(tag, url)
                    else UpdateState.Current
                }
                else -> UpdateState.Failed("Update check failed ($status).")
            }
            if (result !is UpdateState.Failed) {
                context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
                    .putLong("checked", System.currentTimeMillis())
                    .putString("version", (result as? UpdateState.Available)?.version)
                    .putString("url", (result as? UpdateState.Available)?.url).apply()
            }
            result
        } catch (_: Exception) { UpdateState.Failed("Couldn't reach GitHub. Check your connection.") }
        finally { connection.disconnect() }
    }
}
