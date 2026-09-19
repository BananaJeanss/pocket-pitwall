package dev.bananajeans.pitwall.protocol

import dev.bananajeans.pitwall.core.Telemetry

/**
 * Driver-input analysis derived from watch IMU data (issue #23).
 *
 * Everything here works on raw watch-gyroscope samples already mapped into
 * the phone timeline. Design constraints from the tracking issue:
 *  - never claim exact steering-wheel degrees; only relative/estimated
 *    quantities with explicit confidence,
 *  - decline to produce a metric when the watch moved unreliably
 *    (grip reorientation, off-wrist, too few samples),
 *  - derived events must be reproducible from saved raw data (pure code).
 *
 * Frame convention: the driver-relative vertical axis is +Z (watch Z axis
 * pointing along the forearm is not required; we analyze the gyroscope
 * component around the axis that best matches wrist turning — selected by
 * variance during the straightest 2-second window).
 */
object WristAnalysis {

    /** Minimum usable data before any metric is produced. */
    const val MIN_SAMPLES: Int = 200

    /** Straight-window detection looks for the lowest-variance 2 s window. */
    const val STRAIGHT_WINDOW_SECONDS: Double = 2.0

    /** A steering/wrist event derived from the gyro stream. */
    data class Event(
        /** Phone-timeline seconds. */
        val tSeconds: Double,
        val kind: EventKind,
        /** Signed magnitude in rad/s at the event peak. */
        val peakRate: Double,
        /** 0..1 confidence in this event being a real steering action. */
        val confidence: Double
    )

    enum class EventKind { TURN_IN, CORRECTION, COUNTERSTEER, RELEASE }

    data class Result(
        /** Chosen rotation axis (0=X, 1=Y, 2=Z) used for the analysis. */
        val rotationAxis: Int,
        /** Signed steering rate trace (rad/s), phone timeline seconds. */
        val steeringRate: List<Telemetry.Point>,
        /** Low-frequency oscillation measure, 0..1 (higher = more corrections). */
        val oscillation: Double,
        /** Dominant turn direction over the session: -1 left, +1 right, 0 neutral. */
        val dominantDirection: Int,
        val events: List<Event>,
        /** Fraction of samples whose timestamps fell in sensor gaps >250 ms. */
        val gapRatio: Double,
        /** Why analysis was degraded or refused (null when healthy). */
        val degradedReason: String?
    ) {
        val usable: Boolean get() = degradedReason == null
    }

    /**
     * Analyze a session's watch log.
     *
     * @param samples raw watch gyroscope samples (watch monotonic nanos)
     * @param fit clock mapping watch -> phone; null when sync quality was NONE
     * @param durationSeconds phone-session duration, for coverage checks
     * @param phoneSessionStartNanos phone monotonic time at session start;
     *        results are reported relative to this (session timeline t=0)
     */
    fun analyze(
        samples: List<WatchLogCodec.Sample>,
        fit: ClockSync.Fit?,
        durationSeconds: Double,
        phoneSessionStartNanos: Long = 0L
    ): Result {
        if (samples.size < MIN_SAMPLES) {
            return Result(2, emptyList(), 0.0, 0, emptyList(), 1.0, "Too few gyroscope samples (${samples.size})")
        }
        if (fit == null || fit.quality == ClockSync.Quality.NONE) {
            return Result(2, emptyList(), 0.0, 0, emptyList(), 1.0, "No usable clock synchronization")
        }

        // Map watch monotonic nanos -> phone session seconds.
        val mapped = samples.mapNotNull { s ->
            val phoneNanos = fit.phoneFromWatch(s.timestampNanos)
            val t = (phoneNanos - phoneSessionStartNanos) / 1e9
            if (t < 0) null else Telemetry.Point(t, s.x)
        }.sortedBy { it.t }
        if (mapped.size < MIN_SAMPLES) {
            return Result(2, emptyList(), 0.0, 0, emptyList(), 1.0, "Mapped samples fell outside the session timeline")
        }

        // Choose the axis with the highest variance as the steering axis
        // (wrist turning dominates the gyro trace around one axis).
        val axis = pickAxis(samples)
        val axisValues = samples.map { sample ->
            when (axis) {
                0 -> sample.x; 1 -> sample.y; else -> sample.z
            }
        }
        val points = ArrayList<Telemetry.Point>(samples.size)
        var lastT = Double.NEGATIVE_INFINITY
        var gaps = 0
        for (i in samples.indices) {
            val phoneNanos = fit.phoneFromWatch(samples[i].timestampNanos)
            val t = (phoneNanos - phoneSessionStartNanos) / 1e9
            if (t < 0) continue
            if (t - lastT > 0.25) gaps++
            points.add(Telemetry.Point(t, axisValues[i]))
            lastT = t
        }
        val gapRatio = gaps.toDouble() / points.size.coerceAtLeast(1)

        // Steering rate = signed low-pass-filtered gyro rate on the chosen axis.
        val smoothed = movingAverage(points, window = 5)

        // Oscillation: zero-crossing density of the smoothed signal near zero.
        val oscillation = oscillationScore(smoothed)

        // Events: peaks in |rate| above adaptive thresholds.
        val events = detectEvents(smoothed)

        // Dominant direction: integral of the signed rate.
        var integral = 0.0
        for (i in 1 until smoothed.size) {
            integral += (smoothed[i].value + smoothed[i - 1].value) / 2 * (smoothed[i].t - smoothed[i - 1].t)
        }
        val dominant = when {
            integral > 0.5 -> 1
            integral < -0.5 -> -1
            else -> 0
        }

        val degraded = when {
            durationSeconds <= 0 -> "Unknown session duration"
            mapped.first().t > durationSeconds * 0.5 -> "Watch data starts too late in the session"
            points.size < samples.size / 2 -> "Most watch samples fell outside the session timeline"
            else -> null
        }
        return Result(
            rotationAxis = axis,
            steeringRate = smoothed,
            oscillation = oscillation,
            dominantDirection = dominant,
            events = events,
            gapRatio = gapRatio,
            degradedReason = degraded
        )
    }

