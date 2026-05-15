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
import android.util.Log
import androidx.core.app.NotificationCompat
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 * opens and stays bound until it closes.
 *
 * URI grant lifetime: OpenTracks grants `FLAG_GRANT_READ_URI_PERMISSION` to
 * our UID when it launches `DashboardActivity` with the dashboard URIs.
 * `DashboardActivity` then re-delegates the grant to this service by attaching
 * the URIs as `ClipData` on the `ACTION_PROMOTE_FOREGROUND` start intent (with
 * the grant flag set). A foreground service is a valid grant target, so the
 * service holds the grant for as long as it stays alive — independent of
 * whether the user swipes the Activity's task from recents.
 *
 * Watch → Companion keys handled here:
 *   1  CMD_START → fire OpenTracksApi.startRecording
 *   2  CMD_STOP  → fire OpenTracksApi.stopRecording
 *
 * `RUN_STARTED` is sent from `DashboardActivity.onCreate` (when OpenTracks
 * calls us back with the Track URIs), not from here — including for the
 * watch-initiated start path. The watch's `starting` screen waits up to
 * 15 s for that delivery; logical race-free because OpenTracks's
 * Dashboard callback path takes sub-second once StartRecording dispatches.
 *
 * Per the library docs, **received numbers always arrive as UInt32 or
 * Int32 regardless of the wire size the watch used**.
 */
class PebbleListenerService : BasePebbleListenerService() {

    // === Poll lifecycle ===

