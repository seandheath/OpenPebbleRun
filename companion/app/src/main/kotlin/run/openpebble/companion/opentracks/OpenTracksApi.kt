package run.openpebble.companion.opentracks

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import run.openpebble.companion.DashboardActivity

/**
 * OpenTracks Public API client. Spec §6.1.
 *
 * Component-targets the variant-specific StartRecording / StopRecording
 * activities. Intent extras drive track tagging and the Dashboard callback.
 *
 * Naming / categorization:
 *  - TRACK_NAME — intentionally not set. OpenTracks's own "Default track
 *                 name" preference (Settings → Recording → Default track
 *                 name; Date ISO 8601 / Date local / Number) applies when
 *                 the extra is absent. SharedPreferences aren't readable
 *                 from third-party apps, so deferring is the only way to
 *                 honor the user's choice.
 *  - TRACK_CATEGORY = "running"
 *  - TRACK_ICON     = "running"
 *
 * Dashboard callback:
 *  - STATS_TARGET_PACKAGE = our applicationId
 *  - STATS_TARGET_CLASS   = fully qualified name of [DashboardActivity]
 *
 * Background Activity Launch: callers must be foreground-eligible at call
 * time — an Activity (MainActivity / DashboardActivity) or a foreground
 * service (PebbleListenerService while a run is active).
 */
object OpenTracksApi {

    private const val TAG = "OpenTracksApi"

    private const val ACTION_START = "de.dennisguse.opentracks.publicapi.StartRecording"
    private const val ACTION_STOP  = "de.dennisguse.opentracks.publicapi.StopRecording"

    // OpenTracks's publicapi activities live at fixed FQCNs regardless of
    // the variant (`playstore`, `debug`, `nightly` are applicationId
    // suffixes, not source-package changes).
    private const val CLASS_START = "de.dennisguse.opentracks.publicapi.StartRecording"
    private const val CLASS_STOP  = "de.dennisguse.opentracks.publicapi.StopRecording"

    private const val EXTRA_TRACK_CATEGORY       = "TRACK_CATEGORY"
    private const val EXTRA_TRACK_ICON           = "TRACK_ICON"
    private const val EXTRA_STATS_TARGET_PACKAGE = "STATS_TARGET_PACKAGE"
    private const val EXTRA_STATS_TARGET_CLASS   = "STATS_TARGET_CLASS"

    /**
     * Send a StartRecording Intent to the given OpenTracks variant package.
     * Returns true if the intent was dispatched without exception.
     */
    fun startRecording(context: Context, variantPackage: String): Boolean {
        val intent = Intent(ACTION_START).apply {
            component = ComponentName(variantPackage, CLASS_START)
            putExtra(EXTRA_TRACK_CATEGORY, "running")
            putExtra(EXTRA_TRACK_ICON, "running")
            putExtra(EXTRA_STATS_TARGET_PACKAGE, context.packageName)
            putExtra(EXTRA_STATS_TARGET_CLASS, DashboardActivity::class.java.name)
            // FLAG_ACTIVITY_NEW_TASK is required when starting from a
            // non-Activity context (PebbleListenerService).
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Log.d(TAG, "StartRecording dispatched to $variantPackage")
            true
        } catch (e: Exception) {
            Log.w(TAG, "StartRecording failed for $variantPackage", e)
            false
        }
    }

    /** Send a StopRecording Intent. Mirrors [startRecording] semantics. */
    fun stopRecording(context: Context, variantPackage: String): Boolean {
        val intent = Intent(ACTION_STOP).apply {
            component = ComponentName(variantPackage, CLASS_STOP)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Log.d(TAG, "StopRecording dispatched to $variantPackage")
            true
        } catch (e: Exception) {
            Log.w(TAG, "StopRecording failed for $variantPackage", e)
            false
        }
    }

    /**
     * Open the OpenTracks app's main launcher activity. Returns true if the
     * Intent dispatched.
     */
    fun openApp(context: Context, variantPackage: String): Boolean {
        val pm = context.packageManager
        val launcher = pm.getLaunchIntentForPackage(variantPackage)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launcher != null) {
            return try {
                context.startActivity(launcher)
                true
            } catch (e: Exception) {
                Log.w(TAG, "openApp failed for $variantPackage", e)
                false
            }
        }
        return false
    }
}
