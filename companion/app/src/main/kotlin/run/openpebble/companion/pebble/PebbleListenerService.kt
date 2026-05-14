package run.openpebble.companion.pebble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.launch
import run.openpebble.companion.MainActivity
import run.openpebble.companion.R
import run.openpebble.companion.cdm.CdmManager
import run.openpebble.companion.metrics.TrackStats
import run.openpebble.companion.opentracks.OpenTracksApi
import run.openpebble.companion.opentracks.OpenTracksVariant
import java.util.UUID

/**
 * Receives AppMessages from the watch (via PebbleKitAndroid2's bound-service
 * mechanism) and owns the OpenTracks Dashboard poll loop.
 *
 * Polling lives here, not in `DashboardActivity`, so the user can pocket
 * the phone and let the screen lock without dropping the metric stream.
 * The service is bound by the Pebble Android app the moment the watchapp
 * opens and stays bound until it closes; URI grants from OpenTracks are
 * per-UID and remain usable as long as the DashboardActivity's task is in
 * recents (the Activity itself may be stopped).
 *
 * Watch → Companion keys handled here:
 *   2  CMD_STOP → fire OpenTracksApi.stopRecording
 *
 * `RUN_STARTED` is sent from `DashboardActivity.onCreate` (when OpenTracks
 * calls us back with the Track URIs), not from here.
 *
 * Per the library docs, **received numbers always arrive as UInt32 or
 * Int32 regardless of the wire size the watch used**.
 */
class PebbleListenerService : BasePebbleListenerService() {

