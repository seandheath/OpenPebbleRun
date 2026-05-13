package run.openpebble.companion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import io.rebble.pebblekit2.client.PebbleInfoRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import run.openpebble.companion.cdm.CdmManager
import run.openpebble.companion.opentracks.OpenTracksApi
import run.openpebble.companion.opentracks.OpenTracksVariant
import run.openpebble.companion.ui.FirstLaunchScreen
import run.openpebble.companion.ui.HomeScreen

/**
 * Single activity entry point. Spec §5.2: first-launch instructions iff Public
 * API check fails (degraded to "OpenTracks variant not installed" — see below);
 * steady-state Home screen otherwise.
 *
 * The "Public API check" at this stage is degraded to "OpenTracks variant is
 * installed". Actually probing whether the Public API toggle is enabled
 * requires firing StartRecording (intrusive — starts a real track), and spec
 * §11 explicitly accepts "Public API enablement is not auto-verified".
 */
class MainActivity : ComponentActivity() {

    // Compose state so the UI recomposes when onResume re-probes.
    private var detection by mutableStateOf<OpenTracksVariant.Detection>(
        OpenTracksVariant.Detection(pkg = null, label = null)
    )
    private var pebbleConnected by mutableStateOf(false)
    private var paired by mutableStateOf(false)
    /** First-known Pebble BT MAC, used to pre-populate the CDM pairing dialog. */
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
     * POST_NOTIFICATIONS request handle (API 33+). The notification is for the
     * foreground-service recording state (spec §5.1). If the user denies, the
     * service still gets foreground state and runs normally — the notification
     * just won't be visible in the shade.
     */
    private val notificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "POST_NOTIFICATIONS granted=$granted")
    }

    /**
     * Receives the CDM pairing dialog's result. On RESULT_OK the association is
     * created and Android grants `REQUEST_COMPANION_RUN_IN_BACKGROUND` +
     * `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` to our UID.
     * From that point PebbleListenerService can launch OpenTracks's publicapi
     * activities from a watch-button callback (spec §5.1, fix bug #14).
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
        paired = CdmManager.isPaired(this)
        maybeRequestPostNotifications()

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
        paired = CdmManager.isPaired(this)
        refreshPebbleConnection()
    }

    /**
     * Request POST_NOTIFICATIONS on Android 13+ so the foreground-service
     * notification (spec §5.1) shows in the shade during a run. No-op on
     * older versions where the permission doesn't exist, and no-op if
     * already granted.
     */
    private fun maybeRequestPostNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
            // PebbleKit's WatchIdentifier carries the BT MAC (12 hex chars). We
            // surface it as a CDM filter address so the pairing dialog is one-tap.
            pebbleMac = watches.firstOrNull()
                ?.toString()
                ?.let { extractMac(it) }
        }
    }

    /**
     * PebbleKit's `WatchIdentifier(value=C113141100BD)` toString contains the
     * raw MAC (no colons). CDM's `BluetoothDeviceFilter.setAddress` wants the
     * colon-separated form ("C1:13:14:11:00:BD") — convert.
     */
    private fun extractMac(watchToStr: String): String? {
        val rawMatch = Regex("[0-9A-Fa-f]{12}").find(watchToStr) ?: return null
        val raw = rawMatch.value.uppercase()
        return raw.chunked(2).joinToString(":")
    }

    @Composable
    private fun AppContent() {
        val current = detection

        if (current.isInstalled) {
            HomeScreen(
                detection = current,
                pebbleConnected = pebbleConnected,
                paired = paired,
                onPairTapped = ::requestPairing,
            )
        } else {
            FirstLaunchScreen(
                onOpenSettings = { openOpenTracksApp() },
                onDone = {
                    detection = OpenTracksVariant.detect(this)
                    paired = CdmManager.isPaired(this)
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

    /** Driven by the Home screen "Pair Pebble for background access" button. */
    private fun requestPairing() {
        CdmManager.requestPairing(this, pebbleMac, pairingLauncher)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
