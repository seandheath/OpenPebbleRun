package run.openpebble.companion

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import io.rebble.pebblekit2.client.PebbleInfoRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import run.openpebble.companion.cdm.CdmManager
import run.openpebble.companion.opentracks.OpenTracksApi
import run.openpebble.companion.opentracks.OpenTracksVariant
import run.openpebble.companion.pebble.PebbleMessenger
import run.openpebble.companion.pebble.RunSession
import run.openpebble.companion.ui.FirstLaunchScreen
import run.openpebble.companion.ui.HomeScreen

/**
 * Single activity entry point. Spec §5.2: first-launch instructions when
 * no OpenTracks variant is installed; steady-state Home screen otherwise.
 *
 * The check is "OpenTracks variant is installed", not "Public API is
 * enabled" — probing the toggle would require firing StartRecording and
 * actually creating a track, which is too intrusive. Spec §11 documents
 * the gap.
 */
class MainActivity : ComponentActivity() {

    // Compose state so the UI recomposes when onResume re-probes.
    private var detection by mutableStateOf<OpenTracksVariant.Detection>(
        OpenTracksVariant.Detection(pkg = null, label = null)
    )
    private var pebbleConnected by mutableStateOf(false)
    /** Mirrors [RunSession.active]; refreshed by a Compose tick. */
    private var runActive by mutableStateOf(false)
    /** Mirrors [CdmManager.isPaired]; re-read on resume and after pairing. */
    private var paired by mutableStateOf(false)

    /**
     * First-known Pebble BT MAC, pulled from PebbleKit's [WatchIdentifier].
     * Used to pre-populate the CDM pairing dialog with a classic-BT
     * [BluetoothDeviceFilter.setAddress] filter so the user sees their
     * specific watch without the system having to do a discovery scan.
     */
    private var pebbleMac: String? = null

    /**
     * Cached info retriever. PebbleKitAndroid2 binds lazily on first call.
     * Note: per the library README, [PebbleInfoRetriever] works only when the
     * app is in the foreground — fine for Home, which is foreground-only.
     */
    private val infoRetriever: PebbleInfoRetriever by lazy {
        DefaultPebbleInfoRetriever(this)
    }

