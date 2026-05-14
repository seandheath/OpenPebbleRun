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
 * lapses.
 *
 * Filter choice — Phase 1 final attempt: the Pebble Time 2 is a **DUAL**
 * (BR/EDR + LE) bonded device that doesn't advertise while the Pebble
 * Android app holds its GATT connection. Every previous variant
 * (classic+setAddress, LE+setDeviceAddress, with/without
 * DEVICE_PROFILE_WATCH) saw the dialog scan return 0 matches.
 *
 * This pass uses the most permissive classic-BT filter we can build:
 * empty `BluetoothDeviceFilter` + `setSingleDevice(false)`. The dialog
 * shows the system's full classic-BT picker. If Android includes the
 * bonded Pebble in that picker, the user can tap it manually. If it
 * doesn't, see plan Phase 2 (revert and document the limitation).
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
     * Dispatch a CDM associate request. The system shows a pairing dialog
     * listing all classic-BT devices it knows about (bonded + discovered);
     * the user taps their Pebble. On acceptance, Android creates the
     * association and activates `REQUEST_COMPANION_RUN_IN_BACKGROUND` +
     * `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` for
     * our UID, which together grant the BAL exemption our service needs.
     *
     * `pebbleMac` is unused in Phase 1's permissive filter (we no longer
     * pin the dialog to a specific address). Kept in the signature so
     * MainActivity's call site doesn't churn if we re-introduce
     * MAC-pinning later.
     */
    @Suppress("UNUSED_PARAMETER")
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

        // Empty classic-BT filter: match all classic BT devices. The system
        // dialog renders a picker over the user's bonded + discovered set.
        val deviceFilter = BluetoothDeviceFilter.Builder().build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false)
            .build()

        Log.d(TAG, "associate: requesting classic-BT picker (Phase 1 permissive filter)")

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
