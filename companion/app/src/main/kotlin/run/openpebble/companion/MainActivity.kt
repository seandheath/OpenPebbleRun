package run.openpebble.companion

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import io.rebble.pebblekit2.client.PebbleInfoRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * Cached info retriever. PebbleKitAndroid2 binds lazily on first call.
     * Note: per the library README, [PebbleInfoRetriever] works only when the
     * app is in the foreground — fine for Home, which is foreground-only.
     */
    private val infoRetriever: PebbleInfoRetriever by lazy {
        DefaultPebbleInfoRetriever(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        detection = OpenTracksVariant.detect(this)

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
        refreshPebbleConnection()
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

        if (current.isInstalled) {
            HomeScreen(
                detection = current,
                pebbleConnected = pebbleConnected,
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

    companion object {
        private const val TAG = "MainActivity"
    }
}
