/*
 * Stop-confirm screen. Spec §4.2.3.
 *
 * Pushed by active-run when the user presses Down. Overlays active-run
 * (active-run's inbox handler stays installed so metrics keep updating
 * underneath in case the user cancels).
 *
 * Buttons:
 *   Up         → send CMD_STOP, vibrate, push the stopping screen (which
 *                waits for the companion's RUN_STOPPED ack before showing
 *                run-summary).
 *   Down / Back → pop self, return to active-run.
 *   Select     → no-op.
 *
 * Icon hints at the right edge: ✓ next to Up, ✕ next to Down.
 */

#pragma once

#include <pebble.h>

void stop_confirm_show(void);
