package run.openpebble.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import run.openpebble.companion.R
import run.openpebble.companion.opentracks.OpenTracksVariant

/**
 * Home screen. Spec §5.2.2 — steady-state once first-launch is past.
 *
 * Shows:
 *  - Pebble: ✓/✗     (live from [io.rebble.pebblekit2.client.PebbleInfoRetriever])
 *  - OpenTracks: ✓ (variant name) / ✗
 *  - Prompt: "Start runs from your watch."
 *
 * No settings, no troubleshoot, no run history (use OpenTracks for history).
 * Debug Start/Stop test-run buttons were removed in step 5 — the watch is the
 * canonical control surface now.
 */
@Composable
fun HomeScreen(
    detection: OpenTracksVariant.Detection,
    pebbleConnected: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(16.dp))

        StatusRow(
            label = stringResource(R.string.home_pebble_label),
            ok = pebbleConnected,
            detail = null,
        )

        StatusRow(
            label = stringResource(R.string.home_opentracks_label),
            ok = detection.isInstalled,
            detail = detection.label,
        )

        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.home_prompt),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, detail: String?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (ok) stringResource(R.string.home_status_ok) else stringResource(R.string.home_status_missing),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = if (ok && detail != null) "$label  ($detail)" else label,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
