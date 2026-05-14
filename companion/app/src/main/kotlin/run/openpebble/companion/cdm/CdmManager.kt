package run.openpebble.companion.cdm

import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import java.util.concurrent.Executor

/**
 * CompanionDeviceManager (CDM) wrapper. Spec §5.1.
 *
 * An active CDM association is the Android-blessed BAL exemption for our
 * use case: "an app that responds to an action the user performed on a
 * paired companion device" (see [the BAL docs](https://developer.android.com/guide/components/activities/background-starts),
 * condition #11). Without the association, watch-initiated CMD_STOP is
 * silently BAL_BLOCKed on Android 14+ once the FGS BAL window (~10 s)
 * lapses — observed in real-hardware testing.
 *
 * Filter shape — load-bearing. AOSP's
 * `CompanionDeviceDiscoveryService.checkBoundDevicesIfNeeded()` (see
 * `packages/CompanionDeviceManager/src/com/android/companiondevicemanager/
 * CompanionDeviceDiscoveryService.java`) only consults
 * `BluetoothAdapter.getBondedDevices()` — i.e. takes the "no scan
 * required" fast path that surfaces the watch even when it isn't BLE-
 * advertising — when **all** of: (a) at least one classic
 * [BluetoothDeviceFilter] is present, (b) the filter has
 * `setAddress(...)` set, (c) `setSingleDevice(true)`. The Pebble bonded
 * record (`Pebble 822B  DUAL  cod:0-1f-0`) shows up in that list, so
 * with the fast path active the system dialog displays the watch
 * immediately. Earlier attempts that omitted any of (a)/(b)/(c)
 * — `BluetoothLeDeviceFilter`, missing address, `setSingleDevice(false)`,
 * or `setDeviceProfile(DEVICE_PROFILE_WATCH)` (which overrides the path
 * and forces a scan) — all silently fell back to scanning, which the
 * GAP-silent bonded Pebble can never satisfy.
 */
object CdmManager {

    private const val TAG = "CdmManager"

    /** True iff our package has at least one CDM association. */
    fun isPaired(context: Context): Boolean = associationCount(context) > 0

    /** Number of distinct CDM associations bound to our package. */
    private fun associationCount(context: Context): Int {
        if (Build.VERSION.SDK_INT < 26) return 0
        val cdm = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
            ?: return 0
        return try {
            if (Build.VERSION.SDK_INT >= 33) cdm.myAssociations.size
            else @Suppress("DEPRECATION") cdm.associations.size
        } catch (e: Exception) {
            Log.w(TAG, "associations query failed", e)
            0
        }
    }

    /**
     * Dispatch a CDM associate request. The system shows the bonded-device
     * fast-path dialog (no scan), the user taps Allow, and Android
     * activates `REQUEST_COMPANION_RUN_IN_BACKGROUND` +
     * `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` for
     * our UID — together they grant the BAL exemption our service needs.
     *
     * Returns true if a request was dispatched, false if the prerequisites
     * weren't met (caller can surface a hint to the user).
     *
     * @param pebbleMac the Pebble's BT MAC (e.g. "84:54:0A:D4:82:2B")
     *   from PebbleKit's [io.rebble.pebblekit2.common.model.WatchIdentifier].
     *   Required: the AOSP fast path keys off this address. Null means
     *   the Pebble Android app hasn't reported a connected watch yet —
     *   user should open the Pebble app and confirm the watch is
     *   connected, then retry.
     */
    fun requestPairing(
        activity: ComponentActivity,
        pebbleMac: String?,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 26) {
            Log.w(TAG, "CDM not available below API 26; pairing not attempted")
            return false
        }
        if (pebbleMac.isNullOrBlank()) {
            // Without the MAC the bonded fast path won't trigger and the
            // dialog falls back to active scanning, which is GAP-silent
            // for a bonded Pebble — the dialog hangs on "Looking for a
            // watch…". Refuse rather than offer a guaranteed-failing UX.
            Log.w(TAG, "requestPairing: Pebble MAC unknown — connect the Pebble Android app first")
            return false
        }
        val cdm = activity.getSystemService(Context.COMPANION_DEVICE_SERVICE)
            as? CompanionDeviceManager ?: run {
                Log.w(TAG, "CompanionDeviceManager unavailable on this device")
                return false
            }

        // Classic-BT filter pinned to the bonded Pebble's MAC. The system
        // dialog short-circuits to BluetoothAdapter.getBondedDevices() and
        // surfaces the watch instantly — no BLE advertising required.
        Log.d(TAG, "requestPairing: building filter address='$pebbleMac' singleDevice=true")
        val filter = BluetoothDeviceFilter.Builder()
            .setAddress(pebbleMac)
            .build()
        // setSingleDevice(true) is the *other* half of the fast-path
        // trigger. setDeviceProfile(...) is deliberately NOT used —
        // DEVICE_PROFILE_WATCH overrides the bonded check and forces the
        // dialog into a "scanning for watches" mode that never finds a
        // non-advertising Pebble.
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()

        if (Build.VERSION.SDK_INT >= 33) {
            // Modern callback path. Android delivers the dialog
            // IntentSender via onAssociationPending; we forward it to the
            // ActivityResultLauncher.
            val executor: Executor = Executor { it.run() }
            cdm.associate(request, executor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    Log.d(TAG, "associate: onAssociationPending — launching dialog")
                    launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                }

                override fun onAssociationCreated(associationInfo: android.companion.AssociationInfo) {
                    Log.d(TAG, "associate: onAssociationCreated id=${associationInfo.id}")
                }

                override fun onFailure(error: CharSequence?) {
                    Log.w(TAG, "associate: failed: $error")
                }
            })
        } else {
            // API 26-32: legacy callback path. onDeviceFound delivers an
            // IntentSender directly; forward the same way.
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(intentSender: IntentSender) {
                    Log.d(TAG, "associate: onDeviceFound — launching dialog")
                    launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                }

                override fun onFailure(error: CharSequence?) {
                    Log.w(TAG, "associate: failed: $error")
                }
            }, /* handler= */ null)
        }
        return true
    }
}
