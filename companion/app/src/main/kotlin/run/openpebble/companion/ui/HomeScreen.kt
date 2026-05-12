package run.openpebble.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 *  - Pebble: ✓/✗   (Phase B: hardcoded ✗ until PebbleKitAndroid2 wiring in step 5)
 *  - OpenTracks: ✓ (variant name) / ✗
 *  - Prompt: "Start runs from your watch."
 *
 * Debug section: temporary Start/Stop test-run buttons (Phase C). Removed in
 * step 5 once the watchapp owns this flow via CMD_START / CMD_STOP. See
 * docs/log.md TODO.
 */
@Composable
fun HomeScreen(
    detection: OpenTracksVariant.Detection,
    onStartTestRun: () -> Unit,
    onStopTestRun: () -> Unit,
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
            // Phase B: PebbleKitAndroid2 not yet integrated. Always ✗.
            // Step 5 will replace this with real connection state.
            ok = false,
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

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // === Debug controls (Phase C — removed in step 5) ===
        Text(
            text = stringResource(R.string.debug_section_title),
            style = MaterialTheme.typography.labelLarge,
        )
        OutlinedButton(
            onClick = onStartTestRun,
            enabled = detection.isInstalled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.debug_start_test_run))
        }
        OutlinedButton(
            onClick = onStopTestRun,
            enabled = detection.isInstalled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.debug_stop_test_run))
        }
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
