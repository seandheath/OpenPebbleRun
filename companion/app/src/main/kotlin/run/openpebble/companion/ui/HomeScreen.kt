package run.openpebble.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 *  - Background access: ✓ Paired / ✗ Not paired — CDM association state. When
 *    not paired, an outlined button below the rows offers to launch the
 *    pairing dialog. Without this the watch cannot start a run while the
 *    companion is backgrounded (bug #14).
 *
 * No settings, no troubleshoot, no run history (use OpenTracks for history).
 */
@Composable
fun HomeScreen(
    detection: OpenTracksVariant.Detection,
    pebbleConnected: Boolean,
    paired: Boolean,
    onPairTapped: () -> Unit,
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
