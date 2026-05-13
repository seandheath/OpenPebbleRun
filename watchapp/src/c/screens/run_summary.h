/*
 * Run-summary screen. Spec §4.2.4.
 *
 * Pushed by stop-confirm after the user confirms stop. Reads the latest run
 * stats from active_run via `active_run_get_stats()` — active-run's static
 * state survives even after its window is removed from the stack, so the
 * snapshot is valid until the watchapp exits.
 *
 * Four rows: DISTANCE, TIME, AVG PACE, AVG HR. Any button pops back to
 * pre-run. No inbox handler — no metrics expected once the run has stopped.
 */

#pragma once

#include <pebble.h>

void run_summary_show(void);
