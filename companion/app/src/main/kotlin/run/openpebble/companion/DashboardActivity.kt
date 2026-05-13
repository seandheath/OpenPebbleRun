package run.openpebble.companion

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import run.openpebble.companion.pebble.PebbleMessenger
import run.openpebble.companion.pebble.RunSession

/**
 * OpenTracks Dashboard receiver. Spec §6.2.
 *
 * **Thin role:** receive the dashboard URIs from OpenTracks's callback intent,
 * stash them in [RunSession], send RUN_STARTED to the watch, then sit in the
 * background. The actual per-tick polling + ContentObserver registration lives
 * in `PebbleListenerService` so it can run with the phone screen off and the
 * activity stack arbitrarily deep.
 *
 * Why this Activity still exists at all: OpenTracks dispatches the dashboard
 * callback via `startActivity(intent)` (see `DataProvider.startDashboard`),
 * not `startService`. So an Activity is mandatory as the recipient. We also
 * use this Activity's task-stack presence to hold the
 * `FLAG_GRANT_READ_URI_PERMISSION` grant alive — Android keeps URI grants
 * valid as long as the receiving Activity's task is in recents.
 *
 * Verified against OpenTracks v4.27.0 (`DataProvider.java`,
 * `CustomContentProvider.java`):
 *  - All three URIs ride in `intent.clipData` — `[0]` Track, `[1]` TrackPoints,
 *    `[2]` Markers. `intent.data` is unused.
 *  - The dashboard URIs are explicit `/dashboard/...` paths, distinct from the
 *    internal `/trackpoints/trackid/...` URIs (the dashboard provider applies
 *    `DataProvider.DATA_PROJECTIONMAP_*` to expose a restricted column set).
 *
 * UI: minimalist "Recording — see your watch" text. The watch is the primary
 * surface during a run; this screen exists only so OpenTracks has somewhere
 * to dispatch the dashboard intent.
 */
class DashboardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OpenTracks ships URIs via clipData (`DataProvider.startDashboard`):
        //   clipData[0] = Track URI       (/dashboard/tracks/<ids>)
        //   clipData[1] = TrackPoints URI (/dashboard/trackpoints/<ids>)
        //   clipData[2] = Markers URI     (unused)
        // intent.data is never populated.
        val clip = intent?.clipData
        val trackUri       = clip?.takeIf { it.itemCount >= 1 }?.getItemAt(0)?.uri
        val trackPointsUri = clip?.takeIf { it.itemCount >= 2 }?.getItemAt(1)?.uri

        Log.d(TAG, "onCreate trackUri=$trackUri trackPointsUri=$trackPointsUri")

        RunSession.trackUri = trackUri
        RunSession.trackPointsUri = trackPointsUri
        RunSession.active = true

        // Tell the watch the run is recording. Watch transitions from "Starting…"
        // to the active-run window on receipt. Spec §4.2.1, §7.2 key 110. The
        // service's next poll tick will start pushing pace/time/distance.
        lifecycleScope.launch { PebbleMessenger.sendRunStarted(this@DashboardActivity) }

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
        // will resume polling if/when a new run pushes new URIs in.
        RunSession.clear()
    }

    companion object {
        private const val TAG = "DashboardActivity"
    }
}
