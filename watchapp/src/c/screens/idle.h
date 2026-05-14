/*
 * Idle screen. Spec §4.2.1.
 *
 * Watchapp entry point. Shown until a run is active. Two-line layout with
 * a Select-button affordance:
 *
 *   ┌────────────────────────┐
 *   │                        │
 *   │    OpenPebbleRun       │   title (bold)
 *   │                        │
 *   │   Press Select       ▶ │   prompt (regular) + play icon at the
 *   │     to start           │     physical Select-button gutter
 *   │                        │
 *   └────────────────────────┘
 *
 * Inbox handler watches for KEY_RUN_STARTED. On arrival, pushes active-run
 * on top of the stack — covers both the cold-launch-while-run-is-active
 * case (companion's onAppOpened replays RUN_STARTED) and the
 * launch-then-tap-Start case (companion's DashboardActivity sends it shortly
 * after we open).
 *
 * Buttons:
 *   Back   → exit watchapp (window_stack_pop_all).
 *   Select → send KEY_CMD_START to the companion and push the `starting`
 *            screen (which waits for KEY_RUN_STARTED with a 15 s timeout).
 *   Up / Down → no-op.
 */

#pragma once

#include <pebble.h>

void    idle_show(void);
void    idle_hide(void);

/*
 * Re-install idle's inbox handler. Used by `starting.c` on its Back-from-
 * error path so idle keeps catching RUN_STARTED (e.g. the user gives up
 * on the watch flow and starts from the companion instead). idle_show()
 * already arms the handler on first push, so callers that haven't
 * displaced the handler don't need to call this.
 */
void    idle_arm_inbox(void);

/*
 * Idle's Window pointer, for callers that need to perform stack surgery
 * (parallel to `active_run_get_window()`). Returns NULL if idle hasn't
 * been shown yet.
 */
Window *idle_get_window(void);