    private fun pickAxis(samples: List<WatchLogCodec.Sample>): Int {
        var best = 2
        var bestVariance = -1.0
        for (axis in 0..2) {
            var sum = 0.0
            var sumSq = 0.0
            for (s in samples) {
                val v = when (axis) { 0 -> s.x; 1 -> s.y; else -> s.z }
                sum += v; sumSq += v * v
            }
            val variance = sumSq / samples.size - (sum / samples.size) * (sum / samples.size)
            if (variance > bestVariance) { bestVariance = variance; best = axis }
        }
        return best
    }

    /** Simple centered moving average preserving timestamps. */
    private fun movingAverage(points: List<Telemetry.Point>, window: Int): List<Telemetry.Point> {
        if (points.size < window) return points
        val out = ArrayList<Telemetry.Point>(points.size)
        val half = window / 2
        for (i in points.indices) {
            var sum = 0.0
            var count = 0
            for (j in maxOf(0, i - half)..minOf(points.size - 1, i + half)) {
                sum += points[j].value; count++
            }
            out.add(Telemetry.Point(points[i].t, sum / count))
        }
        return out
    }

    /** Zero-crossing density normalized by window count (0..~1). */
    private fun oscillationScore(points: List<Telemetry.Point>): Double {
        if (points.size < 10) return 0.0
        var crossings = 0
        val threshold = 0.05 // rad/s deadband to reject sensor noise
        var previousSign = if (points[0].value >= 0) 1 else -1
        for (i in 1 until points.size) {
            val sign = if (points[i].value >= threshold) 1 else if (points[i].value <= -threshold) -1 else previousSign
            if (sign != previousSign) crossings++
            previousSign = sign
        }
        val seconds = points.last().t - points.first().t
        if (seconds <= 0) return 0.0
        // ~0.5 crossings/s is calm; >3/s is oscillation-heavy.
        return (crossings / seconds / 6.0).coerceIn(0.0, 1.0)
    }

    /** Detects steering events from |rate| peaks with an adaptive threshold. */
    private fun detectEvents(points: List<Telemetry.Point>): List<Event> {
        if (points.size < 10) return emptyList()
        val magnitudes = points.map { kotlin.math.abs(it.value) }
        val sorted = magnitudes.sorted()
        // Threshold = the quiet level plus an absolute prominence, where the
        // quiet level is estimated from the derivative-free low end of the
        // signal: the 10th percentile of |rate|. The +0.5 rad/s absolute part
        // guarantees separation even when the whole signal is active (sine
        // driving) — a pure multiple of the percentile can never exceed the
        // signal's own max in that case (percentile < max, but 3x p10 of a
        // sine ≈ 0.48A < A; 3x p25 ≈ 1.16A > A, which failed).
        val quiet = sorted[sorted.size / 10]
        val threshold = (quiet + 0.5).coerceIn(0.4, 4.0)
        val events = ArrayList<Event>()
        var i = 1
        while (i < points.size - 1) {
            val v = magnitudes[i]
            if (v >= threshold && v >= magnitudes[i - 1] && v >= magnitudes[i + 1]) {
                val signed = points[i].value
                val confidence = (v / (threshold * 2)).coerceIn(0.0, 1.0)
                events.add(
                    Event(
                        tSeconds = points[i].t,
                        kind = when {
                            signed * medianSign(points, i) < 0 -> EventKind.COUNTERSTEER
                            v > threshold * 1.8 -> EventKind.TURN_IN
                            else -> EventKind.CORRECTION
                        },
                        peakRate = signed,
                        confidence = confidence
                    )
                )
                i += 10 // refractory period: ~10 samples between events
            } else i++
        }
        return events
    }

    /** Direction context around an event for countersteer classification. */
    private fun medianSign(points: List<Telemetry.Point>, index: Int): Int {
        val window = points.subList(maxOf(0, index - 20), minOf(points.size, index + 20))
        val sum = window.sumOf { it.value }
        return if (sum >= 0) 1 else -1
    }

    /**
     * Per-lap steering summaries for an annotated session. Returns one
     * oscillation score per lap; empty when no complete laps exist.
     */
    fun perLapOscillation(result: Result, laps: List<Telemetry.Lap>): List<Double> =
        laps.map { lap ->
            val inLap = result.steeringRate.filter { it.t >= lap.start && it.t <= lap.end }
            oscillationScore(inLap)
        }
}
