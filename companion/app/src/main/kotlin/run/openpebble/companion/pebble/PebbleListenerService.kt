package run.openpebble.companion.pebble

import android.util.Log
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.launch
import run.openpebble.companion.opentracks.OpenTracksApi
import run.openpebble.companion.opentracks.OpenTracksVariant
import java.util.UUID

/**
 * Receives AppMessages from the watch via PebbleKitAndroid2's bound-service
 * mechanism. Spec §3, §5.1, §7.1.
 *
 * Watch → Companion keys handled here:
 *   1  CMD_START → fire OpenTracksApi.startRecording
 *   2  CMD_STOP  → fire OpenTracksApi.stopRecording
 *
 * RUN_STARTED is sent from `DashboardActivity.onCreate` (when OpenTracks
 * actually invokes us back with the Track URIs) rather than from here — firing
 * RUN_STARTED on receipt of CMD_START would lie about the state if OpenTracks
 * is misconfigured or denies the intent. Watch's 15 s timeout (spec §4.2.1)
 * covers the failure path.
 *
 * Per the library docs, **received numbers always arrive as UInt32 or Int32
 * regardless of the wire size the watch used**, so we test for either to be
 * robust to future watch-side changes to the send size.
 */
class PebbleListenerService : BasePebbleListenerService() {

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

    private fun handleStart(): ReceiveResult {
        val pkg = OpenTracksVariant.cached(this) ?: run {
            Log.w(TAG, "CMD_START but no OpenTracks variant cached — open the companion app once to probe")
            // Coroutine launch fire-and-forget: tell the watch we couldn't start
            // so its "Starting…" UI doesn't sit on the 15 s timeout.
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
        val pkg = OpenTracksVariant.cached(this) ?: return ReceiveResult.Nack
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
    }
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
