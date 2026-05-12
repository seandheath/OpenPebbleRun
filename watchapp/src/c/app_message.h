/*
 * AppMessage wire protocol. Spec §7.
 *
 * Key numbers are pinned by the spec; do not renumber. The watchapp and the
 * Android companion both reference these directly. Pebble's auto-generated
 * MESSAGE_KEY_* symbols (from package.json `messageKeys`) are NOT used — using
 * raw numeric keys avoids the indirection and lets the Kotlin side reference
 * the same numbers directly.
 *
 * Watch → Companion:
 *   1   CMD_START          uint8  (no payload — value ignored)
 *   2   CMD_STOP           uint8
 *
 * Companion → Watch:
 *   110 RUN_STARTED        uint8
 *   111 RUN_FAILED         uint8
 *   113 HR_SOURCE_EXTERNAL uint8  (switch to forwarded HR)
 *   114 HR_SOURCE_INTERNAL uint8  (switch back to watch HRM)
 *   120 PACE_CURRENT       uint16 (sec/mi, capped 3600)
 *   122 TIME               uint32 (seconds)
 *   123 DISTANCE           uint32 (hundredths of a mile)
 *   124 HR_EXTERNAL        uint16 (bpm; only when external source active)
 *
 * Compatibility: no version negotiation. Both sides ignore unknown keys (spec §7).
 */

#pragma once

#include <pebble.h>

// Watch → Companion
#define KEY_CMD_START          1
#define KEY_CMD_STOP           2

// Companion → Watch
#define KEY_RUN_STARTED        110
#define KEY_RUN_FAILED         111
#define KEY_HR_SOURCE_EXTERNAL 113
#define KEY_HR_SOURCE_INTERNAL 114
#define KEY_PACE_CURRENT       120
#define KEY_TIME               122
#define KEY_DISTANCE           123
#define KEY_HR_EXTERNAL        124

// Buffer sizes. Spec §4.5 says throttle to one in-flight outbox at a time, so
// the buffers only need to hold a single max-sized message. 256B is generous
// for our payloads (largest is one uint32 + dict overhead, ~32 bytes).
#define APP_MESSAGE_INBOX_SIZE  256
#define APP_MESSAGE_OUTBOX_SIZE 256

/*
 * Inbox handler signature for screens that want to react to companion-side
 * messages (RUN_STARTED / RUN_FAILED / metric updates). Registered via
 * app_message_set_inbox_handler().
 *
 * Implementations should read whatever keys they care about from `iter` and
 * leave others alone — unknown keys are ignored by spec.
 */
typedef void (*AppMessageInboxHandler)(DictionaryIterator *iter);

void app_message_init(void);
void app_message_deinit(void);

// Replace the current inbox handler. Pass NULL to clear.
void app_message_set_inbox_handler(AppMessageInboxHandler handler);

/*
 * Send a payloadless uint8 command (CMD_START / CMD_STOP). Returns true if the
 * message was queued. AppMessage delivers one at a time and ACKs each (spec
 * §4.5); callers should rate-limit to one in-flight send.
 */
bool app_message_send_cmd(uint32_t key);