    /**
     * 5 s poll of the Dashboard URIs, driven by a coroutine on
     * [Dispatchers.IO]. OpenTracks's CustomContentProvider `notifyChange`
     * fires sparsely (25+ s gaps observed); the periodic poll is the
     * backstop so the watch's metric fields refresh on a predictable cadence
     * even with no GPS movement (movingtime ticks on its own).
     *
     * Off the main looper deliberately — `contentResolver.query` is a
     * cross-process call to OpenTracks's provider, and PebbleKit's
     * bound-service callbacks (`onMessageReceived`, `onAppOpened`,
     * `onAppClosed`) also dispatch on the main looper; a slow query would
     * queue them.
     *
     * try/catch is load-bearing: if either reader throws (URI grant revoked,
     * cursor in unexpected state) we still want to loop for the next tick.
     */
    private val trackObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            // Fire an immediate read; the next poll tick will overwrite if
            // anything else has changed. Keeping the observer is purely an
            // optimisation for the relatively-rare cases where OpenTracks
            // notifies between poll intervals. ContentObserver(null)
            // delivers onChange on whichever thread the provider notifies on
            // — fine because readTrack only does cursor I/O + @Volatile
            // writes + a coroutineScope.launch.
            readTrack()
        }
    }
    private val trackPointsObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            readLatestTrackPoint()
        }
    }

    // Track which URIs the observers are currently bound to (null when none).
    // ensureObservers() registers as URIs first appear and re-registers if the
    // URIs themselves change between runs.
    private var observedTrackUri: Uri? = null
    private var observedTrackPointsUri: Uri? = null

    // Authoritative URI holders for the run. Populated from the ACTION_PROMOTE_FOREGROUND
    // intent's ClipData (DashboardActivity re-delegates the OpenTracks grant to this
    // service component). Cleared in handleStop / onDestroy so the poll loop stops
    // querying after the run ends. @Volatile because the poll-loop coroutine reads
    // them on Dispatchers.IO while the main looper writes them in onStartCommand.
    @Volatile private var trackUri: Uri? = null
    @Volatile private var trackPointsUri: Uri? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PebbleListenerService onCreate — starting poll loop")
        // The coroutine cancels when coroutineScope (owned by
        // BasePebbleListenerService) is cancelled on service destroy.
        coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_PERIOD_MS)
                try {
                    if (trackUri != null) readTrack()
                    if (trackPointsUri != null) readLatestTrackPoint()
                } catch (e: Exception) {
                    Log.w(TAG, "poll read failed", e)
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "PebbleListenerService onDestroy — stopping poll loop")
        unregisterObserversIfAny()
        // stopForeground is a no-op if we were never promoted — safe.
        stopForeground(STOP_FOREGROUND_REMOVE)
        PebbleMessenger.close()
        super.onDestroy()
    }

    /**
     * Handle the [ACTION_PROMOTE_FOREGROUND] kick from DashboardActivity.
     *
     * The intent carries the validated dashboard URIs as `ClipData[0]` (Track)
     * and `[1]` (TrackPoints), with `FLAG_GRANT_READ_URI_PERMISSION` set so the
     * OpenTracks grant is re-delegated to this service component. Stash the
     * URIs, register observers once (idempotent — `ensureObservers` also
     * handles a fresh promotion mid-process if a second run starts), then
     * promote.
     *
     * DashboardActivity uses `startForegroundService`, so we must call
     * `startForeground` within Android's ~5 s deadline — [promoteToForeground]
     * does that, and we call it unconditionally even on a malformed intent
     * (the OS would crash us otherwise).
     *
     * START_NOT_STICKY: the Pebble Android app re-binds us on the next
     * watchapp open, which is the right re-entry trigger. A killed-then-
     * redelivered promote intent would have lost its URI grants anyway.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PROMOTE_FOREGROUND) {
            val clip = intent.clipData
            val newTrack       = clip?.takeIf { it.itemCount >= 1 }?.getItemAt(0)?.uri
            val newTrackPoints = clip?.takeIf { it.itemCount >= 2 }?.getItemAt(1)?.uri
            val grantFlagSet =
                (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
            if (newTrack != null && newTrackPoints != null && grantFlagSet) {
                Log.d(TAG, "onStartCommand: PROMOTE_FOREGROUND → stashing URIs " +
                    "track=$newTrack trackPoints=$newTrackPoints")
                trackUri = newTrack
                trackPointsUri = newTrackPoints
                ensureObservers()
            } else {
                Log.w(TAG, "onStartCommand: PROMOTE_FOREGROUND with malformed " +
                    "ClipData (track=$newTrack trackPoints=$newTrackPoints " +
                    "grant=$grantFlagSet) — promoting anyway, poll loop will idle")
            }
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
     * Register ContentObservers on the current per-instance URIs, idempotently.
     * Called once from onStartCommand on PROMOTE_FOREGROUND. Logic still
     * tolerates a mid-process second promotion (different URIs) by
     * unregister-then-register; clearing (handleStop) goes through
     * unregisterObserversIfAny() directly rather than ensureObservers.
     */
    private fun ensureObservers() {
        val cr = contentResolver
        val currentTrack = trackUri
        val currentTrackPoints = trackPointsUri

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
        val uri = trackPointsUri ?: return
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
     * Resolve the OpenTracks variant package, preferring the cache populated
     * by MainActivity and falling back to a live PackageManager probe.
     */
    private fun resolveVariant(): String? =
        OpenTracksVariant.cached(this) ?: OpenTracksVariant.detect(this).pkg

    /**
     * Watch-initiated start. Mirrors [handleStop]: dispatch the OpenTracks
     * publicapi Intent from this (non-foreground) service and let the
     * normal Dashboard-callback path send `RUN_STARTED` back to the watch.
     *
     * BAL: we are explicitly **not** in a foreground-service state at this
     * point — no run is active yet, so the FGS BAL window doesn't cover
     * us. The only path that lets `startActivity` through on Android 14+
     * is the CDM-association exemption (BAL docs condition #11). If the
     * user hasn't completed the CDM pairing flow yet, NACK loudly so the
     * watch surfaces its timeout-then-retry error and the user is nudged
     * to open the companion's Home screen and tap "Pair Pebble for
     * background access".
     *
     * We do **not** send `RUN_STARTED` from here. `DashboardActivity.onCreate`
     * is the canonical sender — it fires once OpenTracks has called us
     * back with the Track URIs, which is also the moment we promote the
     * service to foreground. Sending here would race the URI stash and
     * the watch's active-run screen would come up before the metric pipe
     * was actually ready.
     */
    private fun handleStart(): ReceiveResult {
        val pkg = resolveVariant() ?: run {
            Log.w(TAG, "CMD_START but no OpenTracks variant installed — NACK")
            return ReceiveResult.Nack
        }
        if (!CdmManager.isPaired(this)) {
            Log.w(TAG, "CMD_START but no CDM association — start would BAL_BLOCK; " +
                       "open companion and tap 'Pair Pebble for background access'")
            return ReceiveResult.Nack
        }
        Log.d(TAG, "CMD_START → startRecording($pkg)")
        val dispatched = OpenTracksApi.startRecording(this, pkg)
        return if (dispatched) ReceiveResult.Ack else ReceiveResult.Nack
    }

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
        // Stop the poll loop's reads and tear down observers. Demoting the
        // FGS also drops our hold on the URI grant; nulling the per-instance
        // refs ensures the poll loop short-circuits on the next tick.
        trackUri = null
        trackPointsUri = null
        unregisterObserversIfAny()
        // Clear RunSession.active so onAppOpened doesn't replay RUN_STARTED on
        // the next watchapp open. DashboardActivity.onDestroy also clears this
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
