package run.openpebble.companion.opentracks

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * OpenTracks variant detection. Spec §5.4.
 *
 * Probes a hardcoded list of known OpenTracks `applicationId`s in order and
 * returns the first one [PackageManager] resolves. Result is cached in
 * SharedPreferences and re-probed on each `MainActivity.onResume` (caller's
 * responsibility) so users can install / switch variants without restarting.
 *
 * Note on probe order: the Play Store variant's actual `applicationId` is
 * lowercase (`de.dennisguse.opentracks.playstore`), despite a typo in some
 * versions of the OpenTracks README that shows capital "S" (per spec §5.4).
 */
object OpenTracksVariant {

    private const val TAG = "OpenTracksVariant"
    private const val PREFS = "openpebblerun"
    private const val KEY_PACKAGE = "opentracks_variant"

    /**
     * Spec §5.4 probe order. F-Droid default first; playstore, debug, nightly after.
     * First resolved wins — no picker UI.
     */
    val PROBE_ORDER: List<String> = listOf(
        "de.dennisguse.opentracks",
        "de.dennisguse.opentracks.playstore",
        "de.dennisguse.opentracks.debug",
        "de.dennisguse.opentracks.nightly",
    )

    /**
     * Detected OpenTracks variant. `package` is non-null when installed.
     * `label` is the variant suffix shown on the Home screen ("F-Droid", "Play",
     * "Debug", "Nightly").
     */
    data class Detection(val pkg: String?, val label: String?) {
        val isInstalled: Boolean get() = pkg != null
    }

    /**
     * Probe installed OpenTracks variants and return the first match. Cached in
     * SharedPreferences so we can do an O(1) lookup on cold start and re-probe
     * lazily in `onResume`.
     */
    fun detect(context: Context): Detection {
        val pm = context.packageManager
        for (pkg in PROBE_ORDER) {
            if (isInstalled(pm, pkg)) {
                cache(context, pkg)
                Log.d(TAG, "Detected OpenTracks variant: $pkg")
                return Detection(pkg, labelFor(pkg))
            }
        }
        clearCache(context)
        Log.d(TAG, "No OpenTracks variant installed")
        return Detection(null, null)
    }

    /**
     * Cached package name from a prior probe. May return a stale value if the
     * user uninstalled OpenTracks while we weren't running. Always follow up
     * with [detect] before relying on this for an Intent.
     */
    fun cached(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PACKAGE, null)
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun cache(context: Context, pkg: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PACKAGE, pkg)
            .apply()
    }

    private fun clearCache(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PACKAGE)
            .apply()
    }

    private fun labelFor(pkg: String): String = when (pkg) {
        "de.dennisguse.opentracks"           -> "F-Droid"
        "de.dennisguse.opentracks.playstore" -> "Play"
        "de.dennisguse.opentracks.debug"     -> "Debug"
        "de.dennisguse.opentracks.nightly"   -> "Nightly"
        else                                  -> pkg
    }
}
