package run.openpebble.companion.cdm

import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
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
 * lapses.
 *
 * Filter choice — important: the Pebble Time 2 registers as a **DUAL**
 * (BR/EDR + LE) Bluetooth device. Verified via `dumpsys bluetooth_manager`
 * on the user's test device:
 *
 *   xx:xx:xx:xx:82:2b DUAL cod:0-1f-0 ... name:"Pebble 822B"
 *
 * Gadgetbridge's `BondingUtil` chooses the filter type by the bonded
 * device's reported type — `DEVICE_TYPE_LE` and `DEVICE_TYPE_DUAL` both
 * go through `BluetoothLeDeviceFilter`, only `DEVICE_TYPE_CLASSIC` uses
 * the classic filter. We match that: the Pebble's LE half is what the
 * system's CDM scan can discover (the Pebble app holds the active LE
 * ACL connection), so we filter by MAC via [ScanFilter.setDeviceAddress].
 *
 * No `setDeviceProfile(DEVICE_PROFILE_WATCH)` here either — Gadgetbridge
 * doesn't use it for Pebble, and the watch profile bundles permissions
 * targeted at Wear OS devices that may change how the system performs
 * its discovery scan.
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
     * Dispatch a CDM associate request. The system shows a pairing dialog;
     * on user acceptance, Android creates the association and activates
     * `REQUEST_COMPANION_RUN_IN_BACKGROUND` +
     * `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` for
     * our UID, which together grant the BAL exemption our service needs.
     *
     * @param pebbleMac the Pebble's BT MAC (e.g. "C1:13:14:11:00:BD")
     *   from PebbleKit's [WatchIdentifier]. Used as the [ScanFilter]
     *   device address so the system dialog finds the bonded Pebble.
     */
    fun requestPairing(
        activity: ComponentActivity,
        pebbleMac: String?,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
    ) {
        if (Build.VERSION.SDK_INT < 26) {
            Log.w(TAG, "CDM not available below API 26; pairing not attempted")
            return
        }
        val cdm = activity.getSystemService(Context.COMPANION_DEVICE_SERVICE)
            as? CompanionDeviceManager ?: run {
                Log.w(TAG, "CompanionDeviceManager unavailable on this device")
                return
            }
        if (pebbleMac == null) {
            // Without a known MAC we can't build a useful scan filter; refuse
            // to launch a "scanning…" dialog that'll never find anything.
            Log.w(TAG, "No Pebble MAC known yet — connect the Pebble app first")
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setDeviceAddress(pebbleMac)
            .build()
        val deviceFilter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(scanFilter)
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(true)
            .build()

        Log.d(TAG, "associate: requesting pairing with mac=$pebbleMac")

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
    }
}
