package run.openpebble.companion

import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import run.openpebble.companion.metrics.TrackStats
import run.openpebble.companion.pebble.PebbleMessenger
import run.openpebble.companion.pebble.RunSession

/**
 * OpenTracks Dashboard receiver. Spec §6.2.
 *
 * Verified against OpenTracks v4.27.0 (`DataProvider.java`,
 * `CustomContentProvider.java`):
 *
 *  - All three URIs ride in `intent.clipData` — `[0]` Track, `[1]` TrackPoints,
 *    `[2]` Markers. `intent.data` is unused.
 *  - The dashboard URIs are explicit `/dashboard/...` paths, distinct from the
 *    internal `/trackpoints/trackid/...` URIs (the dashboard provider applies
 *    `DataProvider.DATA_PROJECTIONMAP_*` to expose a restricted column set).
 *  - TrackPoints projection in v4.27 is `_id, trackid, latitude, longitude,
 *    time, type, speed` — no `sensor_heartrate`, no `sensor_cadence`. HR/
 *    cadence won't come through this URI; spec §4.3's external-HR-via-
 *    OpenTracks path needs revisiting (filed as a TODO in docs/log.md).
 *  - The TrackPoints URI typically holds a single SEGMENT_START_MANUAL marker
 *    (`type = -2`, `speed = null`) until the device has moved past OpenTracks's
 *    min-distance-from-previous threshold. So the first samples come through
 *    as null `speed` — we render "--:--" in that case and let real data take
 *    over once OpenTracks inserts a normal TrackPoint.
 *
 * Lifecycle role:
 *  - onCreate sends RUN_STARTED to the watch (canonical "OpenTracks really
 *    started" signal — see PebbleListenerService class header).
 *  - ContentObserver notifications recompute pace/time/distance and push to
 *    the watch via PebbleMessenger.
 *  - onDestroy clears RunSession and closes PebbleMessenger.
 *
 * UI: minimalist "Recording — see your watch" text. The watch is the primary
 * surface during a run; this screen exists only so the activity has somewhere
 * to live (spec §5.1 prohibits foreground services, so observation rides this
 * activity's lifecycle).
 */
class DashboardActivity : ComponentActivity() {

