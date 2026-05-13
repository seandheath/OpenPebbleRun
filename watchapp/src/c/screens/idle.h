/*
 * Idle screen. Spec §4.2.1.
 *
 * Watchapp entry point. Shown until a run is active. Two-line layout:
 *
 *   ┌────────────────────────┐
 *   │                        │
 *   │    OpenPebbleRun       │   title (bold)
 *   │                        │
 *   │   Start a run on       │   prompt (regular)
 *   │      your phone        │
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
 *   Select / Up / Down → no-op. There is no on-watch start affordance.
 */

#pragma once

#include <pebble.h>

void idle_show(void);
void idle_hide(void);
