package run.openpebble.companion.metrics

import kotlin.math.roundToLong

/**
 * Pure conversions from OpenTracks dashboard-API columns to watch wire types.
 * Spec §5.3 (keys 120, 122, 123).
 *
 * Kept as top-level functions so they're trivial to unit-test in isolation.
 */
object TrackStats {

    /** International mile in meters. Source: NIST. */
    const val METERS_PER_MILE: Double = 1609.344

    /** Spec §5.3: pace capped at 3600 sec/mi (≈1 mph walk). Below that → "--:--". */
    const val MAX_PACE_SEC_PER_MILE: Int = 3600

    /**
     * Spec §5.3 key 122: `Track.movingtime` (long ms) → seconds (uint32).
     * Negative inputs treated as 0 — defensive conversion avoids underflow when
     * packing into a uint.
     */
    fun movingTimeMsToSec(ms: Long?): Long {
        if (ms == null || ms < 0) return 0L
        return ms / 1000L
    }

    /**
     * Spec §5.3 key 123: `Track.totaldistance` (float meters) → hundredths of
     * a mile (uint32). Rounded half-up.
     *
     * Hundredths-mile rather than raw meters: keeps display arithmetic trivial
     * on the watch ("3.21 mi" = value 321) and avoids floating point in C.
     */
    fun meterToHundredthsMile(m: Float?): Long {
        if (m == null || m < 0f) return 0L
        val miles = m / METERS_PER_MILE.toFloat()
        return (miles * 100f).toDouble().roundToLong().coerceAtLeast(0L)
    }

    /**
     * Spec §5.3 key 120: pace from a mean speed derived from cumulative
     * `Track.totaldistance` / `Track.movingtime` deltas over a rolling window
     * (see [PaceWindow]). Capped at [MAX_PACE_SEC_PER_MILE].
     *
     * Returns null when undefined: zero/negative time delta (no window yet) or
     * zero/negative distance delta (stationary on the movingtime axis). The
     * watch renders "--:--" on null.
     *
     * Replaces the earlier instantaneous `paceFromSpeed(TrackPoint.speed)` —
     * see docs/log.md 2026-05-16 for the field-test rationale.
     */
    fun paceFromMeanSpeed(distM: Float, timeSec: Long): Int? {
        if (timeSec <= 0L || distM <= 0f) return null
        val speedMs = distM / timeSec.toFloat()
        val pace = (METERS_PER_MILE / speedMs).toInt()
        return pace.coerceAtMost(MAX_PACE_SEC_PER_MILE)
    }
}
