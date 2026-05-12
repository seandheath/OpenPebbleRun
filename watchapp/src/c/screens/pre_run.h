/*
 * Pre-run screen. Spec §4.2.1.
 *
 * Shown on app launch. Displays title + "Press Select to start". Select sends
 * CMD_START to the companion and transitions to a "Starting…" state with a 15s
 * timeout (spec text on timeout: "Couldn't start. Open companion app on phone.").
 *
 * On RUN_STARTED from the companion, hands off to the active-run window.
 * The active-run window is a stub at the skeleton stage (spec §14 step 6
 * implements the 5-metric layout).
 */

#pragma once

#include <pebble.h>

void pre_run_show(void);
void pre_run_hide(void);
