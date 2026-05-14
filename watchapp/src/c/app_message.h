/*
 * AppMessage wire protocol. Spec §7.
 *
 * The watchapp and the Android companion both reference these key numbers
 * directly. Pebble's auto-generated MESSAGE_KEY_* symbols (from
 * package.json `messageKeys`) are not used — raw numeric keys avoid the
 * indirection and let the Kotlin side reference the same numbers directly.
 *
 * Watch → Companion:
 *   2   CMD_STOP       uint8  (no payload)
 *
 * Companion → Watch:
 *   110 RUN_STARTED    uint8  (no payload)
 *   111 RUN_STOPPED    uint8  (no payload; ack for CMD_STOP, also sent on
 *                              companion-initiated stop)
 *   120 PACE_CURRENT   uint16 (sec/mi, capped 3600)
 *   122 TIME           uint32 (seconds)
 *   123 DISTANCE       uint32 (hundredths of a mile)
 *
 * No version negotiation. Both sides ignore unknown keys.
 */

#pragma once

#include <pebble.h>

// Watch → Companion
#define KEY_CMD_STOP     2

// Companion → Watch
#define KEY_RUN_STARTED  110
#define KEY_RUN_STOPPED  111
#define KEY_PACE_CURRENT 120
#define KEY_TIME         122
#define KEY_DISTANCE     123

// Buffer sizes. Spec §4.5 throttles to one in-flight outbox at a time, so
// the buffers only need to hold a single max-sized message. 256 B is
// generous for our payloads (largest is one uint32 + dict overhead).
#define APP_MESSAGE_INBOX_SIZE  256
#define APP_MESSAGE_OUTBOX_SIZE 256

/*
 * Inbox handler signature for screens that want to react to companion-side
 * messages. Registered via app_message_set_inbox_handler().
 *
 * Implementations read whatever keys they care about from `iter` and leave
 * others alone — unknown keys are ignored.
 */
typedef void (*AppMessageInboxHandler)(DictionaryIterator *iter);

void app_message_init(void);
void app_message_deinit(void);

// Replace the current inbox handler. Pass NULL to clear.
void app_message_set_inbox_handler(AppMessageInboxHandler handler);

/*
 * Send a payloadless uint8 command identified by `key`. Returns true if the
 * message was queued. AppMessage delivers one at a time and ACKs each (spec
 * §4.5); callers rate-limit to one in-flight send.
 */
bool app_message_send_cmd(uint32_t key);
