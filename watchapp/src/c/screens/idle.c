#include "idle.h"
#include "active_run.h"
#include "../app_message.h"

/*
 * Idle screen implementation. See idle.h for the screen contract.
 *
 * Once active-run is pushed on top, this screen stays underneath but no
 * longer receives inbox dispatches (active-run installs its own handler);
 * exits go through active-run / run-summary, never back to this screen.
 *
 * The inbox handler is installed synchronously in idle_show so RUN_STARTED
 * messages that land before the event loop dispatches window_appear are
 * not dropped.
 */

static Window    *s_window = NULL;
static TextLayer *s_title  = NULL;
static TextLayer *s_prompt = NULL;

// === Inbox ===============================================================

static void inbox_handler(DictionaryIterator *iter) {
    if (dict_find(iter, KEY_RUN_STARTED)) {
        // Hand off to active-run. It installs its own inbox handler in
        // active_run_show, which replaces ours; we stay on the stack
        // underneath but no longer receive dispatches. When the run ends
        // (run_summary Back) or the user backs out (active_run Back),
        // window_stack_pop_all empties the stack and the app exits — we
        // never re-appear in this session.
        active_run_show();
    }
    // Stray metric keys (KEY_PACE_CURRENT, KEY_DISTANCE, KEY_TIME, ...) can
    // arrive between RUN_STARTED-replay and our handler going inactive —
    // ignore. They land on active-run's handler once we hand off.
}

// === Buttons =============================================================

static void back_click_handler(ClickRecognizerRef r, void *ctx) {
    // pop_all empties the stack → Pebble exits the watchapp.
    window_stack_pop_all(true);
}

static void noop_click_handler(ClickRecognizerRef r, void *ctx) {
    // Select/Up/Down intentionally inert — runs start on the phone.
}

static void click_config_provider(void *ctx) {
    window_single_click_subscribe(BUTTON_ID_BACK,   back_click_handler);
    window_single_click_subscribe(BUTTON_ID_SELECT, noop_click_handler);
    window_single_click_subscribe(BUTTON_ID_UP,     noop_click_handler);
    window_single_click_subscribe(BUTTON_ID_DOWN,   noop_click_handler);
}

// === Window lifecycle ====================================================

static void window_load(Window *window) {
    Layer *root = window_get_root_layer(window);
    GRect bounds = layer_get_bounds(root);

    // Title centered upper-mid. The GOTHIC_28_BOLD glyph fits "OpenPebbleRun"
    // in 200 px wide on emery without truncation.
    s_title = text_layer_create(GRect(0, 50, bounds.size.w, 36));
    text_layer_set_font(s_title, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
    text_layer_set_text_alignment(s_title, GTextAlignmentCenter);
    text_layer_set_background_color(s_title, GColorClear);
    text_layer_set_text_color(s_title, GColorBlack);
    text_layer_set_text(s_title, "OpenPebbleRun");
    layer_add_child(root, text_layer_get_layer(s_title));

    // Prompt wraps to two lines at GOTHIC_24 at 184 px wide.
    s_prompt = text_layer_create(GRect(8, 110, bounds.size.w - 16, bounds.size.h - 110));
    text_layer_set_font(s_prompt, fonts_get_system_font(FONT_KEY_GOTHIC_24));
    text_layer_set_text_alignment(s_prompt, GTextAlignmentCenter);
    text_layer_set_background_color(s_prompt, GColorClear);
    text_layer_set_text_color(s_prompt, GColorBlack);
    text_layer_set_text(s_prompt, "Start a run on your phone");
    layer_add_child(root, text_layer_get_layer(s_prompt));
}

static void window_unload(Window *window) {
    if (s_title)  { text_layer_destroy(s_title);  s_title  = NULL; }
    if (s_prompt) { text_layer_destroy(s_prompt); s_prompt = NULL; }
    // Defensive: clear our handler so a trailing dispatch after unload
    // doesn't reach freed state. active-run's window_unload would also clear
    // if it ran last; either way NULL is the safe final state.
    app_message_set_inbox_handler(NULL);
}

// === Public API ==========================================================

void idle_show(void) {
    if (!s_window) {
        s_window = window_create();
        window_set_window_handlers(s_window, (WindowHandlers){
            .load   = window_load,
            .unload = window_unload,
        });
        window_set_click_config_provider(s_window, click_config_provider);
    }
    // Synchronous install — see file-header comment.
    app_message_set_inbox_handler(inbox_handler);
    window_stack_push(s_window, true);
}

void idle_hide(void) {
    if (s_window) {
        window_destroy(s_window);
        s_window = NULL;
    }
}
