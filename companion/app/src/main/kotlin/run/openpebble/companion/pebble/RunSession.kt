package run.openpebble.companion.pebble

/**
 * Process-scoped run-session state shared between `PebbleListenerService`
 * (which receives CMD_START / CMD_STOP) and `DashboardActivity` (which knows
 * when OpenTracks is actually recording). Both run in the same process — a
 * @Volatile singleton is sufficient.
 *
 * Spec §11 explicitly accepts no state persistence across companion restarts,
 * so DataStore / SharedPreferences are unnecessary here.
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
}
