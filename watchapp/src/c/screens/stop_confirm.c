#include "stop_confirm.h"
#include "active_run.h"
#include "run_summary.h"
#include "icons.h"
#include "../app_message.h"

/*
 * Stop-confirm screen. Spec §4.2.3.
 *
 *   ┌────────────────────────┐
 *   │                     ✓  │   check icon next to physical Up button
 *   │                        │
 *   │      Stop run?         │   bold centered title
 *   │                        │
 *   │                     ✕  │   X icon next to physical Down button
 *   └────────────────────────┘
 *
 * Button binding (spec §4.2.3, revised — see docs/log.md 2026-05-13 icons):
 *   Up     → confirm: send CMD_STOP, vibrate, transition to run-summary.
 *   Down   → cancel: pop back to active-run. Combined with active-run's
 *            Down=open-stop-confirm, this gives the user-visible invariant
 *            "Down twice returns you to the live run".
 *   Back   → cancel (mirrors Down) — Pebble convention is Back=go-back.
 *   Select → no-op.
 *
 * Inbox handler is intentionally *not* installed here. active-run's handler
 * stays in place and continues to update its (currently-hidden) text layers
 * while stop-confirm overlays it — so cancelling with Down/Back returns to a
 * live-stats view without a flash of stale data.
 */

#define SCREEN_W 200

static Window    *s_window = NULL;
static TextLayer *s_title  = NULL;
static Layer     *s_check_icon = NULL;
static Layer     *s_x_icon     = NULL;

// === Icon update procs ==================================================

static void check_icon_update_proc(Layer *layer, GContext *ctx) {
    icons_draw_check(ctx, layer_get_bounds(layer));
}

static void x_icon_update_proc(Layer *layer, GContext *ctx) {
    icons_draw_x(ctx, layer_get_bounds(layer));
}

// === Buttons ============================================================

static void up_click_handler(ClickRecognizerRef r, void *ctx) {
    // Send CMD_STOP first so the companion starts its stop-side work
    // (OpenTracksApi.stopRecording, foreground demote, RunSession.clear)
    // while we animate to the summary. Fire-and-forget; companion handles
    // failure paths.
    app_message_send_cmd(KEY_CMD_STOP);
    vibes_short_pulse();  // spec §4.4 — single short pulse on stop ack.

    // Replace the window stack [active_run, stop_confirm] with [run_summary]:
    //   1. Push run_summary on top (animated forward transition).
    //   2. Silently remove active_run from underneath it — fires its
    //      window_unload, which stops HR sampling (battery) and clears the
    //      inbox handler so run-summary isn't disturbed by trailing metrics.
    //   3. Silently remove self.
    // After this, pressing Back on the summary lands on an empty stack and
    // the watchapp exits cleanly (Pebble default).
    run_summary_show();
    window_stack_remove(active_run_get_window(), false);
    window_stack_remove(s_window, false);
}

static void cancel_click_handler(ClickRecognizerRef r, void *ctx) {
    // Pop ourselves, revealing active-run with stats already up-to-date
    // (its inbox handler kept ticking while we were on top).
    window_stack_pop(true);
}

static void noop_click_handler(ClickRecognizerRef r, void *ctx) {
    // Select intentionally unbound: stop is gated by the explicit Up=✓
    // affordance, and a stray Select shouldn't end the run.
}

static void click_config_provider(void *ctx) {
    window_single_click_subscribe(BUTTON_ID_UP,     up_click_handler);
    window_single_click_subscribe(BUTTON_ID_DOWN,   cancel_click_handler);
    window_single_click_subscribe(BUTTON_ID_BACK,   cancel_click_handler);
    window_single_click_subscribe(BUTTON_ID_SELECT, noop_click_handler);
}

// === Window lifecycle ===================================================

static void window_load(Window *window) {
    Layer *root = window_get_root_layer(window);
    GRect bounds = layer_get_bounds(root);

    // Title centered vertically between the two icons (Up ≈ y=50, Down ≈ y=190).
    s_title = text_layer_create(GRect(0, 88, bounds.size.w, 40));
    text_layer_set_font(s_title, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
    text_layer_set_text_alignment(s_title, GTextAlignmentCenter);
    text_layer_set_background_color(s_title, GColorClear);
    text_layer_set_text_color(s_title, GColorBlack);
    text_layer_set_text(s_title, "Stop run?");
    layer_add_child(root, text_layer_get_layer(s_title));

    // Check icon at top-right, aligned with the physical Up button (≈ y=50).
    s_check_icon = layer_create(GRect(180, 40, 20, 20));
    layer_set_update_proc(s_check_icon, check_icon_update_proc);
    layer_add_child(root, s_check_icon);

    // X icon at bottom-right, aligned with the physical Down button (≈ y=190).
    s_x_icon = layer_create(GRect(180, 180, 20, 20));
    layer_set_update_proc(s_x_icon, x_icon_update_proc);
    layer_add_child(root, s_x_icon);
}

static void window_unload(Window *window) {
    if (s_title)      { text_layer_destroy(s_title);   s_title      = NULL; }
    if (s_check_icon) { layer_destroy(s_check_icon);   s_check_icon = NULL; }
    if (s_x_icon)     { layer_destroy(s_x_icon);       s_x_icon     = NULL; }
    if (s_window)     { window_destroy(s_window);      s_window     = NULL; }
}

// === Public API =========================================================

void stop_confirm_show(void) {
    // Recreate each push — simpler than tracking dirty state, and the window
    // is small enough that the alloc cost is negligible. window_unload
    // destroys both the layers and s_window in either dismissal path (Up
    // removes self via window_stack_remove; Down/Back pop normally).
    s_window = window_create();
    window_set_window_handlers(s_window, (WindowHandlers){
        .load   = window_load,
        .unload = window_unload,
    });
    window_set_click_config_provider(s_window, click_config_provider);
    window_stack_push(s_window, true);
}
