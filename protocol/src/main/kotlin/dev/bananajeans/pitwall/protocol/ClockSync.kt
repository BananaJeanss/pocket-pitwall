package dev.bananajeans.pitwall.protocol

/**
 * Phone/watch clock synchronization (issue #20).
 *
 * Both devices log telemetry against their own monotonic clocks
 * (elapsedRealtimeNanos), whose origins differ. The phone periodically sends
 * [Messages.SyncPing] (t1 = phone send time) and the watch replies with a
 * [Messages.SyncPong] carrying (t1, t2 = watch receive time, t3 = watch send
 * time). Given a set of such exchanges we estimate a mapping
 *
 *     watchNanos = offset + (1 + drift) * phoneNanos   ... (watch <- phone)
 *
 * i.e. the phone timeline is the session's canonical timeline (it owns the
 * session), and watch samples are transformed into it by the inverse map.
 * This class works in the "phone -> watch" direction and exposes both maps.
 *
 * Estimation (deliberately simple, deterministic and unit-testable):
 *  1. Each exchange yields rtt = (t4 - t1) - (t3 - t2) and offset = ((t2-t1)
 *     + (t3-t4))/2 (NTP-style, using the *minimum-latency* assumption).
 *  2. Exchanges are filtered: RTT outliers are rejected (median absolute
 *     deviation from the median RTT, or an absolute cap).
 *  3. The remaining points are fit with least squares: watchTime ~ a + b*phoneTime.
 *     b captures drift (nominal 1.0), a the offset.
 *  4. Quality is reported as (bestRtt, medianRtt, n, residualRms) and a
 *     coarse grade used by analysis to decide whether fusion is allowed.
 */
public object ClockSync {

    /** One completed ping/pong exchange (all nanos, monotonic per device). */
    public data class Exchange(
        val t1PhoneNanos: Long,
        val t4PhoneNanos: Long,
        val t2WatchNanos: Long,
        val t3WatchNanos: Long
    ) {
        init {
            require(t4PhoneNanos >= t1PhoneNanos) { "t4 before t1 (phone clock went backwards)" }
            require(t3WatchNanos >= t2WatchNanos) { "t3 before t2 (watch clock went backwards)" }
        }
    }

    /** Result of fitting exchanges. Immutable; query via the map functions. */
    public data class Fit(
        val offsetWatchMinusPhone: Double,
        val driftPerNano: Double,
        val bestRttNanos: Double,
        val medianRttNanos: Double,
        val residualRmsNanos: Double,
        val exchangesUsed: Int,
        val exchangesTotal: Int,
        val rttOutliersRejected: Int
    ) {
        /** Map a watch timestamp into the phone timeline. */
        public fun phoneFromWatch(watchNanos: Long): Double {
            // watch = offset + (1+drift)*phone  =>  phone = (watch - offset)/(1+drift)
            return (watchNanos - offsetWatchMinusPhone) / (1.0 + driftPerNano)
        }

        /** Map a phone timestamp into the watch timeline. */
        public fun watchFromPhone(phoneNanos: Long): Double {
            return offsetWatchMinusPhone + (1.0 + driftPerNano) * phoneNanos
        }

        /**
         * Coarse sync quality used by consumers:
         *  - EXCELLENT: residual RMS < 1 ms and best RTT < 50 ms
         *  - GOOD:      residual RMS < 5 ms
         *  - POOR:      anything usable at all
         *  - NONE:      not enough data
         */
        public val quality: Quality
            get() = when {
                exchangesUsed < 3 -> Quality.NONE
                residualRmsNanos < 1_000_000 && bestRttNanos < 50_000_000 -> Quality.EXCELLENT
                residualRmsNanos < 5_000_000 -> Quality.GOOD
                exchangesUsed >= 3 -> Quality.POOR
                else -> Quality.NONE
            }
    }

    public enum class Quality { EXCELLENT, GOOD, POOR, NONE }

    /** Exchanges whose RTT exceeds median + this multiple of MAD are dropped. */
    private const val RTT_MAD_FACTOR: Double = 4.0

    /** Hard RTT cap: exchanges slower than 3 s are useless for sync. */
    private const val RTT_ABSOLUTE_CAP_NANOS: Double = 3e9

    /**
     * Fits offset + drift from exchanges.
     * @throws IllegalArgumentException when fewer than 2 exchanges remain after
     *         filtering (a single exchange cannot estimate drift).
     */
    public fun fit(exchanges: List<Exchange>): Fit {
        require(exchanges.size >= 2) { "Need at least 2 exchanges" }
        val perExchange = exchanges.map { e ->
            val rtt = (e.t4PhoneNanos - e.t1PhoneNanos) - (e.t3WatchNanos - e.t2WatchNanos)
            require(rtt >= 0) { "Negative RTT (clocks violate assumptions) at t1=${e.t1PhoneNanos}" }
            // Offset in the direction watch - phone at the midpoint of the exchange.
            val offset = ((e.t2WatchNanos - e.t1PhoneNanos).toDouble() + (e.t3WatchNanos - e.t4PhoneNanos).toDouble()) / 2.0
            Triplet(t1 = e.t1PhoneNanos, rtt = rtt.toDouble(), offset = offset)
        }

        // RTT outlier rejection by MAD around the median RTT.
        val sortedRtt = perExchange.map { it.rtt }.sorted()
        val medianRtt = median(sortedRtt)
        val mad = median(sortedRtt.map { kotlin.math.abs(it - medianRtt) }.sorted())
        val kept = perExchange.filter {
            it.rtt <= RTT_ABSOLUTE_CAP_NANOS &&
                (mad <= 0.0 || it.rtt <= medianRtt + RTT_MAD_FACTOR * mad)
        }
        if (kept.size < 2) {
            // Fall back to absolute-cap-only filtering before giving up.
            val capped = perExchange.filter { it.rtt <= RTT_ABSOLUTE_CAP_NANOS }
            if (capped.size >= 2) return fitTrimmed(capped, perExchange.size)
            throw IllegalArgumentException("Not enough usable exchanges after RTT filtering (${kept.size})")
        }
        return fitTrimmed(kept, perExchange.size)
    }

    private class Triplet(val t1: Long, val rtt: Double, val offset: Double)

    private fun fitTrimmed(points: List<Triplet>, total: Int): Fit {
        // Least squares of: watchMidpointTime = a + b * phoneTime.
        // We approximate "watch midpoint time" as t2 (receive time), the
        // classic NTP reference point, and pair it with phone t1. Using t1
        // rather than the phone midpoint avoids double-counting delay.
        val n = points.size
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (p in points) {
            val x = p.t1.toDouble()
            val y = p.t1.toDouble() + p.offset
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val denom = n * sxx - sx * sx
        if (denom == 0.0) throw IllegalArgumentException("Degenerate exchange timestamps (all identical)")
        val slope = (n * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / n

        var residualSq = 0.0
        for (p in points) {
            val predicted = intercept + slope * p.t1.toDouble()
            val y = p.t1.toDouble() + p.offset
            val r = y - predicted
            residualSq += r * r
        }
        val residualRms = kotlin.math.sqrt(residualSq / n)

        return Fit(
            offsetWatchMinusPhone = intercept,
            driftPerNano = slope - 1.0,
            bestRttNanos = points.minOf { it.rtt },
            medianRttNanos = median(points.map { it.rtt }.sorted()),
            residualRmsNanos = residualRms,
            exchangesUsed = n,
            exchangesTotal = total,
            rttOutliersRejected = total - n
        )
    }

    private fun median(sorted: List<Double>): Double =
        if (sorted.isEmpty()) 0.0
        else if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
}
