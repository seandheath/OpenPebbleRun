#include "pre_run.h"
#include "../app_message.h"

/*
 * Pre-run screen state machine. Spec §4.2.1.
 *
 *   IDLE  ──Select─→  STARTING
 *   STARTING ──RUN_STARTED─→  ACTIVE (stub window pushed)
 *   STARTING ──15s timeout─→ ERROR
 *   STARTING ──RUN_FAILED─→  ERROR
 *   ERROR    ──5s timeout─→  IDLE
 *   ERROR    ──any button─→  IDLE   (spec also allows immediate dismiss)
 *
 * Active-run window is intentionally a placeholder at the skeleton stage:
 * spec §14 step 6 builds the real 5-metric layout. Pushing _any_ window proves
 * the state-machine transition works.
 */

typedef enum {
    PRE_RUN_IDLE,
    PRE_RUN_STARTING,
    PRE_RUN_ERROR,
} PreRunState;

static Window *s_window = NULL;
static Window *s_active_stub_window = NULL;
static TextLayer *s_title_layer = NULL;
static TextLayer *s_prompt_layer = NULL;
static AppTimer *s_timeout_timer = NULL;
static PreRunState s_state = PRE_RUN_IDLE;

// Active-run stub window content.
static TextLayer *s_active_stub_text = NULL;

// Stop timeout timer if running.
static void cancel_timeout(void) {
    if (s_timeout_timer) {
        app_timer_cancel(s_timeout_timer);
        s_timeout_timer = NULL;
    }
}

// Render the screen for the current state.
static void render(void) {
    switch (s_state) {
        case PRE_RUN_IDLE:
            text_layer_set_text(s_title_layer, "OpenPebbleRun");
            text_layer_set_text(s_prompt_layer, "Press Select to start");
            break;
        case PRE_RUN_STARTING:
            text_layer_set_text(s_title_layer, "OpenPebbleRun");
            text_layer_set_text(s_prompt_layer, "Starting...");
            break;
        case PRE_RUN_ERROR:
            text_layer_set_text(s_title_layer, "Run start failed");
            text_layer_set_text(s_prompt_layer,
                "Couldn't start. Open companion app on phone.");
            break;
    }
}

static void enter_idle(void) {
    cancel_timeout();
    s_state = PRE_RUN_IDLE;
    render();
}

// Forward declaration: active-run stub window setup.
static void active_stub_push(void);

// ===== Timeout handlers =====

static void error_timeout_cb(void *ctx) {
    s_timeout_timer = NULL;
    if (s_state == PRE_RUN_ERROR) enter_idle();
}

static void enter_error(void) {
    cancel_timeout();
    s_state = PRE_RUN_ERROR;
    render();
    // Spec §8.2: show for 5s then return to pre-run.
    s_timeout_timer = app_timer_register(5000, error_timeout_cb, NULL);
}

static void starting_timeout_cb(void *ctx) {
    s_timeout_timer = NULL;
    if (s_state == PRE_RUN_STARTING) enter_error();
}

static void enter_starting(void) {
    cancel_timeout();
    s_state = PRE_RUN_STARTING;
    render();
    // Spec §4.2.1: 15s timeout before failing.
    s_timeout_timer = app_timer_register(15000, starting_timeout_cb, NULL);
}

// ===== AppMessage inbox =====

static void inbox_handler(DictionaryIterator *iter) {
    if (dict_find(iter, KEY_RUN_STARTED)) {
        if (s_state == PRE_RUN_STARTING) {
            cancel_timeout();
            active_stub_push();
        }
    } else if (dict_find(iter, KEY_RUN_FAILED)) {
        if (s_state == PRE_RUN_STARTING) enter_error();
    }
    // Other keys (metric updates) are ignored at the skeleton stage. Step 6
    // will route them to the active-run window's handler instead.
}

// ===== Button handlers =====

static void select_click_handler(ClickRecognizerRef recognizer, void *context) {
    if (s_state == PRE_RUN_IDLE) {
        if (app_message_send_cmd(KEY_CMD_START)) {
            enter_starting();
        } else {
            enter_error();
        }
    } else if (s_state == PRE_RUN_ERROR) {
        enter_idle();
    }
}

