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
 * activities. Intent extras drive track naming and the Dashboard callback.
 *
 * Naming / categorization:
 *  - TRACK_NAME     = "Run"        (no template — keep simple per spec)
 *  - TRACK_CATEGORY = "running"
 *  - TRACK_ICON     = "running"
 *
 * Dashboard callback (spec §6.1, §6.2):
 *  - STATS_TARGET_PACKAGE = our applicationId (varies between debug/release)
 *  - STATS_TARGET_CLASS   = fully qualified name of [DashboardActivity]
 *
 * OpenTracks invokes the dashboard activity with Track + TrackPoints content
 * URIs and `FLAG_GRANT_READ_URI_PERMISSION`; URI parsing happens in
 * [DashboardActivity].
 */
object OpenTracksApi {

    private const val TAG = "OpenTracksApi"

    private const val ACTION_START = "de.dennisguse.opentracks.publicapi.StartRecording"
    private const val ACTION_STOP  = "de.dennisguse.opentracks.publicapi.StopRecording"

    // OpenTracks's publicapi activities live at FIXED FQCNs regardless of the
    // variant (`playstore`, `debug`, `nightly` are applicationId *suffixes*,
    // not source-package changes). Spec §6.1 writes the Component as
    // "<package>/de.dennisguse.opentracks.publicapi.StartRecording" — the
    // path after the slash is the literal activity class path.
    private const val CLASS_START = "de.dennisguse.opentracks.publicapi.StartRecording"
    private const val CLASS_STOP  = "de.dennisguse.opentracks.publicapi.StopRecording"

    // Extras (string keys mirror spec §6.1).
    private const val EXTRA_TRACK_NAME           = "TRACK_NAME"
    private const val EXTRA_TRACK_CATEGORY       = "TRACK_CATEGORY"
    private const val EXTRA_TRACK_ICON           = "TRACK_ICON"
    private const val EXTRA_STATS_TARGET_PACKAGE = "STATS_TARGET_PACKAGE"
    private const val EXTRA_STATS_TARGET_CLASS   = "STATS_TARGET_CLASS"

    /**
     * Send a StartRecording Intent to the given OpenTracks variant package.
     * Returns true if the intent was dispatched; false if no variant package is
     * available (e.g., user uninstalled OpenTracks between probe and click).
     */
    fun startRecording(context: Context, variantPackage: String): Boolean {
        val intent = Intent(ACTION_START).apply {
            component = ComponentName(variantPackage, CLASS_START)
            putExtra(EXTRA_TRACK_NAME, "Run")
            putExtra(EXTRA_TRACK_CATEGORY, "running")
            putExtra(EXTRA_TRACK_ICON, "running")
            putExtra(EXTRA_STATS_TARGET_PACKAGE, context.packageName)
            putExtra(EXTRA_STATS_TARGET_CLASS, DashboardActivity::class.java.name)
            // FLAG_ACTIVITY_NEW_TASK is required when starting from a non-Activity
            // context (e.g., a PebbleKit service handler). Cheap to set always.
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
     * Best-effort attempt to open the OpenTracks app — its settings screen if we
     * can resolve a dedicated activity, otherwise the launcher. Spec §5.2.1.
     *
     * Returns true if any Intent dispatched.
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
