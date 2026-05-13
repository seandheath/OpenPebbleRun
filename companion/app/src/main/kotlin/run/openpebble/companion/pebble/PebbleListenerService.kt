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
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.launch
import run.openpebble.companion.MainActivity
import run.openpebble.companion.R
import run.openpebble.companion.metrics.TrackStats
import run.openpebble.companion.opentracks.OpenTracksApi
import run.openpebble.companion.opentracks.OpenTracksVariant
import java.util.UUID

/**
 * Receives AppMessages from the watch via PebbleKitAndroid2's bound-service
 * mechanism, AND owns the OpenTracks Dashboard poll loop. Spec §3, §5.1, §7.1.
 *
 * **Why polling lives here, not in `DashboardActivity`:** v0.1 requires that
 * the companion never need to be in the foreground — the user pockets the
 * phone and watches their wrist. An Activity-scoped poll dies on `onPause`
 * (screen lock, app switch). This service is bound by the Pebble Android app
 * the moment the watchapp opens and stays bound until it closes, so its
 * polling cadence is decoupled from screen / activity state. URI grants from
 * OpenTracks are per-UID; as long as the DashboardActivity's task is in
 * recents (the Activity itself may be stopped) the URIs remain usable.
 *
 * Watch → Companion keys handled here:
 *   1  CMD_START → fire OpenTracksApi.startRecording
 *   2  CMD_STOP  → fire OpenTracksApi.stopRecording (still BAL-blocks; bug #14)
 *
 * RUN_STARTED is sent from `DashboardActivity.onCreate` (when OpenTracks
 * actually invokes us back with the Track URIs) rather than from here — firing
 * RUN_STARTED on receipt of CMD_START would lie about state if OpenTracks
 * is misconfigured or denies the intent. Watch's 15 s timeout (spec §4.2.1)
 * covers the failure path.
 *
 * Per the library docs, **received numbers always arrive as UInt32 or Int32
 * regardless of the wire size the watch used**, so we test for either to be
 * robust to future watch-side changes to the send size.
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
        // Defensive demote in case we're torn down mid-run. stopForeground is a
        // no-op if we were never in foreground state — safe to call unconditionally.
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
        PebbleMessenger.close()
        super.onDestroy()
    }

    // === Foreground-service plumbing (spec §5.1) ===
    //
    // Promoted to foreground between CMD_START and CMD_STOP so Android 12+
    // Background Activity Launch policy lets us dispatch OpenTracks's
    // publicapi.Start/StopRecording activities (bug #14). Pattern matches
    // OpenTracks's own TrackRecordingService. Notification is "ongoing" with
    // LOW importance (silent).

    /** Lazy create the recording channel. Safe to call on every promotion. */
    private fun ensureRecordingChannel() {
        if (Build.VERSION.SDK_INT < 26) return
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
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
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
            data.containsKey(Keys.CMD_START) -> handleStart()
            data.containsKey(Keys.CMD_STOP)  -> handleStop()
            else -> {
                Log.d(TAG, "Unknown keys in inbox: ${data.keys}")
                ReceiveResult.Nack
            }
        }
    }

    /**
     * Resolve the OpenTracks variant package. Tries the SharedPreferences cache
     * first (populated by MainActivity), then falls back to a live PackageManager
     * probe. The probe is cheap (four `getPackageInfo` lookups) and also refreshes
     * the cache as a side-effect — so a watch-initiated CMD_START works even
     * when the user has never opened the companion app, which is the canonical
     * UX (the watch is the control surface).
     */
    private fun resolveVariant(): String? =
        OpenTracksVariant.cached(this) ?: OpenTracksVariant.detect(this).pkg

    private fun handleStart(): ReceiveResult {
        val pkg = resolveVariant() ?: run {
            Log.w(TAG, "CMD_START but no OpenTracks variant installed")
            // Fire-and-forget RUN_FAILED so the watch doesn't sit on the 15 s
            // "Starting…" timeout when we know it'll never succeed.
            coroutineScope.launch { PebbleMessenger.sendRunFailed(this@PebbleListenerService) }
            return ReceiveResult.Nack
        }
        Log.d(TAG, "CMD_START → startRecording($pkg)")
        // Promote *before* dispatching: foreground state grants the BAL
        // allowance Android 12+ requires for the subsequent startActivity.
        // Bug #14 fix; see class header.
        promoteToForeground()
        val ok = OpenTracksApi.startRecording(this, pkg)
        if (!ok) {
            demoteFromForeground()
            coroutineScope.launch { PebbleMessenger.sendRunFailed(this@PebbleListenerService) }
            return ReceiveResult.Nack
        }
        // RUN_STARTED is sent from DashboardActivity.onCreate when OpenTracks
        // actually calls back. See class header for rationale.
        return ReceiveResult.Ack
    }

    private fun handleStop(): ReceiveResult {
        val pkg = resolveVariant() ?: return ReceiveResult.Nack
        Log.d(TAG, "CMD_STOP → stopRecording($pkg)")
        // Stay foreground for the dispatch (BAL still required), then demote.
        OpenTracksApi.stopRecording(this, pkg)
        demoteFromForeground()
        // Clear RunSession so onAppOpened doesn't falsely replay RUN_STARTED on
        // the next watchapp open. DashboardActivity.onDestroy also clears
        // these but only fires when the Android task is torn down — typically
        // long after the run has actually stopped.
        RunSession.clear()
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == PebbleMessenger.WATCHAPP_UUID) {
            RunSession.watchAppOpen = true
            Log.d(TAG, "watchapp opened on $watch")
            // If a run is already in progress (user tapped Start on the
            // companion before opening the watchapp), nudge the watch into
            // active-run with a fresh RUN_STARTED. pre_run's inbox handler
            // honors RUN_STARTED in IDLE state as well as STARTING for this
            // exact handoff.
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

/** Convenience: unify reads for the int-key types the library promotes to. */
@Suppress("unused")
private val PebbleDictionaryItem.asUInt: UInt?
    get() = when (this) {
        is PebbleDictionaryItem.UInt32 -> value
        is PebbleDictionaryItem.UInt16 -> value.toUInt()
        is PebbleDictionaryItem.UInt8  -> value.toUInt()
        is PebbleDictionaryItem.Int32  -> value.toUInt()
        else -> null
    }
