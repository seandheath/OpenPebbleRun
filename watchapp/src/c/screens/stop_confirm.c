#include "stop_confirm.h"
#include "active_run.h"
#include "run_summary.h"
#include "../app_message.h"

/*
 * Stop-confirm screen. Spec §4.2.3.
 *
 * Two text layers:
 *   ┌────────────────────────┐
 *   │       Stop run?        │   bold, upper
 *   │                        │
 *   │     Select = Yes       │   regular, lower
 *   │      Back = No         │
 *   └────────────────────────┘
 *
 * Inbox handler is intentionally *not* installed here. active-run's handler
 * stays in place and continues to update its (currently-hidden) text layers
 * while stop-confirm overlays it — so cancelling with Back returns to a
 * live-stats view without a flash of stale data.
 */

#define SCREEN_W 200

static Window    *s_window = NULL;
static TextLayer *s_title;
static TextLayer *s_prompt;

// === Buttons ============================================================

static void select_click_handler(ClickRecognizerRef r, void *ctx) {
    // Send CMD_STOP first so the companion starts its stop-side work
    // (OpenTracksApi.stopRecording, foreground demote, RunSession.clear)
    // while we animate to the summary. The send is fire-and-forget; the
    // companion handles failure paths itself.
    app_message_send_cmd(KEY_CMD_STOP);
    vibes_short_pulse();  // spec §4.4 — single short pulse on stop ack.

    // Replace the window stack [pre_run, active_run, stop_confirm] with
    // [pre_run, run_summary]:
    //   1. Push run_summary on top (animated — looks like a normal forward
    //      transition from the user's POV).
    //   2. Silently remove active_run from underneath it.
    //   3. Silently remove self.
    // After this, pressing Back on the summary lands on pre-run with no
    // intermediate active-run or stop-confirm flash.
    run_summary_show();
    window_stack_remove(active_run_get_window(), false);
    window_stack_remove(s_window, false);
}

static void back_click_handler(ClickRecognizerRef r, void *ctx) {
    // Cancel — pop ourselves, revealing active-run with live stats already
    // up-to-date (its inbox handler kept ticking while we were on top).
    window_stack_pop(true);
}

static void click_config_provider(void *ctx) {
    window_single_click_subscribe(BUTTON_ID_SELECT, select_click_handler);
    window_single_click_subscribe(BUTTON_ID_BACK,   back_click_handler);
    // Up/Down: leave unbound (default = no-op). Avoids any-button-confirms-stop
    // misfires from a sweaty wrist tap.
}

// === Window lifecycle ===================================================

static void window_load(Window *window) {
    Layer *root = window_get_root_layer(window);
    GRect bounds = layer_get_bounds(root);

    s_title = text_layer_create(GRect(0, 40, bounds.size.w, 40));
    text_layer_set_font(s_title, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
    text_layer_set_text_alignment(s_title, GTextAlignmentCenter);
    text_layer_set_background_color(s_title, GColorClear);
    text_layer_set_text_color(s_title, GColorBlack);
    text_layer_set_text(s_title, "Stop run?");
    layer_add_child(root, text_layer_get_layer(s_title));

    s_prompt = text_layer_create(GRect(8, 110, bounds.size.w - 16, bounds.size.h - 110));
    text_layer_set_font(s_prompt, fonts_get_system_font(FONT_KEY_GOTHIC_18));
    text_layer_set_text_alignment(s_prompt, GTextAlignmentCenter);
    text_layer_set_background_color(s_prompt, GColorClear);
    text_layer_set_text_color(s_prompt, GColorBlack);
    text_layer_set_text(s_prompt, "Select = Yes\nBack = No");
    layer_add_child(root, text_layer_get_layer(s_prompt));
}

static void window_unload(Window *window) {
    if (s_title)  { text_layer_destroy(s_title);  s_title  = NULL; }
    if (s_prompt) { text_layer_destroy(s_prompt); s_prompt = NULL; }
    if (s_window) { window_destroy(s_window); s_window = NULL; }
}

// === Public API =========================================================

void stop_confirm_show(void) {
    // Recreate each push — simpler than tracking dirty state, and the window
    // is small enough that the alloc cost is negligible. window_unload
    // destroys both the text layers and s_window in either dismissal path
    // (Select removes self via window_stack_remove; Back pops normally).
    s_window = window_create();
    window_set_window_handlers(s_window, (WindowHandlers){
        .load   = window_load,
        .unload = window_unload,
    });
    window_set_click_config_provider(s_window, click_config_provider);
    window_stack_push(s_window, true);
}