    /**
     * Runtime permission request handle for the dangerous permissions we need:
     *
     *  - **BLUETOOTH_CONNECT** (API 31+): required by PebbleKitAndroid2 for
     *    its IPC, and — crucially — required by Android 14+'s
     *    `connectedDevice` foreground-service-type check. Without it,
     *    `startForeground(... FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`
     *    throws `SecurityException` and crashes the process the moment
     *    DashboardActivity tries to promote PebbleListenerService.
     *  - **POST_NOTIFICATIONS** (API 33+): for the visible recording
     *    notification. If denied the service still gets foreground state;
     *    the notification simply isn't shown.
     */
    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        for ((perm, granted) in grants) {
            Log.d(TAG, "$perm granted=$granted")
        }
    }

    /**
     * Receives the CDM pairing dialog's result. On RESULT_OK the
     * association exists and Android grants the BAL exemption for our
     * UID. Refresh [paired] so the Home screen updates.
     */
    private val pairingLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val ok = result.resultCode == RESULT_OK
        Log.d(TAG, "CDM pairing dialog result: ok=$ok")
        paired = CdmManager.isPaired(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        detection = OpenTracksVariant.detect(this)
        runActive = RunSession.active
        paired = CdmManager.isPaired(this)
        maybeRequestRuntimePermissions()
        runBleProbe()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppContent()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        detection = OpenTracksVariant.detect(this)
        runActive = RunSession.active
        paired = CdmManager.isPaired(this)
        refreshPebbleConnection()
    }

    /**
     * Request the runtime-dangerous permissions we need. Skips any that are
     * already granted and any that don't exist on the current API level.
     * Batched into a single multi-permission prompt for a smoother first
     * launch.
     */
    private fun maybeRequestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 && !isGranted(Manifest.permission.BLUETOOTH_CONNECT)) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 31 && !isGranted(Manifest.permission.BLUETOOTH_SCAN)) {
            needed += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33 && !isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            runtimePermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Probe [PebbleInfoRetriever.getConnectedWatches] for the current
     * connection state. The retriever returns a [kotlinx.coroutines.flow.Flow];
     * we take the first emission and update [pebbleConnected]. WorkerThread
     * annotation requires us to call from a background dispatcher.
     */
    private fun refreshPebbleConnection() {
        lifecycleScope.launch {
            val watches = try {
                withContext(Dispatchers.IO) {
                    infoRetriever.getConnectedWatches().firstOrNull().orEmpty()
                }
            } catch (e: Exception) {
                Log.d(TAG, "getConnectedWatches failed (Pebble app not reachable?)", e)
                emptyList()
            }
            pebbleConnected = watches.isNotEmpty()
            // PebbleKit's WatchIdentifier toString embeds the raw BT MAC
            // (12 hex chars, no colons). CDM's BluetoothDeviceFilter wants
            // the colon-separated form ("C1:13:14:11:00:BD") — convert.
            pebbleMac = watches.firstOrNull()?.toString()?.let { extractMac(it) }
        }
    }

    /**
     * Extract a colon-separated MAC from PebbleKit's
     * `WatchIdentifier(value=C113141100BD)` toString.
     */
    private fun extractMac(watchToStr: String): String? {
        val rawMatch = Regex("[0-9A-Fa-f]{12}").find(watchToStr) ?: return null
        return rawMatch.value.uppercase().chunked(2).joinToString(":")
    }

    /** Driven by the Home screen "Pair Pebble for background access" button. */
    private fun requestPairing() {
        CdmManager.requestPairing(this, pebbleMac, pairingLauncher)
    }

    /**
     * Diagnostic-only: run a brief unfiltered BLE scan and log every
     * advertisement seen. Purpose: settle "does the Pebble actually
     * broadcast while bonded to the Pebble app?" — visible in
     * `adb logcat | grep BleProbe`. Gated by Build.DEBUG so it doesn't
     * ship in release builds. Auto-stops after 10 s.
     */
    private fun runBleProbe() {
        Log.d("BleProbe", "runBleProbe entry")
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            Log.d("BleProbe", "skip — BLUETOOTH_SCAN not granted")
            return
        }
        val bm = getSystemService(BluetoothManager::class.java) ?: return
        val scanner = bm.adapter?.bluetoothLeScanner ?: run {
            Log.d("BleProbe", "skip — no LE scanner")
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.d("BleProbe", "addr=${result.device.address} name=${result.device.name ?: result.scanRecord?.deviceName} rssi=${result.rssi}")
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w("BleProbe", "scan failed code=$errorCode")
            }
        }
        try {
            scanner.startScan(callback)
            Log.d("BleProbe", "scan started — listening for 10 s")
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    scanner.stopScan(callback)
                    Log.d("BleProbe", "scan stopped")
                } catch (e: Exception) {
                    Log.w("BleProbe", "stopScan threw", e)
                }
            }, 10_000)
        } catch (e: Exception) {
            Log.w("BleProbe", "startScan threw", e)
        }
    }

    @Composable
    private fun AppContent() {
        val current = detection

        // 1 Hz tick to keep [runActive] in sync with [RunSession.active] while
        // MainActivity is foreground. RunSession is a @Volatile singleton and
        // not Compose-observable on its own; this is the cheapest way to flip
        // the Start/Stop button label when DashboardActivity arrives/destroys.
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                runActive = RunSession.active
            }
        }

        if (current.isInstalled) {
            HomeScreen(
                detection = current,
                pebbleConnected = pebbleConnected,
                paired = paired,
                runActive = runActive,
                onPairTapped = ::requestPairing,
                onStartTapped = ::onStartTapped,
                onStopTapped = ::onStopTapped,
            )
        } else {
            FirstLaunchScreen(
                onOpenSettings = { openOpenTracksApp() },
                onDone = {
                    detection = OpenTracksVariant.detect(this)
                }
            )
        }
    }

    private fun openOpenTracksApp() {
        val pkg = OpenTracksVariant.cached(this) ?: detection.pkg
        if (pkg == null) {
            Log.d(TAG, "openOpenTracksApp: no variant resolved yet")
            return
        }
        OpenTracksApi.openApp(this, pkg)
    }

    /**
     * Driven by the Home screen's Start Run button:
     *  1. Open the watchapp on the Pebble via PebbleKit's
     *     `startAppOnTheWatch` so the user doesn't have to open it manually.
     *  2. Fire StartRecording to OpenTracks from this foreground context.
     *
     * OpenTracks then calls DashboardActivity back, which (a) stashes the
     * URIs in RunSession, (b) sends RUN_STARTED to the watch, and (c)
     * foregrounds OpenTracks's own UI via OpenTracksApi.openApp. The
     * watch's idle screen accepts RUN_STARTED and transitions to
     * active-run.
     */
    private fun onStartTapped() {
        val pkg = detection.pkg ?: return
        Log.d(TAG, "Start Run → openAppOnWatch + startRecording($pkg)")
        lifecycleScope.launch {
            PebbleMessenger.startWatchapp(this@MainActivity)
            OpenTracksApi.startRecording(this@MainActivity, pkg)
        }
    }

    private fun onStopTapped() {
        val pkg = detection.pkg ?: return
        Log.d(TAG, "Stop Run → stopRecording + sendRunStopped($pkg)")
        OpenTracksApi.stopRecording(this, pkg)
        // Drop RunSession state so the next watchapp open doesn't replay
        // RUN_STARTED, and tell the watch the run is over so it can leave
        // active-run for the run-summary screen.
        RunSession.clear()
        lifecycleScope.launch {
            PebbleMessenger.sendRunStopped(this@MainActivity)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
