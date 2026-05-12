/*
 * OpenPebbleRun watchapp entry point. Spec §4.
 *
 * Skeleton scope (spec §14 step 4): AppMessage init + pre-run screen with
 * Select=CMD_START dispatch and 15s start timeout. HR sampling, cadence,
 * active-run layout, and stop-confirm land in later steps.
 *
 * On any exit path, heart-rate sampling must be turned off (spec §4.3) to
 * stop battery drain — handled here in `deinit` so it covers normal exit and
 * window-stack-pop alike. The skeleton hasn't yet enabled HRM (step 7) but the
 * disable call is harmless when HRM was never enabled.
 */

#include <pebble.h>
#include "app_message.h"
#include "screens/pre_run.h"

static void init(void) {
    app_message_init();
    pre_run_show();
}

static void deinit(void) {
    // Spec §4.3: "On any exit path: health_service_set_heart_rate_sample_period(0)".
    // Idempotent — safe even before step 7 enables sampling.
    health_service_set_heart_rate_sample_period(0);

    pre_run_hide();
    app_message_deinit();
}

int main(void) {
    init();
    app_event_loop();
    deinit();
    return 0;
}
