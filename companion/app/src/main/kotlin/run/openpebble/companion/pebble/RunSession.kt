package run.openpebble.companion.pebble

import android.net.Uri

/**
 * Process-scoped run-session state shared between `PebbleListenerService`
 * (which owns the poll loop + ContentObservers and handles CMD_STOP from the
 * watch) and `DashboardActivity` (which receives OpenTracks's dashboard
 * callback and is the original URI grant target). Both run in the same
 * process — a @Volatile singleton is sufficient.
 *
 * Spec §11 explicitly accepts no state persistence across companion restarts,
 * so DataStore / SharedPreferences are unnecessary here.
 *
 * URI ownership: OpenTracks grants `FLAG_GRANT_READ_URI_PERMISSION` to our
 * process when it launches `DashboardActivity` with the dashboard URIs in
 * `intent.clipData`. The grant is per-UID and persists for the lifetime of the
 * Activity's task (i.e. until the user removes us from recents). We stash the
 * URIs here so PebbleListenerService can read them on its poll cadence even
 * when DashboardActivity itself is no longer foreground (phone screen off,
 * other app on top, etc.) — that's the whole point of v0.1's "phone in
 * pocket" architecture.
 */
object RunSession {
    /** True between DashboardActivity.onCreate and onDestroy. */
    @Volatile var active: Boolean = false

    /**
     * True between PebbleListenerService.onAppOpened and onAppClosed for our
     * watchapp UUID. Reflects watchapp lifecycle on the wrist, NOT just
     * Bluetooth connection state.
     */
    @Volatile var watchAppOpen: Boolean = false

    /** Dashboard Track URI (clipData[0]) once OpenTracks has called us back. */
    @Volatile var trackUri: Uri? = null

    /** Dashboard TrackPoints URI (clipData[1]) once OpenTracks has called us back. */
    @Volatile var trackPointsUri: Uri? = null

    /** Reset run-scoped state. Called from DashboardActivity.onDestroy. */
    fun clear() {
        active = false
        trackUri = null
        trackPointsUri = null
    }
}
