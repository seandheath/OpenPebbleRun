/*
 * Active-run screen. Spec §4.2.2.
 *
 * 200×228 emery layout, three rows:
 *   HEART RATE       (large)   ### bpm
 *   PACE     CADENCE (medium)  M:SS /mi   ### spm
 *   DIST     TIME    (small)   0.00 mi    MM:SS
 *
 * Inbox keys handled (spec §7):
 *   120 PACE_CURRENT  uint16 sec/mi
 *   122 TIME          uint32 seconds
 *   123 DISTANCE      uint32 hundredths of a mile
 *   124 HR_EXTERNAL   uint16 bpm (only when companion forwards strap HR; step 8)
 *
 * HR + cadence display "---" placeholders here — step 7 reads them from the
 * watch's own HRM and step counter. Step 8 routes incoming HR_EXTERNAL to the
 * HR row, replacing the local HRM source.
 *
 * Buttons (spec §4.2.2, revised — see docs/log.md 2026-05-13):
 *   Back   → exit watchapp; run keeps recording in the companion.
 *   Select → open stop-confirm (spec §4.2.3).
 *   Up / Down → no-op.
 *
 * Stale handling (spec §4.2.2 + §8.1): if no inbox message arrives within 30 s,
 * dim text colors to GColorLightGray. Restore full color on next inbox.
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
