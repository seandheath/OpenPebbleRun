/*
 * Stop-confirm screen. Spec §4.2.3.
 *
 * Pushed by active-run when the user presses Select. Overlays active-run
 * (active-run's inbox handler stays installed so metrics keep updating
 * underneath in case the user cancels with Back).
 *
 * Buttons:
 *   Select → send CMD_STOP, vibrate, transition to run-summary (and silently
 *            remove active-run + self from the window stack so dismissing the
 *            summary lands on pre-run).
 *   Back   → pop self, return to active-run.
 */

#pragma once

#include <pebble.h>

void stop_confirm_show(void);