    // === Poll lifecycle ===

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 5 s poll of the Dashboard URIs. OpenTracks's CustomContentProvider
     * `notifyChange` fires sparsely (25+ s gaps observed); the periodic poll
     * is the backstop so the watch's metric fields refresh on a predictable
     * cadence even with no GPS movement (movingtime ticks on its own).
     *
     * try/catch is load-bearing: if either reader throws (URI grant revoked,
     * cursor in unexpected state) we still want to re-post for the next tick.
     */
    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                if (RunSession.trackUri != null) readTrack()
                if (RunSession.trackPointsUri != null) readLatestTrackPoint()
                ensureObservers()
            } catch (e: Exception) {
                Log.w(TAG, "poll read failed", e)
            }
            mainHandler.postDelayed(this, POLL_PERIOD_MS)
        }
    }

    private val trackObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            // Fire an immediate read; the next poll tick will overwrite if
            // anything else has changed. Keeping the observer is purely an
            // optimisation for the relatively-rare cases where OpenTracks
            // notifies between poll intervals.
            readTrack()
        }
    }
    private val trackPointsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            readLatestTrackPoint()
        }
    }

    // Track which URIs the observers are currently bound to (null when none).
    // ensureObservers() registers as URIs first appear and re-registers if the
    // URIs themselves change between runs.
    private var observedTrackUri: Uri? = null
    private var observedTrackPointsUri: Uri? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PebbleListenerService onCreate — starting poll loop")
        mainHandler.postDelayed(pollRunnable, POLL_PERIOD_MS)
    }

    override fun onDestroy() {
        Log.d(TAG, "PebbleListenerService onDestroy — stopping poll loop")
        mainHandler.removeCallbacks(pollRunnable)
        unregisterObserversIfAny()
        // stopForeground is a no-op if we were never promoted — safe.
        stopForeground(STOP_FOREGROUND_REMOVE)
        PebbleMessenger.close()
        super.onDestroy()
    }

    /**
     * Handle the [ACTION_PROMOTE_FOREGROUND] kick from DashboardActivity.
     * DashboardActivity uses `startForegroundService`, so we must call
     * `startForeground` within Android's ~5 s deadline — [promoteToForeground]
     * does that. START_NOT_STICKY: the Pebble Android app re-binds us on
     * the next watchapp open, which is the right re-entry trigger.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PROMOTE_FOREGROUND) {
            Log.d(TAG, "onStartCommand: PROMOTE_FOREGROUND → promoteToForeground")
            promoteToForeground()
        }
        return START_NOT_STICKY
    }

    // === Foreground-service plumbing (spec §5.1) ===
    //
    // The service runs as a foreground service while a run is active so the
    // OS doesn't reap it mid-run. DashboardActivity kicks promotion via
    // startForegroundService(ACTION_PROMOTE_FOREGROUND); handleStop demotes
    // on CMD_STOP; onDestroy demotes defensively. The notification is
    // "ongoing" with LOW importance (silent).

    /** Lazy-create the recording channel. Safe to call on every promotion. */
    private fun ensureRecordingChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID_RECORDING) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID_RECORDING,
            getString(R.string.notification_channel_recording_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_recording_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildRecordingNotification(): Notification {
        // Tap → open MainActivity. FLAG_IMMUTABLE is required from API 31+.
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentPi = PendingIntent.getActivity(
            this, /* requestCode= */ 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_RECORDING)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(getString(R.string.notification_recording_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPi)
            .build()
    }

    private fun promoteToForeground() {
        ensureRecordingChannel()
        val notif = buildRecordingNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            // API 34+ requires the foregroundServiceType to be passed explicitly
            // when calling startForeground from within a Service whose manifest
            // declaration is foregroundServiceType="connectedDevice".
            startForeground(
                NOTIF_ID_RECORDING,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID_RECORDING, notif)
        }
        Log.d(TAG, "promoted to foreground")
    }

    private fun demoteFromForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "demoted from foreground")
    }

    /**
     * Register ContentObservers on the current RunSession URIs, idempotently.
     * Called from each poll tick: if URIs arrived since the last call we
     * subscribe; if they changed (new run) we re-subscribe; if they were
     * cleared we unregister.
     */
    private fun ensureObservers() {
        val cr = contentResolver
        val currentTrack = RunSession.trackUri
        val currentTrackPoints = RunSession.trackPointsUri

        if (currentTrack != observedTrackUri) {
            if (observedTrackUri != null) cr.unregisterContentObserver(trackObserver)
            if (currentTrack != null) {
                cr.registerContentObserver(currentTrack, /* notifyForDescendants= */ false, trackObserver)
            }
            observedTrackUri = currentTrack
        }
        if (currentTrackPoints != observedTrackPointsUri) {
            if (observedTrackPointsUri != null) cr.unregisterContentObserver(trackPointsObserver)
            if (currentTrackPoints != null) {
                cr.registerContentObserver(currentTrackPoints, /* notifyForDescendants= */ true, trackPointsObserver)
            }
            observedTrackPointsUri = currentTrackPoints
        }
    }

    private fun unregisterObserversIfAny() {
        val cr = contentResolver
        if (observedTrackUri != null) { cr.unregisterContentObserver(trackObserver); observedTrackUri = null }
        if (observedTrackPointsUri != null) { cr.unregisterContentObserver(trackPointsObserver); observedTrackPointsUri = null }
    }

    // === Per-tick metric state ===

    /**
     * Cached most-recent metric values. Refresh time/distance from Track
     * notifications and pace from TrackPoints notifications, then push the
     * combined snapshot. Avoids sending stale values when only one cursor
     * was re-read.
     */
    @Volatile private var lastTimeSec: Long = 0L
    @Volatile private var lastDistHundredthsMile: Long = 0L
    @Volatile private var lastPaceSecPerMile: Int? = null

    /**
     * Read the Track row. Spec §6.2: movingtime (long ms), totaldistance
     * (float meters). Derives watch-wire values (spec §7.2 keys 122, 123) and
     * pushes them along with the most recent pace snapshot.
     */
    private fun readTrack() {
        val uri = RunSession.trackUri ?: return
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
     * Read the freshest TrackPoint row and convert its `speed` to pace.
     *
     * OpenTracks v4.27's dashboard URI exposes `_id, trackid, latitude,
     * longitude, time, type, speed`. **`ORDER BY` is silently ignored by
     * OpenTracks's CustomContentProvider** — rows come back in insertion (=
     * ascending `time`) order regardless of the sortOrder we pass. So we
     * `moveToLast()` then scan backward past any SEGMENT marker rows
     * (`type = -2` / `-1`, speed null) to find the freshest row with a usable
     * speed.
     */
    private fun readLatestTrackPoint() {
        val uri = RunSession.trackPointsUri ?: return
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToLast()) return@use
            var speed: Float? = c.floatOrNull(COL_SPEED)
            var time: Long? = c.longOrNull(COL_TIME)
            while (speed == null && c.moveToPrevious()) {
                speed = c.floatOrNull(COL_SPEED)
                time  = c.longOrNull(COL_TIME)
            }
            lastPaceSecPerMile = TrackStats.paceFromSpeed(speed)
            Log.d(TAG,
                "TrackPoint  time=$time  speed=${speed}m/s  " +
                "pace=${lastPaceSecPerMile?.let { "${it}sec/mi" } ?: "--:--"}")
        }
        pushMetrics()
    }

    private fun pushMetrics() {
        val pace = lastPaceSecPerMile
        val timeSec = lastTimeSec
        val distHundredthsMile = lastDistHundredthsMile
        coroutineScope.launch {
            PebbleMessenger.sendMetrics(
                context = this@PebbleListenerService,
                paceSecPerMile = pace,
                timeSec = timeSec,
                distHundredthsMile = distHundredthsMile,
            )
        }
    }

    // === Inbox / CMD handling ===

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != PebbleMessenger.WATCHAPP_UUID) {
            // Not our app. The library should normally route by UUID but
            // defense-in-depth doesn't hurt.
            Log.w(TAG, "Ignoring message for wrong UUID: $watchappUUID")
            return ReceiveResult.Nack
        }

        return when {
            data.containsKey(Keys.CMD_STOP) -> handleStop()
            else -> {
                Log.d(TAG, "Unknown keys in inbox: ${data.keys}")
                ReceiveResult.Nack
            }
        }
    }

    /**
     * Resolve the OpenTracks variant package, preferring the cache populated
     * by MainActivity and falling back to a live PackageManager probe.
     */
    private fun resolveVariant(): String? =
        OpenTracksVariant.cached(this) ?: OpenTracksVariant.detect(this).pkg

    private fun handleStop(): ReceiveResult {
        val pkg = resolveVariant() ?: return ReceiveResult.Nack
        if (!CdmManager.isPaired(this)) {
            // Not fatal — the FGS BAL window may still cover a short run.
            // But on Android 14+ after ~10 s from the foreground promotion,
            // startActivity is silently BAL_BLOCKed. Loud-log so the
            // failure mode is obvious in logcat. The Home screen exposes a
            // "Pair Pebble for background access" button that fixes this.
            Log.w(TAG, "CMD_STOP but no CDM association — stop may BAL_BLOCK; open companion and pair")
        }
        Log.d(TAG, "CMD_STOP → stopRecording($pkg)")
        // With a CDM association in place, BAL is allowed via
        // BAL_ALLOW_ALLOWLISTED_COMPONENT. Without it, the service's
        // FGS state may still cover the dispatch for ≤10 s after run start.
        OpenTracksApi.stopRecording(this, pkg)
        demoteFromForeground()
        // Clear RunSession so onAppOpened doesn't replay RUN_STARTED on the
        // next watchapp open. DashboardActivity.onDestroy also clears these
        // but only when the Android task is torn down.
        RunSession.clear()
        // Acknowledge the stop to the watch. The stopping screen blocks on
        // this; without the ack the watch surfaces a timeout error. Sent
        // unconditionally — stopRecording's intent dispatch succeeding is
        // the best signal we have, OpenTracks's actual stop is async.
        coroutineScope.launch {
            PebbleMessenger.sendRunStopped(this@PebbleListenerService)
        }
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == PebbleMessenger.WATCHAPP_UUID) {
            RunSession.watchAppOpen = true
            Log.d(TAG, "watchapp opened on $watch")
            // If a run is already in progress, nudge the watch's idle screen
            // into active-run with a fresh RUN_STARTED.
            if (RunSession.active) {
                coroutineScope.launch {
                    PebbleMessenger.sendRunStarted(this@PebbleListenerService)
                }
            }
        }
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == PebbleMessenger.WATCHAPP_UUID) {
            RunSession.watchAppOpen = false
            Log.d(TAG, "watchapp closed on $watch")
        }
    }

    companion object {
        private const val TAG = "PebbleListenerService"

        /**
         * Intent action sent by DashboardActivity (via startForegroundService)
         * to promote this service to a foreground service for the duration
         * of the run. Demotion happens in `handleStop` on CMD_STOP and
         * defensively in onDestroy.
         */
        const val ACTION_PROMOTE_FOREGROUND =
            "run.openpebble.companion.action.PROMOTE_FOREGROUND"

        /**
         * Poll cadence for the OpenTracks Dashboard URIs. 5 s matches the
         * watchapp's HR sample period; both update on the same beat.
         */
        private const val POLL_PERIOD_MS = 5_000L

        // Foreground-service notification (spec §5.1).
        private const val CHANNEL_ID_RECORDING = "run.openpebble.companion.recording"
        private const val NOTIF_ID_RECORDING = 1001

        // Column names — ALL lowercase. SQLite is case-insensitive in unquoted
        // SQL but Android's Cursor.getColumnIndexOrThrow is case-sensitive on
        // most providers, so upper-case names threw IllegalArgumentException →
        // silently caught → null reads → all-zero metrics on the watch.
        //
        // sensor_heartrate / sensor_cadence are intentionally absent — the
        // dashboard URI projects only the basic GPS columns.
        private const val COL_MOVING_TIME    = "movingtime"
        private const val COL_TOTAL_DISTANCE = "totaldistance"
        private const val COL_SPEED          = "speed"
        private const val COL_TIME           = "time"
    }
}

// === Cursor extension helpers ===
// Read by column name with `getColumnIndexOrThrow`, then null-check the cell.
// Wraps `IllegalArgumentException` from missing columns so cursor reads
// tolerate OpenTracks schema changes (spec §6.2: "tolerate missing columns").

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
