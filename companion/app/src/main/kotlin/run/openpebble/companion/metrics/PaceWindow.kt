package run.openpebble.companion.metrics

/**
 * Rolling-window current pace. Spec §5.3 (key 120) / §6.2.
 *
 * Maintains a sliding window of the last [windowMs] TrackPoint samples and
 * emits the current pace as `sec/mi`, derived from the mean speed in the
 * window. Output is capped at 3600 sec/mi (spec §5.3) — equivalent to a 1 mph
 * walk; below that we display "—" (caller's responsibility).
 *
 * Why a window rather than instantaneous speed:
 *  - GPS-derived speed is noisy; smoothing helps the watch UI.
 *  - 15 s is short enough to feel responsive but long enough to dampen
 *    multi-meter GPS jitter at typical run speeds.
 *
 * The window is purely time-bounded — sample count is unbounded. We trim
 * on every [push] / [paceSecPerMile] call.
 *
 * Not thread-safe. Call from a single thread (the Dashboard observer's looper
 * thread is fine).
 */
class PaceWindow(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private data class Sample(val timeMs: Long, val speedMs: Float)

    private val samples = ArrayDeque<Sample>()

    /** Add a TrackPoint sample. `speedMs` in meters/second; `timeMs` epoch ms. */
    fun push(timeMs: Long, speedMs: Float) {
        samples.addLast(Sample(timeMs, speedMs))
        trim(timeMs)
    }

    /**
     * Current pace as **seconds per mile**, capped at 3600 (spec §5.3).
     * Returns null when no samples in the window or mean speed is non-positive
     * (treated as "stopped"; caller renders "—").
     */
    fun paceSecPerMile(nowMs: Long): Int? {
        trim(nowMs)
        if (samples.isEmpty()) return null
        val meanSpeedMs = samples.sumOf { it.speedMs.toDouble() } / samples.size
        if (meanSpeedMs <= 0.0) return null
        // sec/mi = METERS_PER_MILE / m_per_s
        val pace = (METERS_PER_MILE / meanSpeedMs).toInt()
        return pace.coerceAtMost(MAX_PACE_SEC_PER_MILE)
    }

    /** Clear all samples (e.g., on run stop). */
    fun reset() { samples.clear() }

    /** Drop samples older than `nowMs - windowMs`. */
    private fun trim(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (samples.isNotEmpty() && samples.first().timeMs < cutoff) {
            samples.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 15_000L
        const val MAX_PACE_SEC_PER_MILE: Int = 3600
        // International mile (1609.344 m). Source: NIST.
        const val METERS_PER_MILE: Double = 1609.344
    }
}
