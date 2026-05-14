package run.openpebble.companion

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import run.openpebble.companion.opentracks.OpenTracksApi
import run.openpebble.companion.opentracks.OpenTracksVariant
import run.openpebble.companion.pebble.PebbleListenerService
import run.openpebble.companion.pebble.PebbleMessenger
import run.openpebble.companion.pebble.RunSession

/**
 * OpenTracks Dashboard receiver. Spec §6.2.
 *
 * Receives the dashboard URIs from OpenTracks's callback intent, stashes
 * them in [RunSession], promotes [PebbleListenerService] to foreground,
 * sends RUN_STARTED to the watch, and hands the phone's foreground to
 * OpenTracks's own recording UI. The per-tick polling + ContentObserver
 * registration lives in `PebbleListenerService` so it can run with the
 * phone screen off.
 *
 * Why an Activity exists at all: OpenTracks dispatches the dashboard
 * callback via `startActivity(intent)` (`DataProvider.startDashboard`),
 * not `startService`. We also use this Activity's task-stack presence
 * to hold the `FLAG_GRANT_READ_URI_PERMISSION` grant alive — Android
 * keeps URI grants valid as long as the receiving Activity's task is in
 * recents.
 *
 * URI delivery shape (`DataProvider.startDashboard`):
 *  - `intent.clipData[0]` = Track URI       (`/dashboard/tracks/<ids>`)
 *  - `intent.clipData[1]` = TrackPoints URI (`/dashboard/trackpoints/<ids>`)
 *  - `intent.clipData[2]` = Markers URI     (unused)
 *  - `intent.data` is never populated.
 *
 * UI: a minimalist "Recording — see your watch" fallback. The user only
 * sees it if they navigate back from OpenTracks while the run is still
 * going.
 */
class DashboardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val clip = intent?.clipData
        val trackUri       = clip?.takeIf { it.itemCount >= 1 }?.getItemAt(0)?.uri
        val trackPointsUri = clip?.takeIf { it.itemCount >= 2 }?.getItemAt(1)?.uri

        Log.d(TAG, "onCreate trackUri=$trackUri trackPointsUri=$trackPointsUri")

        RunSession.trackUri = trackUri
        RunSession.trackPointsUri = trackPointsUri
        RunSession.active = true

        // Promote the listener service to foreground for the duration of the
        // run. Called from this foreground Activity so the FGS-from-background
        // gate doesn't apply; the service's onStartCommand calls
        // startForeground within the OS's 5 s deadline.
        val promoteIntent = Intent(this, PebbleListenerService::class.java)
            .setAction(PebbleListenerService.ACTION_PROMOTE_FOREGROUND)
        startForegroundService(promoteIntent)

        // Tell the watch the run is recording; idle → active-run.
        lifecycleScope.launch { PebbleMessenger.sendRunStarted(this@DashboardActivity) }

        // Hand the phone's foreground to OpenTracks's own recording UI.
        // We do NOT finish() this Activity — its task-stack presence keeps
        // the FLAG_GRANT_READ_URI_PERMISSION grant alive. OpenTracks's
        // launcher Intent uses FLAG_ACTIVITY_NEW_TASK so it comes up on top
        // of our task without collapsing it.
        OpenTracksVariant.cached(this)?.let { variantPkg ->
            OpenTracksApi.openApp(this, variantPkg)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(TextView(this@DashboardActivity).apply {
                text = getString(R.string.dashboard_recording_message)
                textSize = 20f
                gravity = Gravity.CENTER
            })
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        // Drop the URIs from RunSession so the service stops polling. The
        // service itself stays alive (still bound by the Pebble app) and
        // will resume polling when a new run pushes new URIs in.
        RunSession.clear()
    }

    companion object {
        private const val TAG = "DashboardActivity"
    }
}
