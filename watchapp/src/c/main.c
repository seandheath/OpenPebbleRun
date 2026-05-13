/*
 * OpenPebbleRun watchapp entry point. Spec §4.
 *
 * v0.1 flow: launch lands directly on the active-run screen — runs are
 * started from the companion phone app (spec §5.2.2, §11), so there's no
 * watch-side "press Select to start" affordance any more. If no run is
 * active when the user manually launches the app, active-run renders its
 * placeholder values ("---") and dims after 30 s of inbox silence. If a run
 * *is* active, the companion's PebbleListenerService.onAppOpened replays
 * RUN_STARTED on app launch and the poll loop's next tick populates the
 * metrics.
 *
 * On any exit path, heart-rate sampling must be turned off (spec §4.3) to
 * stop battery drain — handled here in `deinit` so it covers normal exit and
 * window-stack-pop alike. active_run.c's window_unload already unsubscribes;
 * the duplicate call here is idempotent and guards against future refactors.
 */

#include <pebble.h>
#include "app_message.h"
#include "screens/active_run.h"

static void init(void) {
    app_message_init();
    active_run_show();
}

static void deinit(void) {
    // Spec §4.3: "On any exit path: health_service_set_heart_rate_sample_period(0)".
    // Idempotent — active_run's window_unload also calls this.
    health_service_set_heart_rate_sample_period(0);

    active_run_hide();
    app_message_deinit();
}

int main(void) {
    init();
    app_event_loop();
    deinit();
    return 0;
}
