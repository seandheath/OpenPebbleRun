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
import run.openpebble.companion.opentracks.OpenTracksApi
import run.openpebble.companion.opentracks.OpenTracksVariant
import run.openpebble.companion.ui.FirstLaunchScreen
import run.openpebble.companion.ui.HomeScreen

/**
 * Single activity entry point. Spec §5.2: first-launch instructions iff Public
 * API check fails (degraded to "OpenTracks variant not installed" — see below);
 * steady-state Home screen otherwise.
 *
 * No multi-step wizard. No settings. No history (use OpenTracks for history).
 *
 * The "Public API check" at this stage is degraded to "OpenTracks variant is
 * installed" — actually probing whether the Public API toggle is enabled
 * requires firing StartRecording and inspecting the result, which is intrusive
 * (it starts a real track). Spec §11 explicitly accepts that "Public API
 * enablement is not auto-verified".
 */
class MainActivity : ComponentActivity() {

    // Backed by Compose state so the UI recomposes when onResume re-probes.
    private var detection by mutableStateOf<OpenTracksVariant.Detection>(
        OpenTracksVariant.Detection(pkg = null, label = null)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initial probe before setContent so the very first composition has a
        // correct state. onResume will refresh it.
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
        // Re-probe on every resume so installing OpenTracks while the user is
        // on the first-launch screen flips them to Home immediately.
        detection = OpenTracksVariant.detect(this)
    }

    @Composable
    private fun AppContent() {
        val current = detection

        if (current.isInstalled) {
            HomeScreen(
                detection = current,
                onStartTestRun = { startTestRun() },
                onStopTestRun = { stopTestRun() },
            )
        } else {
            FirstLaunchScreen(
                onOpenSettings = { openOpenTracksApp() },
                onDone = {
                    // User reports they followed the steps — re-probe.
                    detection = OpenTracksVariant.detect(this)
                }
            )
        }
    }

    /**
     * Open the OpenTracks app (best-effort surrogate for "settings"). Spec §5.2.1
     * asks for the settings activity; OpenTracks does not expose a stable
     * Settings deeplink, so we open the launcher Intent and let the user
     * navigate. Acceptable since this is a one-time first-launch flow.
     */
    private fun openOpenTracksApp() {
        val pkg = OpenTracksVariant.cached(this) ?: detection.pkg
        if (pkg == null) {
            Log.d(TAG, "openOpenTracksApp: no variant resolved yet")
            return
        }
        OpenTracksApi.openApp(this, pkg)
    }

    private fun startTestRun() {
        val pkg = detection.pkg ?: return
        OpenTracksApi.startRecording(this, pkg)
    }

    private fun stopTestRun() {
        val pkg = detection.pkg ?: return
        OpenTracksApi.stopRecording(this, pkg)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
