package run.openpebble.companion.pebble

import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.launch
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
        PebbleMessenger.close()
        super.onDestroy()
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
        val ok = OpenTracksApi.startRecording(this, pkg)
        if (!ok) {
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
        OpenTracksApi.stopRecording(this, pkg)
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == PebbleMessenger.WATCHAPP_UUID) {
            RunSession.watchAppOpen = true
            Log.d(TAG, "watchapp opened on $watch")
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
