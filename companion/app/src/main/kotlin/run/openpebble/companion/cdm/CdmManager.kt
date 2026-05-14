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
import java.util.regex.Pattern

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
 * Filter choice — important: the Pebble is a classic-Bluetooth (BR/EDR)
 * device, paired through the Pebble Android app, and does **not**
 * BLE-advertise while bonded. The earlier deleted attempt at CDM in this
 * project used `BluetoothLeDeviceFilter` and the dialog never found the
 * Pebble for that reason. Gadgetbridge's BondingUtil for Pebble uses
 * [BluetoothDeviceFilter] (classic) with `setAddress(macAddress)`
 * pre-populated from the already-bonded device — that's what we mirror
 * here.
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
     * @param pebbleMac the Pebble's BT MAC (e.g. "C1:13:14:11:00:BD") if
     *   already known via PebbleKit. Pre-populates the dialog so the user
     *   sees their specific watch (no BLE-advertising scan required). If
     *   null, falls back to a name-pattern filter ("Pebble.*").
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

        val filterBuilder = BluetoothDeviceFilter.Builder().apply {
            if (pebbleMac != null) {
                // Classic-BT filter pinned to the bonded Pebble's MAC. The
                // system dialog shows the device without performing a
                // discovery scan, so it doesn't matter that the Pebble
                // isn't BLE-advertising while bonded to the Pebble app.
                setAddress(pebbleMac)
            } else {
                // No MAC known yet (user hasn't connected the Pebble app
                // since install). Show any device with a Pebble-ish name.
                setNamePattern(Pattern.compile("Pebble.*"))
            }
        }
        val requestBuilder = AssociationRequest.Builder()
            .addDeviceFilter(filterBuilder.build())
            .setSingleDevice(true)
        if (Build.VERSION.SDK_INT >= 30) {
            // DEVICE_PROFILE_WATCH (API 30+) bundles watch-appropriate
            // permission grants into a single user-visible dialog and
            // makes the prompt's intent clearer.
            requestBuilder.setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH)
        }
        val request = requestBuilder.build()

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
