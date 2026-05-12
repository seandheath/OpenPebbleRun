package run.openpebble.companion.metrics

import kotlin.math.roundToLong

/**
 * Pure conversions from OpenTracks Track-table columns to watch wire types.
 * Spec §5.3 (keys 122, 123).
 *
 * Kept as top-level functions so they're trivial to unit-test in isolation.
 */
object TrackStats {

    /**
     * Spec §5.3 key 122: `Track.MOVINGTIME` (long ms) → seconds (uint32).
     * Negative inputs treated as 0 — they shouldn't occur but defensive
     * conversion avoids underflow when packing into a uint.
     */
    fun movingTimeMsToSec(ms: Long?): Long {
        if (ms == null || ms < 0) return 0L
        return ms / 1000L
    }

    /**
     * Spec §5.3 key 123: `Track.TOTALDISTANCE` (float meters) → hundredths of a
     * mile (uint32). Rounded half-up to the nearest hundredth.
     *
     * Worth packing as hundredths-mile rather than meters: a uint32 of meters
     * supports 4.29 Gm (way overkill); hundredths-mile keeps display arithmetic
     * trivial on the watch ("3.21 mi" = value 321) and avoids floating point in
     * Pebble C code.
     */
    fun meterToHundredthsMile(m: Float?): Long {
        if (m == null || m < 0f) return 0L
        val miles = m / PaceWindow.METERS_PER_MILE.toFloat()
        return (miles * 100f).toDouble().roundToLong().coerceAtLeast(0L)
    }
}
