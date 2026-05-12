package run.openpebble.companion

import android.app.Activity
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import android.widget.LinearLayout
import android.view.Gravity
import run.openpebble.companion.metrics.PaceWindow
import run.openpebble.companion.metrics.TrackStats

/**
 * OpenTracks Dashboard receiver. Spec §6.2.
 *
 * OpenTracks invokes us after StartRecording with two content URIs:
 *   - Track URI    (intent.data) — single row, columns include MOVINGTIME, TOTALDISTANCE
 *   - TrackPoints URI (intent.clipData[0]) — many rows, columns include speed, time, SENSOR_HEARTRATE
 *
 * Both arrive with FLAG_GRANT_READ_URI_PERMISSION and remain valid for the
 * lifetime of this activity (or until the grant is revoked when OpenTracks
 * stops recording).
 *
 * Phase C scope: register a ContentObserver on each URI; on each notification
 * dump the relevant columns to logcat. Spec §6.2 mandates column-name reads via
 * [Cursor.getColumnIndexOrThrow] (schema evolved in OpenTracks v4.26.0 — tolerate
 * missing columns, especially SENSOR_HEARTRATE which depends on a paired strap).
 *
 * Phase D will wrap the cursor reads with metric computation; step 5 forwards
 * the metrics to the watch via PebbleKitAndroid2.
 *
 * UI: minimalist "Recording — see your watch" text. The watch is the primary
 * surface during a run.
 */
class DashboardActivity : Activity() {

    private var trackUri: Uri? = null
    private var trackPointsUri: Uri? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Phase D: rolling-window pace + pure time/distance conversions.
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

        // Minimal UI: a single TextView so the activity isn't blank if the user
        // foregrounds it. Real polish lands in spec §14 step 10.
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(TextView(this@DashboardActivity).apply {
                text = "Recording…\n\nLook at your watch for stats."
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

    /**
     * Read the Track row (one row per recording). Spec §6.2: MOVINGTIME (long ms),
     * TOTALDISTANCE (float meters). Derives the watch-wire values (spec §5.3
     * keys 122, 123). Logged for Phase D; transmission lands in step 5.
     */
    private fun readTrack() {
        val uri = trackUri ?: return
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use
            val movingTimeMs = c.longOrNull(COL_MOVING_TIME)
            val totalDistanceM = c.floatOrNull(COL_TOTAL_DISTANCE)

            val timeSec = TrackStats.movingTimeMsToSec(movingTimeMs)
            val distHundredthsMile = TrackStats.meterToHundredthsMile(totalDistanceM)

            Log.d(TAG,
                "Track  moving=${movingTimeMs}ms→${timeSec}s  " +
                "distance=${totalDistanceM}m→${distHundredthsMile}/100mi")
        }
    }

    /**
     * Read the latest TrackPoint row, push its speed into [paceWindow], and
     * log the current pace (spec §5.3 key 120: 15s rolling mean → sec/mi).
     * SENSOR_HEARTRATE is read here but only logged — the external-HR forwarding
     * state machine (spec §4.3) lands in step 8.
     */
    private fun readLatestTrackPoint() {
        val uri = trackPointsUri ?: return
        contentResolver.query(uri, null, null, null, "$COL_TIME DESC")?.use { c ->
            if (!c.moveToFirst()) return@use
            val speed = c.floatOrNull(COL_SPEED)
            val time = c.longOrNull(COL_TIME)
            val hr = c.floatOrNull(COL_SENSOR_HEARTRATE) // tolerate missing column / null value

            if (time != null && speed != null) {
                paceWindow.push(time, speed)
            }
            val nowMs = time ?: System.currentTimeMillis()
            val paceSecPerMile = paceWindow.paceSecPerMile(nowMs)

            Log.d(TAG,
                "TrackPoint  time=$time  speed=${speed}m/s  hr=$hr  " +
                "pace=${paceSecPerMile?.let { "${it}sec/mi" } ?: "—"}")
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
