package run.openpebble.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
 *  - Primary action: **Start Run** / **Stop Run** button. Toggles by
 *    [runActive] (which mirrors `RunSession.active`). Disabled when OpenTracks
 *    isn't installed.
 *
 * Spec §5.1 / §11: runs are started from this button. The watchapp itself
 * has no on-watch start affordance — pre-run was removed in v0.1 and the
 * watch-side CMD_START key was retired in the dead-code sweep (see
 * docs/log.md 2026-05-14). Three earlier workarounds for watch-initiated
 * start while the companion was backgrounded (PendingIntent, in-service
 * `startForeground`, CompanionDeviceManager) all failed on Android 14+; the
 * v0.1 design accepts the phone-only-start constraint.
 */
@Composable
fun HomeScreen(
    detection: OpenTracksVariant.Detection,
    pebbleConnected: Boolean,
    runActive: Boolean,
    onStartTapped: () -> Unit,
    onStopTapped: () -> Unit,
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

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = if (runActive) onStopTapped else onStartTapped,
            enabled = detection.isInstalled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (runActive) R.string.home_stop_button
                    else R.string.home_start_button
                )
            )
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
