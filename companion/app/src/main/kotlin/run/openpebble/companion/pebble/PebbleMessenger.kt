package run.openpebble.companion.pebble

import android.content.Context
import android.util.Log
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import java.util.UUID

/**
 * Process-scoped wrapper around [DefaultPebbleSender]. Owns the sender's
 * lifecycle (lazy init, explicit close on run stop) so callers can fire-and-
 * forget metric updates without per-message setup overhead.
 *
 * [DefaultPebbleSender] internally manages a bound-service connection;
 * constructing/closing it per Dashboard observer callback would churn the
 * binder. The Dashboard observer fires once per OpenTracks TrackPoint —
 * potentially a few times per second. The singleton keeps one connection.
 *
 * Threading: all sender methods are suspend; call from a coroutine. Callers
 * launch on their own scope (e.g. `Activity.lifecycleScope`).
 */
object PebbleMessenger {

    private const val TAG = "PebbleMessenger"

    /** Watchapp UUID. Must match `watchapp/package.json`. */
    val WATCHAPP_UUID: UUID = UUID.fromString("e8e7473e-eeb9-48d3-a57c-fc2af318db5d")

    @Volatile private var sender: PebbleSender? = null

    /**
     * Lazily create (or return) the cached [PebbleSender]. Uses the application
     * context to outlive any single Activity / Service callsite.
     */
    private fun getOrCreate(context: Context): PebbleSender {
        // Double-checked locking — sender is @Volatile.
        sender?.let { return it }
        synchronized(this) {
            sender?.let { return it }
            val newSender = DefaultPebbleSender(context.applicationContext)
            sender = newSender
            return newSender
        }
    }

    /** Idempotent close. Safe to call multiple times. */
    fun close() {
        synchronized(this) {
            sender?.close()
            sender = null
        }
    }

    // === Send helpers ===

    /** Tell the watch a run is recording (transitions idle → active-run). */
    suspend fun sendRunStarted(context: Context) {
        send(context, mapOf(Keys.RUN_STARTED to PebbleDictionaryItem.UInt8(1)))
    }

    /**
     * Launch our watchapp on the connected Pebble (PebbleKit
     * `startAppOnTheWatch`). Used by the Start Run button so the user
     * doesn't have to open the watchapp manually. No-op if already open.
     */
    suspend fun startWatchapp(context: Context) {
        val s = getOrCreate(context)
        val result: Map<*, TransmissionResult>? = try {
            s.startAppOnTheWatch(WATCHAPP_UUID)
        } catch (e: Exception) {
            Log.w(TAG, "startAppOnTheWatch failed", e)
            return
        }
        if (result == null) {
            Log.d(TAG, "startAppOnTheWatch: Pebble app not reachable")
            return
        }
        for ((watch, tr) in result) {
            if (tr !is TransmissionResult.Success) {
                Log.d(TAG, "startAppOnTheWatch to $watch: $tr")
            }
        }
    }

    /**
     * Push live metrics to the watch. Pace is omitted when null (treated as
     * "stopped" — watch renders "--:--").
     *
     * - paceSecPerMile: capped at 3600 by caller (TrackStats.paceFromSpeed).
     * - timeSec:        Track.MOVINGTIME / 1000.
     * - distHundredthsMile: meters → hundredths-of-a-mile (TrackStats).
     */
    suspend fun sendMetrics(
        context: Context,
        paceSecPerMile: Int?,
        timeSec: Long,
        distHundredthsMile: Long,
    ) {
        val dict = buildMap<UInt, PebbleDictionaryItem> {
            paceSecPerMile?.let {
                put(Keys.PACE_CURRENT, PebbleDictionaryItem.UInt16(it.coerceIn(0, 3600)))
            }
            // Skip TIME=0: OpenTracks's `movingtime` is 0 while it has no GPS
            // fix yet, but the watch is already running its own 1 Hz local
            // counter — a TIME=0 push here would snap the watch back to 0:00
            // every 5 s. Only forward a value once OpenTracks has real elapsed
            // time; the watch advances on its own meanwhile.
            if (timeSec > 0) {
                put(Keys.TIME, PebbleDictionaryItem.UInt32(timeSec))
            }
            put(Keys.DISTANCE, PebbleDictionaryItem.UInt32(distHundredthsMile.coerceAtLeast(0L)))
        }
        send(context, dict)
    }

    private suspend fun send(context: Context, dict: PebbleDictionary) {
        val s = getOrCreate(context)
        val result: Map<*, TransmissionResult>? = try {
            s.sendDataToPebble(WATCHAPP_UUID, dict)
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
            return
        }
        if (result == null) {
            // Pebble companion app not reachable (uninstalled, or not the
            // currently-selected app per PebbleAndroidAppPicker). Home
            // already reflects the connection state.
            Log.d(TAG, "Pebble app not reachable; dropping ${dict.keys}")
            return
        }
        // Non-success results aren't user-actionable mid-run — the watch
        // dims its own metrics on staleness (spec §8.1). Log only.
        for ((watch, tr) in result) {
            if (tr !is TransmissionResult.Success) {
                Log.d(TAG, "send to $watch: $tr")
            }
        }
    }
}
