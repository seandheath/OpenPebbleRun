package run.openpebble.companion.cdm

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.ContextCompat
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
 * CompanionDeviceDiscoveryService.java`) takes the "no scan required"
 * fast path that surfaces the watch even when it isn't BLE-advertising
 * only when **all** of: (a) at least one classic [BluetoothDeviceFilter]
 * is present, (b) the filter has `setAddress(...)` set, (c)
 * `setSingleDevice(true)`. The address it matches against is whatever
 * `BluetoothDevice.getAddress()` returns for entries in
 * `BluetoothAdapter.getBondedDevices()` — i.e. the **BR/EDR public
 * address** (e.g. `84:54:0A:D4:82:2B`), not the LE random static
 * address. PebbleKit's `WatchIdentifier` exposes only the LE address
 * (`C1:13:14:11:00:BD`-style, top two bits = random-static), so passing
 * that through to CDM silently misses the bonded match and the dialog
 * falls back to scanning. We instead enumerate
 * `BluetoothAdapter.getBondedDevices()` directly, find the Pebble by
 * `name.startsWith("Pebble")`, and use that record's `address` — the
 * exact string AOSP will compare against.
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
     * Look up the bonded Pebble's BR/EDR address via the platform
     * Bluetooth adapter. Returns the first bonded device whose name
     * starts with "Pebble" (case-insensitive), or null if none — meaning
     * the user hasn't completed the Pebble Android app's pairing flow
     * yet. Requires `BLUETOOTH_CONNECT` on API 31+; the permission is
     * requested at app launch (`MainActivity.maybeRequestRuntimePermissions`).
     */
    fun findBondedPebbleAddress(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "findBondedPebbleAddress: BLUETOOTH_CONNECT not granted")
            return null
        }
        val bm = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = bm.adapter ?: return null
        val bonded: Set<BluetoothDevice> = try {
            adapter.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            Log.w(TAG, "bondedDevices threw SecurityException", e)
            return null
        }
        val pebble = bonded.firstOrNull { device ->
            // BluetoothDevice.getName() requires BLUETOOTH_CONNECT on API
            // 31+; we just gated on that. Wrap in try in case a specific
            // device's name read still throws on a quirky OEM build.
            val name = try { device.name } catch (_: SecurityException) { null }
            name?.startsWith("Pebble", ignoreCase = true) == true
        }
        if (pebble == null) {
            val names = bonded.joinToString(", ") {
                runCatching { it.name }.getOrNull() ?: it.address
            }
            Log.d(TAG, "findBondedPebbleAddress: no Pebble in bonded set [$names]")
        }
        return pebble?.address
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
     */
    fun requestPairing(
        activity: ComponentActivity,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 26) {
            Log.w(TAG, "CDM not available below API 26; pairing not attempted")
            return false
        }
        val bondedAddress = findBondedPebbleAddress(activity)
        if (bondedAddress.isNullOrBlank()) {
            // Without the bonded address the fast path can't trigger and
            // the dialog falls back to active scanning, which is
            // GAP-silent for a bonded Pebble. Refuse rather than offer a
            // guaranteed-failing UX.
            Log.w(TAG, "requestPairing: Pebble not in bondedDevices — pair the watch in the Pebble app first")
            return false
        }
        val cdm = activity.getSystemService(Context.COMPANION_DEVICE_SERVICE)
            as? CompanionDeviceManager ?: run {
                Log.w(TAG, "CompanionDeviceManager unavailable on this device")
                return false
            }

        // Classic-BT filter pinned to the bonded Pebble's BR/EDR address.
        // AOSP's CompanionDeviceDiscoveryService.findMatch evaluates
        // BluetoothDeviceFilter.matches() against
        // BluetoothAdapter.getBondedDevices(); the comparison is
        // String.equals on the address, so we MUST pass the same string
        // that getBondedDevices() reports — which is the BR/EDR public
        // address, never the LE random static.
        Log.d(TAG, "requestPairing: building filter address='$bondedAddress' singleDevice=true")
        val filter = BluetoothDeviceFilter.Builder()
            .setAddress(bondedAddress)
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
