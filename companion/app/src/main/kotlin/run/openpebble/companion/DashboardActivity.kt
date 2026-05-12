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
import run.openpebble.companion.metrics.PaceWindow
import run.openpebble.companion.metrics.TrackStats
import run.openpebble.companion.pebble.PebbleMessenger
import run.openpebble.companion.pebble.RunSession

/**
 * OpenTracks Dashboard receiver. Spec §6.2.
 *
 * OpenTracks invokes us after StartRecording with two content URIs:
 *   - Track URI       (intent.data) — single row, columns include MOVINGTIME, TOTALDISTANCE
 *   - TrackPoints URI (intent.clipData[0]) — many rows, columns include speed, time, SENSOR_HEARTRATE
 *
 * Both arrive with FLAG_GRANT_READ_URI_PERMISSION and remain valid for the
 * lifetime of this activity (or until the grant is revoked when OpenTracks
 * stops recording).
 *
 * Lifecycle role in step 5+:
 *  - onCreate sends RUN_STARTED to the watch (the canonical "OpenTracks really
 *    started" signal — see PebbleListenerService class header for why we don't
 *    send it on CMD_START receipt).
 *  - Each ContentObserver notification recomputes pace/time/distance and pushes
 *    them to the watch via PebbleMessenger.
 *  - onDestroy clears RunSession and closes PebbleMessenger.
 *
 * UI: minimalist "Recording — see your watch" text. The watch is the primary
 * surface during a run; this screen exists only so the activity has somewhere
 * to live (Spec §5.1 prohibits foreground services, so observation rides this
 * activity's lifecycle).
 */
class DashboardActivity : ComponentActivity() {

    private var trackUri: Uri? = null
    private var trackPointsUri: Uri? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // PaceWindow is mutated only from the observer thread (main looper here).
    private val paceWindow = PaceWindow()

    private val trackObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = readTrack()
    }
    private val trackPointsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = readLatestTrackPoint()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        trackUri = intent?.data
        trackPointsUri = intent?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

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
     * Read the latest TrackPoint row, push its speed into [paceWindow], cache
     * the current pace, and send the combined metric snapshot to the watch.
     * SENSOR_HEARTRATE is logged here but the external-HR forwarding state
     * machine (spec §4.3) lands in step 8.
     */
    private fun readLatestTrackPoint() {
        val uri = trackPointsUri ?: return
        contentResolver.query(uri, null, null, null, "$COL_TIME DESC")?.use { c ->
            if (!c.moveToFirst()) return@use
            val speed = c.floatOrNull(COL_SPEED)
            val time = c.longOrNull(COL_TIME)
            val hr = c.floatOrNull(COL_SENSOR_HEARTRATE)

            if (time != null && speed != null) {
                paceWindow.push(time, speed)
            }
            val nowMs = time ?: System.currentTimeMillis()
            lastPaceSecPerMile = paceWindow.paceSecPerMile(nowMs)

            Log.d(TAG,
                "TrackPoint  time=$time  speed=${speed}m/s  hr=$hr  " +
                "pace=${lastPaceSecPerMile?.let { "${it}sec/mi" } ?: "—"}")
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

        // Column names per spec §6.2. Capitalization matches OpenTracks's schema
        // (Track table uses UPPER_SNAKE; TrackPoints uses lowercase `speed`/`time`).
        private const val COL_MOVING_TIME      = "MOVINGTIME"
        private const val COL_TOTAL_DISTANCE   = "TOTALDISTANCE"
        private const val COL_SPEED            = "speed"
        private const val COL_TIME             = "time"
        private const val COL_SENSOR_HEARTRATE = "SENSOR_HEARTRATE"
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
