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
 * Buttons:
 *   Back   → send CMD_STOP, pop. Step 9 wraps this with a confirm screen.
 *   Select/Up/Down → no-op.
 *
 * Stale handling (spec §4.2.2 + §8.1): if no inbox message arrives within 30 s,
 * dim text colors to GColorLightGray. Restore full color on next inbox.
 */

#pragma once

#include <pebble.h>

void active_run_show(void);
void active_run_hide(void);
