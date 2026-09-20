package dev.bananajeans.pitwall

import dev.bananajeans.pitwall.protocol.ClockSync
import dev.bananajeans.pitwall.protocol.PitwallJson
import dev.bananajeans.pitwall.protocol.WatchLogCodec
import dev.bananajeans.pitwall.protocol.WatchLogImporter

/**
 * Watch metadata attached to a phone session (issue #22).
 *
 * Stored inside session.json under the optional key "watch". Absent for
 * phone-only sessions — old sessions load byte-for-byte as before.
 * Missing/partial watch data is represented honestly (status + quality),
 * never faked with zero values.
 */
data class WatchSessionInfo(
    val status: Status,
    val deviceModel: String,
    val watchAppVersion: String,
    val logComplete: Boolean,
    val sampleCount: Long,
    /** Observed per-sensor rates from the log metadata (type -> Hz). */
    val sensorRates: Map<Int, Double>,
    /** Clock mapping used to align this stream to the phone timeline. */
    val sync: Sync?,
    /** Phone monotonic clock (elapsedRealtimeNanos) anchor persisted at
     *  import: the exact per-session base the analysis must subtract. */
    val phoneStartNanos: Long?,
    /** Log file name inside the session folder (null until imported). */
    val logFile: String?,
    /** Derived watch metrics (layer 6 writes these). */
    val metrics: Metrics?
) {

    enum class Status { PENDING, IMPORTED, UNAVAILABLE }

    data class Sync(
        /** offset + drift mapping from ClockSync.Fit at import time. */
        val offsetWatchMinusPhone: Double,
        val driftPerNano: Double,
        val bestRttNanos: Double,
        val residualRmsNanos: Double,
        val exchangesUsed: Int,
        val quality: ClockSync.Quality
    )

    data class Metrics(
        val steeringSmoothness: Double?,
        val correctionCount: Int?,
        val quality: String?
    )

    fun json(): PitwallJson.Value.Object {
        val rates = sensorRates.entries.map { (type, hz) ->
            PitwallJson.obj("type" to PitwallJson.n(type.toLong()), "hz" to PitwallJson.n(hz))
        }
        val sync = sync?.let {
            PitwallJson.obj(
                "offset" to PitwallJson.n(it.offsetWatchMinusPhone),
                "drift" to PitwallJson.n(it.driftPerNano),
                "bestRtt" to PitwallJson.n(it.bestRttNanos),
                "residualRms" to PitwallJson.n(it.residualRmsNanos),
                "exchanges" to PitwallJson.n(it.exchangesUsed.toLong()),
                "quality" to PitwallJson.s(it.quality.name)
            )
        } ?: PitwallJson.Value.Null
        val metrics = metrics?.let {
            PitwallJson.obj(
                "smoothness" to (it.steeringSmoothness?.let { v -> PitwallJson.n(v) } ?: PitwallJson.Value.Null),
                "corrections" to (it.correctionCount?.let { v -> PitwallJson.n(v.toLong()) } ?: PitwallJson.Value.Null),
                "quality" to (it.quality?.let { v -> PitwallJson.s(v) } ?: PitwallJson.Value.Null)
            )
        } ?: PitwallJson.Value.Null
        return PitwallJson.obj(
            "status" to PitwallJson.s(status.name),
            "device" to PitwallJson.s(deviceModel),
            "app" to PitwallJson.s(watchAppVersion),
            "complete" to PitwallJson.b(logComplete),
            "samples" to PitwallJson.n(sampleCount),
            "rates" to PitwallJson.Value.Array(rates),
            "phoneStartNanos" to (phoneStartNanos?.let { PitwallJson.n(it) } ?: PitwallJson.Value.Null),
            "sync" to sync,
            "logFile" to (logFile?.let { PitwallJson.s(it) } ?: PitwallJson.Value.Null),
            "metrics" to metrics
        )
    }

    companion object {
        fun fromJson(obj: PitwallJson.Value.Object): WatchSessionInfo? {
            val status = when (obj.string("status")) {
                "PENDING" -> Status.PENDING
                "IMPORTED" -> Status.IMPORTED
                "UNAVAILABLE" -> Status.UNAVAILABLE
                else -> return null
            }
            val rates = obj.array("rates")?.items?.mapNotNull { item ->
                val o = item as? PitwallJson.Value.Object ?: return@mapNotNull null
                val type = o.number("type")?.toInt() ?: return@mapNotNull null
                val hz = o.number("hz")?.toDouble() ?: return@mapNotNull null
                type to hz
            }?.toMap() ?: emptyMap()
            val sync = obj.obj("sync")?.let {
                val quality = try {
                    ClockSync.Quality.valueOf(it.string("quality") ?: "NONE")
                } catch (_: IllegalArgumentException) {
                    ClockSync.Quality.NONE
                }
                ClockSync.Fit(
                    offsetWatchMinusPhone = it.number("offset")?.toDouble() ?: 0.0,
                    driftPerNano = it.number("drift")?.toDouble() ?: 0.0,
                    bestRttNanos = it.number("bestRtt")?.toDouble() ?: 0.0,
                    medianRttNanos = 0.0,
                    residualRmsNanos = it.number("residualRms")?.toDouble() ?: 0.0,
                    exchangesUsed = it.number("exchanges")?.toInt() ?: 0,
                    exchangesTotal = it.number("exchanges")?.toInt() ?: 0,
                    rttOutliersRejected = 0
                ) to quality
            }
            val metrics = obj.obj("metrics")?.let {
                WatchSessionInfo.Metrics(
                    steeringSmoothness = it.number("smoothness")?.toDouble(),
                    correctionCount = it.number("corrections")?.toInt(),
                    quality = it.string("quality")
                )
            }
            return WatchSessionInfo(
                status = status,
                deviceModel = obj.string("device") ?: "unknown",
                watchAppVersion = obj.string("app") ?: "unknown",
                logComplete = obj.bool("complete") ?: false,
                sampleCount = obj.number("samples")?.toLong() ?: 0L,
                sensorRates = rates,
                sync = sync?.let { (fit, quality) ->
                    WatchSessionInfo.Sync(
                        offsetWatchMinusPhone = fit.offsetWatchMinusPhone,
                        driftPerNano = fit.driftPerNano,
                        bestRttNanos = fit.bestRttNanos,
                        residualRmsNanos = fit.residualRmsNanos,
                        exchangesUsed = fit.exchangesUsed,
                        quality = quality
                    )
                },
                phoneStartNanos = obj.number("phoneStartNanos")?.toLong(),
                logFile = obj.string("logFile"),
                metrics = metrics
            )
        }
    }
}
