#include "idle.h"
#include "active_run.h"
#include "starting.h"
#include "icons.h"
#include "../app_message.h"

/*
 * Idle screen implementation. See idle.h for the screen contract.
 *
 * Once active-run is pushed on top, this screen stays underneath but no
 * longer receives inbox dispatches (active-run installs its own handler);
 * exits go through active-run / run-summary, never back to this screen
 * unless the user backs out of the `starting` screen on a failed start.
 *
 * The inbox handler is installed synchronously in idle_show so RUN_STARTED
 * messages that land before the event loop dispatches window_appear are
 * not dropped. starting.c temporarily displaces the handler with its own;
 * its Back-from-error path calls `idle_arm_inbox()` to put us back in
 * charge before popping.
 */

static Window    *s_window     = NULL;
static TextLayer *s_title      = NULL;
static TextLayer *s_prompt     = NULL;
static Layer     *s_play_icon  = NULL;

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

// === Play-icon layer =====================================================

static void play_icon_update_proc(Layer *layer, GContext *ctx) {
    icons_draw_play(ctx, layer_get_bounds(layer));
}

// === Buttons =============================================================

static void back_click_handler(ClickRecognizerRef r, void *ctx) {
    // pop_all empties the stack → Pebble exits the watchapp.
    window_stack_pop_all(true);
}

static void select_click_handler(ClickRecognizerRef r, void *ctx) {
    // Ask the companion to dispatch OpenTracks StartRecording. Vibrate
    // for tactile confirmation that the press registered (mirrors
    // stop-confirm's Up handler), then hand off to the `starting` screen
    // which waits for the companion's RUN_STARTED ack with a 15 s
    // timeout + retry. starting.c installs its own inbox handler so it
    // catches RUN_STARTED ahead of ours; the success path pushes
    // active-run on top of [idle] from there.
    //
    // We do not check companion reachability locally — if the AppMessage
    // can't go out, the starting screen surfaces the timeout error and
    // offers retry, which is the same recovery surface as a companion
    // that received CMD_START but couldn't satisfy the BAL exemption.
    app_message_send_cmd(KEY_CMD_START);
    vibes_short_pulse();
    starting_show();
}

static void noop_click_handler(ClickRecognizerRef r, void *ctx) {
    // Up / Down intentionally inert — Select is the only on-watch start
    // affordance.
}

static void click_config_provider(void *ctx) {
    window_single_click_subscribe(BUTTON_ID_BACK,   back_click_handler);
    window_single_click_subscribe(BUTTON_ID_SELECT, select_click_handler);
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

    // Prompt: leave room on the right for the play icon. The play icon
    // occupies 20 px at x=180, so cap the prompt's horizontal extent at
    // x=176 so the text never collides with it visually even on the
    // longest possible wrap.
    s_prompt = text_layer_create(GRect(8, 110, 168, bounds.size.h - 110));
    text_layer_set_font(s_prompt, fonts_get_system_font(FONT_KEY_GOTHIC_24));
    text_layer_set_text_alignment(s_prompt, GTextAlignmentCenter);
    text_layer_set_background_color(s_prompt, GColorClear);
    text_layer_set_text_color(s_prompt, GColorBlack);
    text_layer_set_text(s_prompt, "Press Select\nto start");
    layer_add_child(root, text_layer_get_layer(s_prompt));

    // Play icon at the Select-button gutter (right edge, vertically
    // centered ≈ y=78 on emery — same x-column as stop-confirm's ✓/✕).
    // Reads as "the Select button on this side is the action".
    s_play_icon = layer_create(GRect(180, 78, 20, 20));
    layer_set_update_proc(s_play_icon, play_icon_update_proc);
    layer_add_child(root, s_play_icon);
}

static void window_unload(Window *window) {
    if (s_title)     { text_layer_destroy(s_title);  s_title     = NULL; }
    if (s_prompt)    { text_layer_destroy(s_prompt); s_prompt    = NULL; }
    if (s_play_icon) { layer_destroy(s_play_icon);   s_play_icon = NULL; }
    // Defensive: clear our handler so a trailing dispatch after unload
    // doesn't reach freed state. active-run's window_unload would also clear
    // if it ran last; either way NULL is the safe final state.
    app_message_set_inbox_handler(NULL);
}

// === Public API ==========================================================

void idle_arm_inbox(void) {
    app_message_set_inbox_handler(inbox_handler);
}

Window *idle_get_window(void) {
    return s_window;
}

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
    idle_arm_inbox();
    window_stack_push(s_window, true);
}

void idle_hide(void) {
    if (s_window) {
        window_destroy(s_window);
        s_window = NULL;
    }
}
