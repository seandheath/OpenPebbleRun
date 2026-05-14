/*
 * Active-run screen. Spec §4.2.2.
 *
 * Pushed by the idle screen (§4.2.1) when its inbox handler receives
 * RUN_STARTED. The watchapp does not launch directly into this screen —
 * idle stays up until the companion confirms a run is active so the user
 * never sees "---" placeholders on what looks like a run.
 *
 * 200×228 emery layout, three rows:
 *   HEART RATE       (large)   ### bpm
 *   PACE     CADENCE (medium)  M:SS /mi   ### spm
 *   DIST     TIME    (small)   0.00 mi    MM:SS
 * Plus a small filled-square stop-icon hint at the right edge of the
 * bottom row, vertically aligned with the physical Down button.
 *
 * Inbox keys handled (spec §7):
 *   120 PACE_CURRENT  uint16 sec/mi
 *   122 TIME          uint32 seconds
 *   123 DISTANCE      uint32 hundredths of a mile
 *
 * HR is read from the watch's internal HRM (HealthMetricHeartRateBPM) and
 * averaged for the run-summary screen. CADENCE is derived locally on the
 * watch from HealthMetricStepCount (spec §4.3): 5 s polling, 15 s
 * rolling window, rendered as steps-per-minute. Not surfaced in the
 * run-summary (spec §4.2.5 lists DIST / TIME / AVG PACE / AVG HR only).
 *
 * Buttons (spec §4.2.2):
 *   Back     → exit watchapp; run keeps recording in the companion. The
 *              user can reopen and the companion replays RUN_STARTED.
 *   Down     → open stop-confirm (spec §4.2.3). Square icon hint at the
 *              right edge marks this affordance.
 *   Select / Up → no-op.
 *
 * Stale handling (spec §4.2.2 + §8.1): if no inbox message arrives within
 * 30 s, dim text colors to GColorLightGray. Restore full color on next
 * inbox.
 */

#pragma once

#include <pebble.h>

/*
 * Snapshot of the run's end-of-run statistics, populated from the most recent
 * companion-pushed values (time/distance) and the running mean of internal-HRM
 * samples observed while the active-run screen was up. Consumed by the
 * run-summary screen.
 *
 *   time_sec            — locally-ticked elapsed seconds, snapped to the
 *                         companion's KEY_TIME whenever one arrives.
 *   dist_hundredths_mi  — most recent KEY_DISTANCE value.
 *   avg_hr              — mean of all non-zero HRM samples, or 0 if no sample
 *                         ever fired (very short run / sensor cold).
 */
typedef struct {
    uint32_t time_sec;
    uint32_t dist_hundredths_mi;
    uint16_t avg_hr;
} RunStats;

void    active_run_show(void);
void    active_run_hide(void);
Window *active_run_get_window(void);
void    active_run_get_stats(RunStats *out);
