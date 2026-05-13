/*
 * OpenPebbleRun watchapp entry point. Spec §4.
 *
 * v0.1 flow: launch lands on the idle screen ("OpenPebbleRun" + "Start a run
 * on your phone"). The idle screen's inbox handler watches for RUN_STARTED;
 * on arrival it pushes active-run on top. If the user opens the watchapp
 * while a run is already active, the companion's
 * PebbleListenerService.onAppOpened replays RUN_STARTED almost immediately,
 * so the idle screen is effectively transient in that case.
 *
 * On any exit path, heart-rate sampling must be turned off (spec §4.3) to
 * stop battery drain — handled here in `deinit` so it covers every code
 * path. active_run.c's window_unload also unsubscribes; the duplicate call
 * here is idempotent and guards against future refactors that bypass it.
 */

#include <pebble.h>
#include "app_message.h"
#include "screens/idle.h"
#include "screens/active_run.h"

static void init(void) {
    app_message_init();
    idle_show();
}

static void deinit(void) {
    // Spec §4.3: "On any exit path: health_service_set_heart_rate_sample_period(0)".
    // Idempotent — active_run's window_unload also calls this when it runs.
    health_service_set_heart_rate_sample_period(0);

    // Tear down both screen modules. Either may hold a live Window pointer
    // depending on whether the user ever transitioned to active-run.
    idle_hide();
    active_run_hide();
    app_message_deinit();
}

int main(void) {
    init();
    app_event_loop();
    deinit();
    return 0;
}
