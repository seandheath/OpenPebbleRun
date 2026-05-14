#include "stopping.h"
#include "active_run.h"
#include "run_summary.h"
#include "../app_message.h"

/*
 * Stopping screen implementation. See stopping.h for the contract.
 *
 * The watch-side stop flow used to be fire-and-forget: stop-confirm sent
 * CMD_STOP and immediately showed run-summary, regardless of whether the
 * companion received the message or successfully dispatched OpenTracks's
 * StopRecording intent. This screen is the explicit "waiting for the
 * companion to confirm" state — it commits to run-summary only after
 * KEY_RUN_STOPPED arrives, and surfaces an error if it doesn't.
 */

#define STOPPING_TIMEOUT_MS 10000

typedef enum {
    STOPPING,
    STOPPING_ERROR,
} StoppingState;

static Window    *s_window = NULL;
static TextLayer *s_title  = NULL;
static AppTimer  *s_timeout = NULL;
static StoppingState s_state = STOPPING;

static void cancel_timeout(void) {
    if (s_timeout) { app_timer_cancel(s_timeout); s_timeout = NULL; }
}

static void show_summary_and_cleanup(void) {
    cancel_timeout();
    // Push run-summary on top, then silently remove stopping + active-run
    // so Back from the summary lands on an empty stack and exits the app.
    run_summary_show();
    window_stack_remove(active_run_get_window(), false);
    window_stack_remove(s_window, false);
}

static void timeout_cb(void *ctx) {
    s_timeout = NULL;
    s_state = STOPPING_ERROR;
    text_layer_set_text(s_title, "Couldn't stop.\nUp = retry\nBack = ok");
}

static void start_timeout(void) {
    cancel_timeout();
    s_timeout = app_timer_register(STOPPING_TIMEOUT_MS, timeout_cb, NULL);
}

// === Inbox ===============================================================

static void inbox_handler(DictionaryIterator *iter) {
    if (dict_find(iter, KEY_RUN_STOPPED)) {
        show_summary_and_cleanup();
    }
    // Trailing metric keys (PACE_CURRENT / TIME / DISTANCE) can still land
    // here in the window between CMD_STOP and the companion's poll loop
    // demoting. Ignore — the run is on the way out either way.
}

// === Buttons =============================================================

static void up_click_handler(ClickRecognizerRef r, void *ctx) {
    if (s_state == STOPPING_ERROR) {
        // Retry: re-send CMD_STOP and re-enter the waiting state.
        app_message_send_cmd(KEY_CMD_STOP);
        s_state = STOPPING;
        text_layer_set_text(s_title, "Stopping…");
        start_timeout();
    }
}

static void back_click_handler(ClickRecognizerRef r, void *ctx) {
    if (s_state == STOPPING_ERROR) {
        // Fall through to summary even without confirmation — the run
        // likely did stop, and the user can verify on the phone.
        show_summary_and_cleanup();
    }
    // In STOPPING state, Back is inert — we're waiting on the companion.
}

static void noop_click_handler(ClickRecognizerRef r, void *ctx) {
    // Select / Down: inert in both states.
}

static void click_config_provider(void *ctx) {
    window_single_click_subscribe(BUTTON_ID_UP,     up_click_handler);
    window_single_click_subscribe(BUTTON_ID_BACK,   back_click_handler);
    window_single_click_subscribe(BUTTON_ID_SELECT, noop_click_handler);
    window_single_click_subscribe(BUTTON_ID_DOWN,   noop_click_handler);
}

// === Window lifecycle ====================================================

static void window_load(Window *window) {
    Layer *root = window_get_root_layer(window);
    GRect bounds = layer_get_bounds(root);

    // Title is the only on-screen element. Pre-sized to fit the longest
    // possible text ("Couldn't stop.\nUp = retry\nBack = ok" wraps to
    // three lines at GOTHIC_24_BOLD on emery).
    s_title = text_layer_create(GRect(8, 56, bounds.size.w - 16, bounds.size.h - 56));
    text_layer_set_font(s_title, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
    text_layer_set_text_alignment(s_title, GTextAlignmentCenter);
    text_layer_set_background_color(s_title, GColorClear);
    text_layer_set_text_color(s_title, GColorBlack);
    text_layer_set_text(s_title, "Stopping…");
    layer_add_child(root, text_layer_get_layer(s_title));
}

static void window_unload(Window *window) {
    cancel_timeout();
    if (s_title)  { text_layer_destroy(s_title);  s_title  = NULL; }
    if (s_window) { window_destroy(s_window);     s_window = NULL; }
    // Clear the inbox handler so a trailing dispatch (a duplicate ack
    // arriving after we've already transitioned) doesn't run into freed
    // state. run-summary doesn't install one of its own.
    app_message_set_inbox_handler(NULL);
}

// === Public API ==========================================================

void stopping_show(void) {
    s_state = STOPPING;
    s_window = window_create();
    window_set_window_handlers(s_window, (WindowHandlers){
        .load   = window_load,
        .unload = window_unload,
    });
    window_set_click_config_provider(s_window, click_config_provider);

    // Synchronous install — the companion's RUN_STOPPED ack can arrive
    // before the event loop dispatches window_appear, especially on a
    // healthy BT link. Same race the idle screen handles for RUN_STARTED.
    app_message_set_inbox_handler(inbox_handler);
    start_timeout();
    window_stack_push(s_window, true);
}
