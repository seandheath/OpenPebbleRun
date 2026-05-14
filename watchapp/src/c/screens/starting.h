/*
 * Starting screen. Spec §4.2.1a.
 *
 *   ┌────────────────────────┐
 *   │                        │
 *   │      Starting…         │   bold centered title
 *   │                        │
 *   └────────────────────────┘
 *
 * Pushed by the idle screen after the user presses Select (which sends
 * KEY_CMD_START to the companion). Listens for KEY_RUN_STARTED from the
 * companion and transitions to active-run when it arrives. If no ack
 * lands within 15 s — longer than stopping's 10 s to absorb the cold
 * GPS-lock path through OpenTracks — swaps to an error state with retry
 * (Up) and dismiss (Back) options. Back from the error state pops back to
 * idle so the user can try again later or exit normally.
 *
 * Buttons (STARTING state): all inert.
 * Buttons (ERROR state):
 *   Up   → retry: re-send CMD_START, restart the timeout.
 *   Back → pop back to idle (idle remains armed for a later RUN_STARTED).
 *   Select / Down → inert.
 */

#pragma once

#include <pebble.h>

void starting_show(void);
