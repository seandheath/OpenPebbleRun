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
 * CompanionDeviceManager (CDM) integration. Spec §5.1.
 *
 * The CDM association is the OS-blessed mechanism for letting a companion
 * app for a Bluetooth wearable launch activities and start foreground
 * services from a *background* trigger — e.g. PebbleListenerService receiving
 * `CMD_START` from the watch while the user has no companion Activity visible.
 *
 * Android (AOSP `BackgroundActivityStartController`) short-circuits its BAL
 * gate to `BAL_ALLOW_ALLOWLISTED_COMPONENT` for any UID that has an active
 * CDM association. The two manifest permissions
 * `REQUEST_COMPANION_RUN_IN_BACKGROUND` and
 * `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` are declared
 * in `AndroidManifest.xml`; declaring them is necessary, but the actual
 * grant only takes effect once the user completes the pairing dialog.
 *
 * Gadgetbridge does the same thing for Pebble (and Mi Band, etc.) — see its
 * wiki page "Companion Device pairing".
 */
object CdmManager {

    private const val TAG = "CdmManager"

    /** True iff our app has at least one CDM association. */
    fun isPaired(context: Context): Boolean = associationCount(context) > 0

    /** Number of distinct CDM associations bound to our package. */
    fun associationCount(context: Context): Int {
        if (Build.VERSION.SDK_INT < 26) return 0
        val cdm = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
            ?: return 0
        return try {
            // myAssociations (API 33+) is the modern accessor. associations (API
            // 26-32) is the deprecated alias that returns a List<String>. We use
            // the modern one when available.
            if (Build.VERSION.SDK_INT >= 33) cdm.myAssociations.size
            else @Suppress("DEPRECATION") cdm.associations.size
        } catch (e: Exception) {
            Log.w(TAG, "associations query failed", e)
            0
        }
    }

    /**
     * Dispatch a CDM associate request. The system shows a pairing dialog;
     * on user acceptance, Android creates the association and triggers our
     * permission grants. The caller passes the `ActivityResultLauncher`
     * (registered via `ActivityResultContracts.StartIntentSenderForResult()`)
     * that will receive the dialog's IntentSender.
     *
     * @param pebbleMac the Pebble's BT MAC (e.g. "C1:13:14:11:00:BD"), if known.
     *                  Pre-populates the dialog to a single device; if null,
     *                  the dialog scans for any Bluetooth device matching the
     *                  name pattern "Pebble.*".
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
                setAddress(pebbleMac)
            } else {
                setNamePattern(Pattern.compile("Pebble.*"))
            }
        }
        val requestBuilder = AssociationRequest.Builder()
            .addDeviceFilter(filterBuilder.build())
            .setSingleDevice(true)
        // DEVICE_PROFILE_WATCH (API 30+) bundles watch-appropriate permission
        // grants into a single user-visible prompt. It doesn't itself grant
        // BAL — the two REQUEST_COMPANION_* perms in the manifest do — but it
        // makes the dialog clearer about what's being granted.
        if (Build.VERSION.SDK_INT >= 30) {
            requestBuilder.setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH)
        }
        val request = requestBuilder.build()

        if (Build.VERSION.SDK_INT >= 33) {
            // Modern callback path. Android delivers the dialog IntentSender via
            // onAssociationPending; we forward it to the ActivityResultLauncher.
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
            // IntentSender directly; we forward it the same way.
            @Suppress("DEPRECATION")
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
