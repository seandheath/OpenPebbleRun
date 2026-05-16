package run.openpebble.companion.metrics

/**
 * Rolling-window pace calculator over cumulative `(movingTime, distance)` samples.
 *
 * Spec §5.3: pace = Δdistance / Δmovingtime across the oldest sample within the
 * configured window and the newest. Using `movingtime` (not wall-clock) as the
 * window axis means the window naturally excludes paused periods — when the user
 * stops moving, `movingtime` freezes and the window slides only on actual
 * running time. This also keeps pace consistent with the displayed TIME field,
 * which is derived from the same `movingtime`.
 *
 * Why endpoint delta rather than per-sample integration: with cumulative
 * counters, `(newest.dist − oldest.dist) / (newest.time − oldest.time)` is
 * mathematically the time-weighted mean speed over the spanned interval. No
 * per-sample accumulation buys us anything.
 *
 * Replaces the instantaneous `TrackStats.paceFromSpeed(TrackPoint.speed)` path
 * which was visibly jittery in field testing — see docs/log.md 2026-05-16.
 */
class PaceWindow(private val windowSec: Long = DEFAULT_WINDOW_SEC) {

    private data class Sample(val movingTimeSec: Long, val distMeters: Float)

    // Oldest at index 0, newest at the back. We retain exactly the samples
    // needed to span [newest.t − windowSec, newest.t]: one front sample older
    // than the window edge plus everything inside it. On push we trim from the
    // front whenever samples[1] is itself >= windowSec old, since then
    // samples[0] would be redundant.
    private val samples = ArrayDeque<Sample>()

    /**
     * Push a new cumulative sample. No-op on a repeated `movingTimeSec` —
     * OpenTracks's Track row only ticks `movingtime` forward when it detects
     * actual movement, so consecutive polls during a stop produce duplicates
     * we don't want to flood the buffer with.
     */
    fun push(movingTimeSec: Long, distMeters: Float) {
        val last = samples.lastOrNull()
        if (last != null && last.movingTimeSec == movingTimeSec) return
        samples.addLast(Sample(movingTimeSec, distMeters))
        // Trim while the second-oldest is itself old enough to serve as our
        // window-edge sample. This keeps a single sample older than the window
        // (so paceSecPerMile spans the full windowSec) without unbounded growth.
        while (samples.size >= 2 && (movingTimeSec - samples[1].movingTimeSec) >= windowSec) {
            samples.removeFirst()
        }
    }

    /**
     * Current pace in seconds per mile, or null when undefined:
     *  - fewer than 2 samples (warm-up — first poll after run start),
     *  - Δmovingtime < 1 s (samples coincide — should not happen given push's
     *    dedupe, but defensive),
     *  - Δdistance ≤ 0 (user is stationary on the movingtime axis — also rare,
     *    movingtime usually freezes before this triggers).
     *
     * The watch renders `--:--` on null.
     */
    fun paceSecPerMile(): Int? {
        if (samples.size < 2) return null
        val newest = samples.last()
        val oldest = samples.first()
        val dt = newest.movingTimeSec - oldest.movingTimeSec
        if (dt < 1L) return null
        val dDist = newest.distMeters - oldest.distMeters
        return TrackStats.paceFromMeanSpeed(dDist, dt)
    }

    /** Drop all buffered samples. Call between runs so a second run starts fresh. */
    fun reset() {
        samples.clear()
    }

    companion object {
        /** Spec §5.3: 15 s. Aligns with the watch-side cadence window. */
        const val DEFAULT_WINDOW_SEC: Long = 15L
    }
}
