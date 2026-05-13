#include "run_summary.h"
#include "active_run.h"

#include <stdio.h>

/*
 * Run-summary screen. Spec §4.2.4.
 *
 * Layout (200×228 emery):
 *   ┌────────────────────────┐
 *   │      RUN COMPLETE      │   bold title
 *   │                        │
 *   │  DISTANCE   X.XX mi    │
 *   │  TIME       MM:SS      │
 *   │  AVG PACE   M:SS /mi   │
 *   │  AVG HR     ### bpm    │  or "---" if avg_hr == 0
 *   └────────────────────────┘
 *
 * Stats are read once at window_load via active_run_get_stats(). active_run's
 * window has already been removed from the stack by stop_confirm by the time
 * we appear, but its static state (s_elapsed_sec, s_dist_hundredths_mi,
 * s_hr_sum, s_hr_count) is still valid — the binary's BSS lives until app
 * exit, not until window destroy.
 *
 * Buttons (see docs/log.md 2026-05-13 icons entry):
 *   Back → exits the watchapp entirely (the stack at this point is just
 *          [run_summary] — active-run and stop-confirm were removed by
 *          stop_confirm's Up handler; pop_all empties and Pebble returns the
 *          user to the launcher / previously-foregrounded app).
 *   Select / Up / Down → ignored, so a stray button press doesn't yank the
 *          user out of the summary before they've read it.
 *
 * No inbox handler — a stopped run produces no further metrics, and the
 * companion's poll loop has been demoted in PebbleListenerService.handleStop.
 */

#define SCREEN_W 200

static Window    *s_window = NULL;
static TextLayer *s_title;
static TextLayer *s_dist_label,  *s_dist_value;
static TextLayer *s_time_label,  *s_time_value;
static TextLayer *s_pace_label,  *s_pace_value;
static TextLayer *s_hr_label,    *s_hr_value;

// String buffers for the four value cells. Sized to fit the longest expected
// rendering (e.g. "1:23:45" for time, "###" for HR).
static char s_dist_buf[10];
static char s_time_buf[10];
static char s_pace_buf[12];
static char s_hr_buf[8];

// === Formatting helpers (mirror active_run.c) ===========================
//
// Kept private to this TU rather than exposed from active_run.c so the
// summary doesn't depend on active-run's internals beyond the published
// RunStats accessor. The formats are short enough that the duplication is
// cheaper than threading a third public API through active_run.h.

static void format_dist(uint32_t hundredths, char *out, size_t n) {
    snprintf(out, n, "%lu.%02lu",
        (unsigned long)(hundredths / 100),
        (unsigned long)(hundredths % 100));
}

static void format_time(uint32_t sec, char *out, size_t n) {
    unsigned h = sec / 3600;
    unsigned m = (sec / 60) % 60;
    unsigned s = sec % 60;
    if (h > 0) snprintf(out, n, "%u:%02u:%02u", h, m, s);
    else       snprintf(out, n, "%u:%02u", m, s);
}

// Average pace from totals. Cap matches the running-pace cap in spec §5.3
// (3600 sec/mi == 60:00/mi == effectively stopped).
static void format_avg_pace(uint32_t time_sec, uint32_t dist_hundredths_mi,
                            char *out, size_t n) {
    if (dist_hundredths_mi == 0) {
        snprintf(out, n, "--:--");
        return;
    }
    // sec/mi = time_sec / (dist_hundredths / 100) = time_sec * 100 / dist_hundredths.
    uint32_t sec_per_mi = (time_sec * 100) / dist_hundredths_mi;
    if (sec_per_mi >= 3600) {
        snprintf(out, n, "--:--");
        return;
    }
    unsigned m = sec_per_mi / 60;
    unsigned s = sec_per_mi % 60;
    snprintf(out, n, "%u:%02u", m, s);
}

static void format_hr(uint16_t avg_hr, char *out, size_t n) {
    if (avg_hr == 0) snprintf(out, n, "---");
    else             snprintf(out, n, "%u", (unsigned)avg_hr);
}

// === Layer construction =================================================

static TextLayer *make_label(GRect frame, const char *text) {
    TextLayer *t = text_layer_create(frame);
    text_layer_set_font(t, fonts_get_system_font(FONT_KEY_GOTHIC_14));
    text_layer_set_text_alignment(t, GTextAlignmentLeft);
    text_layer_set_background_color(t, GColorClear);
    text_layer_set_text_color(t, GColorBlack);
    text_layer_set_text(t, text);
    return t;
}