static void back_click_handler(ClickRecognizerRef recognizer, void *context) {
    if (s_state == PRE_RUN_ERROR) {
        enter_idle();
    } else {
        // Default Back behavior in pre-run = pop window = exit app.
        window_stack_pop(true);
    }
}

// Spec §4.2.1: "Any button returns to pre-run" when the error message is shown.
// Up/Down do nothing in IDLE/STARTING states.
static void any_button_click_handler(ClickRecognizerRef recognizer, void *context) {
    if (s_state == PRE_RUN_ERROR) enter_idle();
}

static void click_config_provider(void *context) {
    window_single_click_subscribe(BUTTON_ID_SELECT, select_click_handler);
    window_single_click_subscribe(BUTTON_ID_BACK,   back_click_handler);
    window_single_click_subscribe(BUTTON_ID_UP,     any_button_click_handler);
    window_single_click_subscribe(BUTTON_ID_DOWN,   any_button_click_handler);
}

// ===== Window lifecycle =====

static void window_load(Window *window) {
    Layer *root = window_get_root_layer(window);
    GRect bounds = layer_get_bounds(root);

    // Title centered upper-mid; prompt centered lower-mid. Wraps as needed for
    // the error message (which is long).
    s_title_layer = text_layer_create(GRect(0, 30, bounds.size.w, 40));
    text_layer_set_font(s_title_layer, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
    text_layer_set_text_alignment(s_title_layer, GTextAlignmentCenter);
    text_layer_set_background_color(s_title_layer, GColorClear);
    layer_add_child(root, text_layer_get_layer(s_title_layer));

    s_prompt_layer = text_layer_create(GRect(8, 90, bounds.size.w - 16, bounds.size.h - 90));
    text_layer_set_font(s_prompt_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
    text_layer_set_text_alignment(s_prompt_layer, GTextAlignmentCenter);
    text_layer_set_background_color(s_prompt_layer, GColorClear);
    layer_add_child(root, text_layer_get_layer(s_prompt_layer));

    enter_idle();
}

static void window_unload(Window *window) {
    cancel_timeout();
    if (s_title_layer)  { text_layer_destroy(s_title_layer);  s_title_layer  = NULL; }
    if (s_prompt_layer) { text_layer_destroy(s_prompt_layer); s_prompt_layer = NULL; }
}

// ===== Active-run stub (replaced in step 6) =====

static void active_stub_unload(Window *window) {
    if (s_active_stub_text) {
        text_layer_destroy(s_active_stub_text);
        s_active_stub_text = NULL;
    }
}

static void active_stub_load(Window *window) {
    Layer *root = window_get_root_layer(window);
    GRect bounds = layer_get_bounds(root);
    s_active_stub_text = text_layer_create(GRect(0, bounds.size.h / 2 - 20, bounds.size.w, 40));
    text_layer_set_font(s_active_stub_text, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
    text_layer_set_text_alignment(s_active_stub_text, GTextAlignmentCenter);
    text_layer_set_text(s_active_stub_text, "Active");
    layer_add_child(root, text_layer_get_layer(s_active_stub_text));
}

static void active_stub_push(void) {
    if (!s_active_stub_window) {
        s_active_stub_window = window_create();
        window_set_window_handlers(s_active_stub_window, (WindowHandlers){
            .load = active_stub_load,
            .unload = active_stub_unload,
        });
    }
    window_stack_push(s_active_stub_window, true);
}

// ===== Public API =====

void pre_run_show(void) {
    if (!s_window) {
        s_window = window_create();
        window_set_window_handlers(s_window, (WindowHandlers){
            .load = window_load,
            .unload = window_unload,
        });
        window_set_click_config_provider(s_window, click_config_provider);
    }
    app_message_set_inbox_handler(inbox_handler);
    window_stack_push(s_window, true);
}

void pre_run_hide(void) {
    app_message_set_inbox_handler(NULL);
    if (s_active_stub_window) {
        window_destroy(s_active_stub_window);
        s_active_stub_window = NULL;
    }
    if (s_window) {
        window_destroy(s_window);
        s_window = NULL;
    }
}
