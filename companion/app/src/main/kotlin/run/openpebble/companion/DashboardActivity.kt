package run.openpebble.companion

import android.content.Intent
import android.net.Uri
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
 * callback via `startActivity(intent)` (`IntentDashboardUtils.startDashboard`),
 * not `startService`. We also use this Activity's task-stack presence
 * to hold the `FLAG_GRANT_READ_URI_PERMISSION` grant alive — Android
 * keeps URI grants valid as long as the receiving Activity's task is in
 * recents.
 *
 * URI delivery shape (OpenTracks `IntentDashboardUtils`, observed in the field):
 *  - `intent.action`        = "Intent.OpenTracks-Dashboard"
 *  - `intent.clipData[0]`   = Track URI       (`<auth>/dashboard/tracks/<id>`)
 *  - `intent.clipData[1]`   = TrackPoints URI (`<auth>/dashboard/trackpoints/<id>`)
 *  - `intent.clipData[2]`   = Markers URI     (unused)
 *  - `intent.data` is never populated.
 *  - `<auth>` is `<variant-applicationId>.content`, varying per OpenTracks flavor
 *    (`de.dennisguse.opentracks.content`, `.playstore.content`, etc.).
 *
 * Note: these `/dashboard/...` paths are emitted by OpenTracks's public-API URI
 * builder and are distinct from the internal `TracksColumns.CONTENT_URI` paths
 * (which are `/tracks` / `/trackpoints/trackid`). Don't conflate the two when
 * reading the OpenTracks source.
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

        // Validate the incoming intent. The Activity is exported (OpenTracks
        // needs to launch us with the dashboard URIs), so without this guard
        // any installed app could push arbitrary content URIs into RunSession
        // and our 5s poll loop would happily read them. Blast radius is
        // limited (no network, no exfil) but it's gratuitous attack surface.
        //
        // Four checks:
        //  1. Intent action matches OpenTracks's dashboard-callback constant
        //     (`IntentDashboardUtils.ACTION_DASHBOARD` in OpenTracks source).
        //     Cheap positive signal; harmless to spoof but raises the bar.
        //  2. Caller is one of the OpenTracks variants we know about, looked
        //     up via Activity.getReferrer() — the documented API for exported
        //     no-result Activities. callingActivity is always null here
        //     because OpenTracks uses startActivity, not startActivityForResult.
        //     Only system-signed apps can override the launcher-supplied
        //     EXTRA_REFERRER_NAME, so the referrer pkg is trustworthy for our
        //     threat model (non-root installed apps).
        //  3. URI authority + path match OpenTracks's Dashboard API URIs:
        //     authority is `<variant-applicationId>.content`; Track URI path
        //     starts with `/dashboard/tracks/` and TrackPoints with
        //     `/dashboard/trackpoints/`.
        //  4. FLAG_GRANT_READ_URI_PERMISSION is set — without it the grant
        //     would fail downstream anyway; failing fast surfaces malformed
        //     intents in logcat instead of silent zero-metric reads.
        val grantFlagSet =
            ((intent?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        val action = intent?.action
        if (action != ACTION_DASHBOARD
            || !isCallerOpenTracks()
            || !grantFlagSet
            || !isValidDashboardUri(trackUri, "/dashboard/tracks/")
            || !isValidDashboardUri(trackPointsUri, "/dashboard/trackpoints/")) {
            Log.w(TAG, "rejecting dashboard intent: " +
                "action=$action caller=$referrer grant=$grantFlagSet " +
                "trackUri=$trackUri trackPointsUri=$trackPointsUri")
            finish()
            return
        }

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

    /**
     * True iff the caller is one of the OpenTracks variants in
     * [OpenTracksVariant.PROBE_ORDER]. Resolved via [Activity.getReferrer],
     * which the system fills in from the launching package for startActivity
     * calls. EXTRA_REFERRER_NAME can only be overridden by system-signed apps,
     * so the referrer host is trustworthy under our threat model
     * (non-privileged installed apps).
     */
    private fun isCallerOpenTracks(): Boolean {
        val ref = referrer ?: return false
        if (ref.scheme != "android-app") return false
        val pkg = ref.host ?: return false
        return pkg in OpenTracksVariant.PROBE_ORDER
    }

    /**
     * True iff [uri] looks like one of OpenTracks's Dashboard API URIs.
     * Authority pattern is `<variant-applicationId>.content` per OpenTracks's
     * AndroidManifest.xml (`android:authorities="${applicationId}.content"`):
     *   - de.dennisguse.opentracks.content              (irreproducible flavor)
     *   - de.dennisguse.opentracks.playstore.content    (reproducible / Play)
     *   - de.dennisguse.opentracks.nightly.content
     *   - any of the above with `.debug` for debug builds
     * Path observed in the field (OpenTracks's public Dashboard API URI builder,
     * distinct from the internal `TracksColumns.CONTENT_URI`):
     *   - Track:       /dashboard/tracks/<id>
     *   - TrackPoints: /dashboard/trackpoints/<id>
     */
    private fun isValidDashboardUri(uri: Uri?, expectedPathPrefix: String): Boolean {
        if (uri == null) return false
        val auth = uri.authority ?: return false
        if (!auth.startsWith("de.dennisguse.opentracks") || !auth.endsWith(".content")) {
            return false
        }
        val path = uri.path ?: return false
        return path.startsWith(expectedPathPrefix)
    }

    companion object {
        private const val TAG = "DashboardActivity"

        // Defined by OpenTracks in IntentDashboardUtils.java as ACTION_DASHBOARD.
        // Used as a cheap positive signal in the validator: any caller spamming
        // our component without setting this action gets a louder rejection.
        private const val ACTION_DASHBOARD = "Intent.OpenTracks-Dashboard"
    }
}