    private var trackUri: Uri? = null
    private var trackPointsUri: Uri? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val trackObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = readTrack()
    }
    private val trackPointsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = readLatestTrackPoint()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OpenTracks ships URIs via clipData (`IntentDashboardUtils.startDashboard`):
        //   clipData[0] = Track URI       (TracksColumns.CONTENT_URI / <ids>)
        //   clipData[1] = TrackPoints URI (TrackPointsColumns.CONTENT_URI_BY_TRACKID / <ids>)
        //   clipData[2] = Markers URI     (unused)
        // intent.data is never populated — earlier code reading it always saw null.
        val clip = intent?.clipData
        trackUri       = clip?.takeIf { it.itemCount >= 1 }?.getItemAt(0)?.uri
        trackPointsUri = clip?.takeIf { it.itemCount >= 2 }?.getItemAt(1)?.uri

        Log.d(TAG, "onCreate trackUri=$trackUri trackPointsUri=$trackPointsUri")

        RunSession.active = true

        // Tell the watch the run is recording. Watch transitions from "Starting…"
        // to the active-run window on receipt. Spec §4.2.1, §7.2 key 110.
        lifecycleScope.launch { PebbleMessenger.sendRunStarted(this@DashboardActivity) }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(TextView(this@DashboardActivity).apply {
                text = getString(R.string.dashboard_recording_message)
                textSize = 20f
                gravity = Gravity.CENTER
            })
        })
    }

    override fun onResume() {
        super.onResume()
        val cr = contentResolver

        trackUri?.let {
            cr.registerContentObserver(it, /* notifyForDescendants= */ false, trackObserver)
            readTrack() // initial read
        }
        trackPointsUri?.let {
            cr.registerContentObserver(it, /* notifyForDescendants= */ true, trackPointsObserver)
            readLatestTrackPoint() // initial read
        }
    }

    override fun onPause() {
        super.onPause()
        val cr = contentResolver
        cr.unregisterContentObserver(trackObserver)
        cr.unregisterContentObserver(trackPointsObserver)
    }

    override fun onDestroy() {
        super.onDestroy()
        RunSession.active = false
        PebbleMessenger.close()
    }

    /**
     * Cached most-recent metric values. We refresh time/distance from Track
     * notifications and pace from TrackPoints notifications, then push the
     * combined snapshot. Avoids sending stale time/distance when only
     * TrackPoints fire (and vice versa).
     */
    @Volatile private var lastTimeSec: Long = 0L
    @Volatile private var lastDistHundredthsMile: Long = 0L
    @Volatile private var lastPaceSecPerMile: Int? = null

    /**
     * Read the Track row. Spec §6.2: MOVINGTIME (long ms), TOTALDISTANCE
     * (float meters). Derives watch-wire values (spec §7.2 keys 122, 123) and
     * pushes them along with the most recent pace snapshot.
     */
    private fun readTrack() {
        val uri = trackUri ?: return
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use
            val movingTimeMs = c.longOrNull(COL_MOVING_TIME)
            val totalDistanceM = c.floatOrNull(COL_TOTAL_DISTANCE)

            lastTimeSec = TrackStats.movingTimeMsToSec(movingTimeMs)
            lastDistHundredthsMile = TrackStats.meterToHundredthsMile(totalDistanceM)

            Log.d(TAG,
                "Track  moving=${movingTimeMs}ms→${lastTimeSec}s  " +
                "distance=${totalDistanceM}m→${lastDistHundredthsMile}/100mi")
        }
        pushMetrics()
    }

    /**
     * Read the latest TrackPoint row and convert its `speed` to pace.
     *
     * OpenTracks v4.27's dashboard URI exposes only `_id, trackid, latitude,
     * longitude, time, type, speed`. Until the device has moved past
     * OpenTracks's min-distance-from-previous threshold, the cursor holds a
     * single SEGMENT_START marker with `speed = null` — [TrackStats.paceFromSpeed]
     * returns null in that case and the watch renders "--:--" until a real
     * TrackPoint lands.
     */
    private fun readLatestTrackPoint() {
        val uri = trackPointsUri ?: return
        contentResolver.query(uri, null, null, null, "$COL_TIME DESC")?.use { c ->
            if (!c.moveToFirst()) return@use
            val speed = c.floatOrNull(COL_SPEED)
            val time = c.longOrNull(COL_TIME)
            lastPaceSecPerMile = TrackStats.paceFromSpeed(speed)
            Log.d(TAG,
                "TrackPoint  time=$time  speed=${speed}m/s  " +
                "pace=${lastPaceSecPerMile?.let { "${it}sec/mi" } ?: "--:--"}")
        }
        pushMetrics()
    }

    private fun pushMetrics() {
        // Capture into locals so the coroutine sees a coherent snapshot.
        val pace = lastPaceSecPerMile
        val timeSec = lastTimeSec
        val distHundredthsMile = lastDistHundredthsMile
        lifecycleScope.launch {
            PebbleMessenger.sendMetrics(
                context = this@DashboardActivity,
                paceSecPerMile = pace,
                timeSec = timeSec,
                distHundredthsMile = distHundredthsMile,
            )
        }
    }

    companion object {
        private const val TAG = "DashboardActivity"

        // Column names — ALL lowercase. SQLite is case-insensitive in unquoted
        // SQL but Android's Cursor.getColumnIndexOrThrow is case-sensitive on
        // most providers, so upper-case names threw IllegalArgumentException →
        // silently caught → null reads → all-zero metrics on the watch.
        //
        // sensor_heartrate / sensor_cadence are intentionally absent: v4.27's
        // dashboard URI projects only the basic GPS columns. Strap HR via
        // OpenTracks (spec §4.3 step 8) needs a different mechanism — see
        // docs/log.md TODO.
        private const val COL_MOVING_TIME    = "movingtime"
        private const val COL_TOTAL_DISTANCE = "totaldistance"
        private const val COL_SPEED          = "speed"
        private const val COL_TIME           = "time"
    }
}

// === Cursor extension helpers ===
// Read by column name with `getColumnIndexOrThrow`, then null-check the cell.
// Wraps `IllegalArgumentException` from missing columns so DashboardActivity
// tolerates OpenTracks schema changes (spec §6.2: "tolerate missing columns").

private fun Cursor.longOrNull(name: String): Long? = try {
    val idx = getColumnIndexOrThrow(name)
    if (isNull(idx)) null else getLong(idx)
} catch (_: IllegalArgumentException) {
    null
}

private fun Cursor.floatOrNull(name: String): Float? = try {
    val idx = getColumnIndexOrThrow(name)
    if (isNull(idx)) null else getFloat(idx)
} catch (_: IllegalArgumentException) {
    null
}
