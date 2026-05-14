package run.openpebble.companion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import run.openpebble.companion.opentracks.OpenTracksApi
import run.openpebble.companion.opentracks.OpenTracksVariant
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        detection = OpenTracksVariant.detect(this)
        runActive = RunSession.active
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
        runActive = RunSession.active
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
            val connected = try {
                withContext(Dispatchers.IO) {
                    infoRetriever.getConnectedWatches().firstOrNull().orEmpty().isNotEmpty()
                }
            } catch (e: Exception) {
                Log.d(TAG, "getConnectedWatches failed (Pebble app not reachable?)", e)
                false
            }
            pebbleConnected = connected
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
                runActive = runActive,
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
            run.openpebble.companion.pebble.PebbleMessenger
                .startWatchapp(this@MainActivity)
            OpenTracksApi.startRecording(this@MainActivity, pkg)
        }
    }

    private fun onStopTapped() {
        val pkg = detection.pkg ?: return
        Log.d(TAG, "Stop Run → stopRecording($pkg)")
        OpenTracksApi.stopRecording(this, pkg)
        // Mirror PebbleListenerService.handleStop: drop RunSession state so
        // the next watchapp open doesn't trigger a spurious RUN_STARTED replay.
        RunSession.clear()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