static TextLayer *make_value(GRect frame, const char *initial) {
    TextLayer *t = text_layer_create(frame);
    text_layer_set_font(t, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
    text_layer_set_text_alignment(t, GTextAlignmentRight);
    text_layer_set_background_color(t, GColorClear);
    text_layer_set_text_color(t, GColorBlack);
    text_layer_set_text(t, initial);
    return t;
}

// Row height tuned to fit four data rows plus a title in 228 px while keeping
// a comfortable gap from the screen edges. Title at y=8, then four rows
// starting at y=46 with 38 px stride.
#define ROW_STRIDE 38
#define ROW_Y_BASE 46
#define LABEL_X     8
#define LABEL_W   120
#define VALUE_X   100
#define VALUE_W    92

static void window_load(Window *window) {
    Layer *root = window_get_root_layer(window);
    window_set_background_color(window, GColorWhite);

    s_title = text_layer_create(GRect(0, 8, SCREEN_W, 28));
    text_layer_set_font(s_title, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
    text_layer_set_text_alignment(s_title, GTextAlignmentCenter);
    text_layer_set_background_color(s_title, GColorClear);
    text_layer_set_text_color(s_title, GColorBlack);
    text_layer_set_text(s_title, "RUN COMPLETE");
    layer_add_child(root, text_layer_get_layer(s_title));

    // Snapshot stats once. Subsequent reads would return the same values
    // (active-run is no longer accepting inbox messages or HR events).
    RunStats stats;
    active_run_get_stats(&stats);

    format_dist(stats.dist_hundredths_mi, s_dist_buf, sizeof(s_dist_buf));
    format_time(stats.time_sec, s_time_buf, sizeof(s_time_buf));
    format_avg_pace(stats.time_sec, stats.dist_hundredths_mi,
                    s_pace_buf, sizeof(s_pace_buf));
    format_hr(stats.avg_hr, s_hr_buf, sizeof(s_hr_buf));

    // Row 0: DISTANCE
    int y = ROW_Y_BASE;
    s_dist_label = make_label(GRect(LABEL_X, y, LABEL_W, 22), "DISTANCE");
    s_dist_value = make_value(GRect(VALUE_X, y, VALUE_W, 24), s_dist_buf);
    layer_add_child(root, text_layer_get_layer(s_dist_label));
    layer_add_child(root, text_layer_get_layer(s_dist_value));

    // Row 1: TIME
    y += ROW_STRIDE;
    s_time_label = make_label(GRect(LABEL_X, y, LABEL_W, 22), "TIME");
    s_time_value = make_value(GRect(VALUE_X, y, VALUE_W, 24), s_time_buf);
    layer_add_child(root, text_layer_get_layer(s_time_label));
    layer_add_child(root, text_layer_get_layer(s_time_value));

    // Row 2: AVG PACE
    y += ROW_STRIDE;
    s_pace_label = make_label(GRect(LABEL_X, y, LABEL_W, 22), "AVG PACE");
    s_pace_value = make_value(GRect(VALUE_X, y, VALUE_W, 24), s_pace_buf);
    layer_add_child(root, text_layer_get_layer(s_pace_label));
    layer_add_child(root, text_layer_get_layer(s_pace_value));

    // Row 3: AVG HR
    y += ROW_STRIDE;
    s_hr_label = make_label(GRect(LABEL_X, y, LABEL_W, 22), "AVG HR");
    s_hr_value = make_value(GRect(VALUE_X, y, VALUE_W, 24), s_hr_buf);
    layer_add_child(root, text_layer_get_layer(s_hr_label));
    layer_add_child(root, text_layer_get_layer(s_hr_value));
}

static void window_unload(Window *window) {
    text_layer_destroy(s_title);
    text_layer_destroy(s_dist_label); text_layer_destroy(s_dist_value);
    text_layer_destroy(s_time_label); text_layer_destroy(s_time_value);
    text_layer_destroy(s_pace_label); text_layer_destroy(s_pace_value);
    text_layer_destroy(s_hr_label);   text_layer_destroy(s_hr_value);
    window_destroy(s_window);
    s_window = NULL;
}

// === Buttons ============================================================

static void back_click_handler(ClickRecognizerRef r, void *ctx) {
    // pop_all empties the window stack → Pebble exits the watchapp and
    // returns the user to the launcher (or whichever app was foregrounded
    // before ours). With pre-run gone the stack at this point is just
    // [run_summary], so the dismiss is unambiguous.
    window_stack_pop_all(true);
}

static void noop_click_handler(ClickRecognizerRef r, void *ctx) {
    // Select/Up/Down intentionally ignored — see file-header docstring.
}

static void click_config_provider(void *ctx) {
    window_single_click_subscribe(BUTTON_ID_BACK,   back_click_handler);
    window_single_click_subscribe(BUTTON_ID_SELECT, noop_click_handler);
    window_single_click_subscribe(BUTTON_ID_UP,     noop_click_handler);
    window_single_click_subscribe(BUTTON_ID_DOWN,   noop_click_handler);
}

// === Public API =========================================================

void run_summary_show(void) {
    // Always recreate — easier than tracking dirty state across runs. The
    // window has minimal allocation cost and we only ever show it once per
    // app session (the user dismisses it back to pre-run).
    s_window = window_create();
    window_set_window_handlers(s_window, (WindowHandlers){
        .load   = window_load,
        .unload = window_unload,
    });
    window_set_click_config_provider(s_window, click_config_provider);
    window_stack_push(s_window, true);
}
