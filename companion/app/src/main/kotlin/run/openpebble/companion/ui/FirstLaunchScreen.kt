package run.openpebble.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import run.openpebble.companion.R

/**
 * First-launch instructions. Spec §5.2.1.
 *
 * Single screen, NOT a multi-step wizard. Shown only when Public API check
 * fails (which at this stage means "OpenTracks variant not installed" — see
 * MainActivity for why).
 */
@Composable
fun FirstLaunchScreen(
    onOpenSettings: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current

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

        Text(
            text = stringResource(R.string.first_launch_intro),
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(stringResource(R.string.first_launch_step_1), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.first_launch_step_2), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.first_launch_step_3), style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(16.dp))

        // Open IzzyOnDroid OpenTracks page (spec §5.2.1 — "IzzyOnDroid / F-Droid link").
        // IzzyOnDroid hosts the same canonical package id, so this is a single link.
        OutlinedButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW,
                    "https://f-droid.org/packages/de.dennisguse.opentracks/".toUri())
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.first_launch_install))
        }

        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.first_launch_open_settings))
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.first_launch_done))
        }
    }
}
