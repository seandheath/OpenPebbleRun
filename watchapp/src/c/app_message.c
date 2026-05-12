#include "app_message.h"

static AppMessageInboxHandler s_inbox_handler = NULL;

static void inbox_received_handler(DictionaryIterator *iter, void *context) {
    if (s_inbox_handler) {
        s_inbox_handler(iter);
    }
}

static void inbox_dropped_handler(AppMessageResult reason, void *context) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Inbox dropped: %d", reason);
}

static void outbox_failed_handler(DictionaryIterator *iter, AppMessageResult reason, void *context) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Outbox failed: %d", reason);
}

static void outbox_sent_handler(DictionaryIterator *iter, void *context) {
    // Spec §4.5: AppMessage ACKs each message. Throttle is enforced by callers
    // sending one at a time; no queue here.
}

void app_message_init(void) {
    app_message_register_inbox_received(inbox_received_handler);
    app_message_register_inbox_dropped(inbox_dropped_handler);
    app_message_register_outbox_failed(outbox_failed_handler);
    app_message_register_outbox_sent(outbox_sent_handler);
    app_message_open(APP_MESSAGE_INBOX_SIZE, APP_MESSAGE_OUTBOX_SIZE);
}

void app_message_deinit(void) {
    s_inbox_handler = NULL;
    // Pebble OS reclaims AppMessage state at app exit; no explicit close needed.
}

void app_message_set_inbox_handler(AppMessageInboxHandler handler) {
    s_inbox_handler = handler;
}

bool app_message_send_cmd(uint32_t key) {
    DictionaryIterator *iter;
    AppMessageResult prep = app_message_outbox_begin(&iter);
    if (prep != APP_MSG_OK) {
        APP_LOG(APP_LOG_LEVEL_WARNING, "outbox_begin failed: %d", prep);
        return false;
    }
    // Payload value is ignored by spec; send 1 as a non-zero marker.
    dict_write_uint8(iter, key, 1);
    AppMessageResult send = app_message_outbox_send();
    if (send != APP_MSG_OK) {
        APP_LOG(APP_LOG_LEVEL_WARNING, "outbox_send failed: %d", send);
        return false;
    }
    return true;
}
