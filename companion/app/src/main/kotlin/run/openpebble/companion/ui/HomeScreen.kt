package run.openpebble.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
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
 *  - Pebble: ✓/✗     (live from [io.rebble.pebblekit2.client.PebbleInfoRetriever])
 *  - OpenTracks: ✓ (variant name) / ✗
 *  - Background access: ✓ Paired / ✗ Not paired — CDM association
 *    state. When ✗, an outlined "Pair Pebble for background access"
 *    button below the rows launches the CDM pairing dialog. Without
 *    this, watch-initiated stop is BAL_BLOCKed on Android 14+ after
 *    the FGS BAL window (~10 s) lapses (spec §5.1).
 *  - Primary action: **Start Run** / **Stop Run** button.
 *
 * Runs start from this button — the watchapp has no on-watch start
 * affordance.
 */
@Composable
fun HomeScreen(
    detection: OpenTracksVariant.Detection,
    pebbleConnected: Boolean,
    paired: Boolean,
    runActive: Boolean,
    onPairTapped: () -> Unit,
    onStartTapped: () -> Unit,
    onStopTapped: () -> Unit,
) {
    Column(
        // windowInsetsPadding(safeDrawing) consumed before the 24dp content
        // padding so the Start/Stop button isn't drawn under the gesture bar
        // on Android 15+ (targetSdk=35 forces edge-to-edge).
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
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

        StatusRow(
            label = stringResource(R.string.home_background_access_label),
            ok = paired,
            detail = if (paired) stringResource(R.string.home_background_access_paired)
                     else stringResource(R.string.home_background_access_missing),
        )

        if (!paired) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onPairTapped,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.home_pair_button))
            }
        }

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
            text = if (detail != null) "$label  ($detail)" else label,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
