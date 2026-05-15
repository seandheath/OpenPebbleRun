package run.openpebble.companion.pebble

/**
 * Process-scoped run-session state shared between `PebbleListenerService` and
 * `DashboardActivity`. Both run in the same process — a @Volatile singleton is
 * sufficient.
 *
 * Spec §11 explicitly accepts no state persistence across companion restarts,
 * so DataStore / SharedPreferences are unnecessary here.
 *
 * Dashboard URI ownership lives on `PebbleListenerService` itself (per-instance
 * fields). The Activity hands URIs to the service via `Intent.setClipData` +
 * `FLAG_GRANT_READ_URI_PERMISSION` on the `ACTION_PROMOTE_FOREGROUND` start
 * intent, which re-delegates the OpenTracks grant from the Activity's task to
 * the foreground service component. Keeping URIs on the service (the only
 * reader) removes a stale cross-component invariant and lets `ensureObservers`
 * collapse to a one-shot.
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

    /** Reset run-scoped state. */
    fun clear() {
        active = false
    }
}
