/*
 * Stop-confirm screen. Spec §4.2.3.
 *
 * Pushed by active-run when the user presses Down. Overlays active-run
 * (active-run's inbox handler stays installed so metrics keep updating
 * underneath in case the user cancels).
 *
 * Buttons (see docs/log.md 2026-05-13 icons entry):
 *   Up         → send CMD_STOP, vibrate, transition to run-summary (and
 *                silently remove active-run + self from the window stack so
 *                dismissing the summary exits the watchapp).
 *   Down / Back → pop self, return to active-run.
 *   Select     → no-op.
 *
 * Icon hints at the right edge: ✓ next to Up, ✕ next to Down.
 */

#pragma once

#include <pebble.h>

void stop_confirm_show(void);
