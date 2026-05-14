/*
 * Stopping screen. Spec §4.2.4.
 *
 *   ┌────────────────────────┐
 *   │                        │
 *   │      Stopping…         │   bold centered title
 *   │                        │
 *   └────────────────────────┘
 *
 * Pushed by stop-confirm after the user confirms Up. Listens for
 * KEY_RUN_STOPPED from the companion and transitions to run-summary
 * when it arrives. If no ack lands within 10 s, swaps to an error
 * state with retry (Up) and dismiss (Back) options — the latter falls
 * through to run-summary, since the run probably did stop and the user
 * can verify on the phone.
 *
 * Buttons (STOPPING state): all inert.
 * Buttons (ERROR state):
 *   Up   → retry: re-send CMD_STOP, restart the timeout.
 *   Back → fall through to run-summary anyway.
 *   Select / Down → inert.
 */

#pragma once

#include <pebble.h>

void stopping_show(void);
