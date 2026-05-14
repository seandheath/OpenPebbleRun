#include "starting.h"
#include "idle.h"
#include "active_run.h"
#include "../app_message.h"

/*
 * Starting screen implementation. See starting.h for the contract.
 *
 * Mirrors stopping.c almost verbatim — the watch's start flow is the dual
 * of the stop flow now that the CDM bonded-device fast path provides the
 * Background Activity Launch exemption the companion needs to dispatch
 * OpenTracks's StartRecording Intent from a non-foreground service. See
 * docs/log.md (2026-05-14) for the historical context.
 *
 * Timeout is longer than stopping's 10 s because the success criterion
 * here is an end-to-end round trip: CMD_START → companion startActivity
 * → OpenTracks StartRecording onCreate → DashboardActivity onCreate →
 * PebbleMessenger.sendRunStarted → AppMessage delivery to the watch.
 * A cold GPS fix in OpenTracks can add several seconds before the
 * Dashboard callback fires.
 */

#define STARTING_TIMEOUT_MS 15000

typedef enum {
    STARTING,
    STARTING_ERROR,
} StartingState;

static Window       *s_window  = NULL;
static TextLayer    *s_title   = NULL;
static AppTimer     *s_timeout = NULL;
static StartingState s_state   = STARTING;

static void cancel_timeout(void) {
    if (s_timeout) { app_timer_cancel(s_timeout); s_timeout = NULL; }
}

static void promote_to_active_run(void) {
    cancel_timeout();
    // Push active-run on top, then silently remove starting + idle so the
    // active-run flow proceeds with the same window stack shape the
    // companion-initiated start path produces ([idle, active_run] → run
    // proceeds → run_summary's pop_all empties). Removing starting here
    // (instead of waiting for window_unload) keeps the stack clean if a
    // duplicate RUN_STARTED dispatch lands before our window_unload runs.
    active_run_show();
    window_stack_remove(s_window, false);
}

static void timeout_cb(void *ctx) {
    s_timeout = NULL;
    s_state = STARTING_ERROR;
    text_layer_set_text(s_title, "Couldn't start.\nUp = retry\nBack = ok");
}

static void start_timeout(void) {
    cancel_timeout();
    s_timeout = app_timer_register(STARTING_TIMEOUT_MS, timeout_cb, NULL);
}

// === Inbox ===============================================================

static void inbox_handler(DictionaryIterator *iter) {
    if (dict_find(iter, KEY_RUN_STARTED)) {
        promote_to_active_run();
    }
    // Stray metric keys (PACE_CURRENT / TIME / DISTANCE) can race in
    // between CMD_START being processed and the companion's poll loop
    // standing up — ignore. They'll land on active-run's handler once we
    // hand off.
}

// === Buttons =============================================================

static void up_click_handler(ClickRecognizerRef r, void *ctx) {
    if (s_state == STARTING_ERROR) {
        // Retry: re-send CMD_START and re-enter the waiting state. We
        // don't try to differentiate between "companion missed the
        // message" vs. "companion got it but OpenTracks didn't call back"
        // — either way, resending is the only recovery the watch can
        // perform locally.
        app_message_send_cmd(KEY_CMD_START);
        s_state = STARTING;
        text_layer_set_text(s_title, "Starting…");
        start_timeout();
    }
}

static void back_click_handler(ClickRecognizerRef r, void *ctx) {
    if (s_state == STARTING_ERROR) {
        // Pop back to the idle screen. The starting window_unload below
        // clears our inbox handler; idle_arm_inbox re-installs idle's so
        // a later RUN_STARTED (e.g. user gives up and starts from the
        // companion instead) still transitions correctly.
        idle_arm_inbox();
        window_stack_pop(true);
    }
    // In STARTING state, Back is inert — we're waiting on the companion.
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
    // possible text ("Couldn't start.\nUp = retry\nBack = ok" wraps to
    // three lines at GOTHIC_28_BOLD on emery, ≈ 108 px tall total).
    s_title = text_layer_create(GRect(8, 40, bounds.size.w - 16, bounds.size.h - 40));
    text_layer_set_font(s_title, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
    text_layer_set_text_alignment(s_title, GTextAlignmentCenter);
    text_layer_set_background_color(s_title, GColorClear);
    text_layer_set_text_color(s_title, GColorBlack);
    text_layer_set_text(s_title, "Starting…");
    layer_add_child(root, text_layer_get_layer(s_title));
}

static void window_unload(Window *window) {
    cancel_timeout();
    if (s_title)  { text_layer_destroy(s_title);  s_title  = NULL; }
    if (s_window) { window_destroy(s_window);     s_window = NULL; }
    // Clear our inbox handler so a trailing dispatch after we've already
    // transitioned doesn't run into freed state. The Back-from-error
    // path re-arms idle's handler before popping us; the success path
    // hands off to active-run's handler via active_run_show().
    app_message_set_inbox_handler(NULL);
}

// === Public API ==========================================================

void starting_show(void) {
    s_state = STARTING;
    s_window = window_create();
    window_set_window_handlers(s_window, (WindowHandlers){
        .load   = window_load,
        .unload = window_unload,
    });
    window_set_click_config_provider(s_window, click_config_provider);

    // Synchronous install — the companion's RUN_STARTED ack can arrive
    // before the event loop dispatches window_appear, especially on a
    // healthy BT link with a warm OpenTracks/GPS path. Same race the
    // stopping screen handles for RUN_STOPPED.
    app_message_set_inbox_handler(inbox_handler);
    start_timeout();
    window_stack_push(s_window, true);
}
