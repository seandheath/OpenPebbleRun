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
     * Spec §5.3 key 120: TrackPoint `speed` (m/s, from OpenTracks dashboard
     * API) → pace as seconds per mile. Capped at [MAX_PACE_SEC_PER_MILE].
     *
     * Returns null when speed is null or non-positive — the watch renders
     * "--:--" in that case. Common cases:
     *  - The cursor's only row is a SEGMENT_START marker (`type=-2`,
     *    `speed=null`) — happens before the device has moved past OpenTracks's
     *    min-distance-from-previous threshold.
     *  - The user is stationary (`speed=0`).
     *
     * No smoothing window — we display what OpenTracks reports. (Earlier the
     * spec specified a 15 s rolling mean; that approach was abandoned in favor
     * of letting OpenTracks be the source of truth — see docs/log.md.)
     */
    fun paceFromSpeed(speedMs: Float?): Int? {
        if (speedMs == null || speedMs <= 0f) return null
        val pace = (METERS_PER_MILE / speedMs).toInt()
        return pace.coerceAtMost(MAX_PACE_SEC_PER_MILE)
    }
}
