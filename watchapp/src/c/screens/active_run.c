#include "active_run.h"
#include "stop_confirm.h"
#include "run_summary.h"
#include "icons.h"
#include "../app_message.h"

#include <stdio.h>

/*
 * Active-run screen. Spec §4.2.2.
 *
 * Layout breakdown for emery (200 wide × 228 tall, color):
 *   Row 1 (y=0..88)    HR label + HR value      large    HEIGHT 88
 *   Row 2 (y=88..158)  PACE label + CADENCE     medium   HEIGHT 70
 *   Row 3 (y=158..228) DIST label + TIME        small    HEIGHT 70
 *
 * Within each row, labels render above values; the wide row (HR) is one cell,
 * the other two rows split horizontally at x=100 (50/50).
 */

#define SCREEN_W 200
#define SCREEN_H 228

#define ROW_HR_Y      0
#define ROW_HR_H     88

#define ROW_MID_Y    88
#define ROW_MID_H    70

#define ROW_BOT_Y   158
#define ROW_BOT_H    70

#define COL_LEFT_X    0
#define COL_RIGHT_X 100
#define COL_W       100

// Stale threshold per spec §8.1: dim when no inbox in 30 s.
#define STALE_THRESHOLD_MS 30000
// Periodic tick to check staleness; cheap to run, ~1 Hz.
#define STALE_TICK_MS       1000

// Cadence polling cadence per spec §4.3: read HealthMetricStepCount every
// 5 s, window 15 s. Ring size = 15 s / 5 s = 3 slots — with 3 slots
// spaced 5 s apart, the oldest slot (next-write slot, in circular order)
// is exactly 15 s older than the most recent write. SPM =
// (delta_steps / 15) × 60 = delta_steps × 4.
#define CADENCE_TICK_MS       5000
#define CADENCE_RING_SIZE     3
// Floor on per-window step delta. Pebble's HealthMetricStepCount is a
// monotonically-increasing daily counter that resets at local midnight; a
// run that straddles midnight would observe a negative delta and we clamp
// it to 0 (visible as a single zero-SPM tick before the post-midnight
// samples populate the ring with a fresh baseline). No upper clamp —
// extreme readings are usually real (interval workouts can briefly hit
// 200+ SPM) and capping them would hide sensor problems.
#define CADENCE_MIN_DELTA     0

static Window    *s_window = NULL;

// Layers: label (small caps) + value (large numeric) per cell.
static TextLayer *s_hr_label,  *s_hr_value;
static TextLayer *s_pace_label, *s_pace_value;
static TextLayer *s_cad_label,  *s_cad_value;
static TextLayer *s_dist_label, *s_dist_value;
static TextLayer *s_time_label, *s_time_value;

// Small filled-square button hint at the right edge of the screen, vertically
// aligned with the physical Down button (≈ y=188 center on emery). Drawn via
// icons_draw_stop_square in stop_icon_update_proc. The TIME column (row 3
// right) is shrunk from 100 to 82 px wide to leave room for this 16-px gutter.
static Layer *s_stop_icon = NULL;

// Value buffers. AppMessage handler writes into these and refreshes the
// layer. Sized to gcc's worst-case format-truncation analysis (it can't see
// the runtime ranges of bpm / seconds / hundredths-mile and assumes full
// uint range): %lu can emit up to 10 digits + null, etc. Real outputs stay
// well below these sizes (e.g. "172" for HR, "0:23:45" for time), but the
// extra slack silences -Wformat-truncation. RAM cost is negligible (~14B).
static char s_hr_buf[12];     // "###" or "---" (worst case "4294967295")
static char s_pace_buf[12];   // "M:SS" — gcc reasons through the <3600 cap
static char s_dist_buf[16];   // "X.XX" worst case "4294967295.99"
static char s_time_buf[16];   // "MM:SS" / "H:MM:SS" worst case 3×10-digit
static char s_cad_buf[12];    // "###" SPM or "---" during ring fill

// Staleness tracking. last_inbox_ms is updated by inbox_handler; the timer
// callback compares against `app_now_ms()` (we use a monotonic counter via
// time_ms() — it's wall-clock but the OS doesn't jump it during a run).
static uint64_t s_last_inbox_ms = 0;
static bool     s_dimmed = false;
static AppTimer *s_stale_timer = NULL;

// Locally-ticked elapsed time. Companion's Track.movingtime updates sparsely
// (OpenTracks's dashboard ContentObserver only fires on track-row changes —
// often 25+ seconds apart), so the watch drives its own 1 Hz clock and snaps
// to the companion's authoritative value on every KEY_TIME receipt. This
// matches what OpenTracks's own TrackRecordingActivity does internally.
static uint32_t s_elapsed_sec = 0;

// Raw most-recent distance from KEY_DISTANCE, preserved alongside the
// formatted s_dist_buf for the run-summary screen to read post-stop.
static uint32_t s_dist_hundredths_mi = 0;

// Running mean of internal-HRM samples. Accumulated in health_event_handler
// for the duration of the active-run window; reported via active_run_get_stats
// to the summary screen. Zero-valued samples (sensor warming up, not worn)
// are skipped so they don't drag the average down.
static uint64_t s_hr_sum   = 0;
static uint32_t s_hr_count = 0;

// Cadence ring: 4 step-count samples spaced 5 s apart. `s_step_idx` is the
// next slot to write; the slot at that index holds the OLDEST sample (the
// one we're about to overwrite), which is exactly the 15 s-ago reading
// we want for the delta. `s_step_filled` saturates at CADENCE_RING_SIZE
// once we've collected enough samples for a valid window — until then we
// render "---" instead of a misleadingly small SPM derived from a partial
// window.
static int32_t  s_step_ring[CADENCE_RING_SIZE];
static uint8_t  s_step_idx     = 0;
static uint8_t  s_step_filled  = 0;
static AppTimer *s_cad_timer   = NULL;

// ===== Heart-rate sampling =====
//
// Spec §4.3: internal HRM at 1 Hz, subscribe to HealthEventHeartRateUpdate,
// display the latest value. Sampling lifecycle is bound to the active-run
// screen (rather than the whole app lifetime) to avoid battery drain on the
// pre-run screen where HR isn't displayed. main.c also calls
// health_service_set_heart_rate_sample_period(0) on app exit defensively
// (idempotent).
static void render_hr(HealthValue bpm) {
    if (bpm <= 0) {
        snprintf(s_hr_buf, sizeof(s_hr_buf), "---");
    } else {
        snprintf(s_hr_buf, sizeof(s_hr_buf), "%lu", (unsigned long)bpm);
    }
    text_layer_set_text(s_hr_value, s_hr_buf);
}

static void health_event_handler(HealthEventType event, void *context) {
    if (event != HealthEventHeartRateUpdate) return;
    HealthValue bpm = health_service_peek_current_value(HealthMetricHeartRateBPM);
    render_hr(bpm);
    // Accumulate for end-of-run average. Skip zeros so a cold/unworn sensor
    // doesn't pull the mean toward 0 before real data arrives.
    if (bpm > 0) {
        s_hr_sum += (uint64_t)bpm;
        s_hr_count += 1;
    }
}

// ===== Formatting helpers =====

// Pace seconds-per-mile → "M:SS" or "MM:SS". Spec §5.3: capped at 3600 (60:00).
static void format_pace(uint16_t sec_per_mi, char *out, size_t n) {
    if (sec_per_mi == 0 || sec_per_mi >= 3600) {
        snprintf(out, n, "--:--");
        return;
    }
    unsigned m = sec_per_mi / 60;
    unsigned s = sec_per_mi % 60;
    snprintf(out, n, "%u:%02u", m, s);
}

// Distance hundredths-mile → "0.00", "10.42", etc.
static void format_dist(uint32_t hundredths, char *out, size_t n) {
    snprintf(out, n, "%lu.%02lu",
        (unsigned long)(hundredths / 100),
        (unsigned long)(hundredths % 100));
}

// Time seconds → "MM:SS" or "H:MM:SS" past one hour.
static void format_time(uint32_t sec, char *out, size_t n) {
    unsigned h = sec / 3600;
    unsigned m = (sec / 60) % 60;
    unsigned s = sec % 60;
    if (h > 0) snprintf(out, n, "%u:%02u:%02u", h, m, s);
    else       snprintf(out, n, "%u:%02u", m, s);
}

// ===== Dimming =====

static void set_color(TextLayer *t, GColor c) {
    text_layer_set_text_color(t, c);
}

static void apply_color(bool dim) {
    GColor c = dim ? GColorLightGray : GColorBlack;
    set_color(s_hr_label,    c); set_color(s_hr_value,   c);
    set_color(s_pace_label,  c); set_color(s_pace_value, c);
    set_color(s_cad_label,   c); set_color(s_cad_value,  c);
    set_color(s_dist_label,  c); set_color(s_dist_value, c);
    set_color(s_time_label,  c); set_color(s_time_value, c);
}

static uint64_t now_ms(void) {
    time_t s; uint16_t ms;
    time_ms(&s, &ms);
    return ((uint64_t)s) * 1000ull + ms;
}

static void stale_tick_cb(void *ctx);

static void schedule_stale_tick(void) {
    s_stale_timer = app_timer_register(STALE_TICK_MS, stale_tick_cb, NULL);
}

static void stale_tick_cb(void *ctx) {
    s_stale_timer = NULL;
    uint64_t now = now_ms();
    if (s_last_inbox_ms > 0 && (now - s_last_inbox_ms) >= STALE_THRESHOLD_MS) {
        if (!s_dimmed) { s_dimmed = true; apply_color(true); }
    } else {
        if (s_dimmed)  { s_dimmed = false; apply_color(false); }
    }

    // Local 1 Hz time advance. Independent of phone reachability — we keep
    // counting and let the companion correct us when its next KEY_TIME lands.
    s_elapsed_sec += 1;
    format_time(s_elapsed_sec, s_time_buf, sizeof(s_time_buf));
    text_layer_set_text(s_time_value, s_time_buf);

    schedule_stale_tick();
}

// ===== Cadence sampling =====
//
// Spec §4.3: poll HealthMetricStepCount every 5 s, derive SPM over a 15 s
// rolling window, render locally. The companion is intentionally not in
// the loop here — cadence updates need a tighter latency than the
// ContentObserver-driven metric pipe, and step-count delivery doesn't
// benefit from going phone-side at all.
//
// Ring construction: slot at s_step_idx is the next write target, which
// (once the ring has been around at least once) holds the sample from
// 15 s ago. So we read-then-write at that slot: pull the 15 s-ago
// reading, push today's current count, advance. No separate "tail"
// pointer.

static void cadence_tick_cb(void *ctx);

static void schedule_cadence_tick(void) {
    s_cad_timer = app_timer_register(CADENCE_TICK_MS, cadence_tick_cb, NULL);
}

static void render_cadence(int32_t spm) {
    if (spm < 0) {
        snprintf(s_cad_buf, sizeof(s_cad_buf), "---");
    } else {
        snprintf(s_cad_buf, sizeof(s_cad_buf), "%ld", (long)spm);
    }
    text_layer_set_text(s_cad_value, s_cad_buf);
}

static void cadence_tick_cb(void *ctx) {
    s_cad_timer = NULL;

    // peek_current_value returns the cumulative daily step count. Safe to
    // call even if the user hasn't taken any steps today (it returns 0)
    // and even immediately after a midnight rollover (it returns the
    // post-rollover count, which is small — handled by the clamp below).
    int32_t steps_now = (int32_t)health_service_peek_current_value(
        HealthMetricStepCount);

    int32_t spm = -1;  // sentinel → render "---"
    int32_t steps_15s_ago = -1;
    if (s_step_filled >= CADENCE_RING_SIZE) {
        steps_15s_ago = s_step_ring[s_step_idx];
        int32_t delta = steps_now - steps_15s_ago;
        if (delta < CADENCE_MIN_DELTA) delta = CADENCE_MIN_DELTA;
        spm = delta * 4;  // delta / 15 s × 60 s/min
    }

    // Diagnostic for the 2026-05-16 cadence-stays-at-zero report. The ring
    // arithmetic above is correct on its own terms (verified by walking
    // through tick-by-tick); the suspect is the data source. Logging
    // steps_now / oldest / filled lets us tell from `pebble logs` whether
    // HealthMetricStepCount is updating at all, updating in batches, or
    // simply unreadable on this hardware. One line per 5 s tick — cheap.
    APP_LOG(APP_LOG_LEVEL_DEBUG,
        "cadence tick: steps_now=%ld  oldest=%ld  filled=%u  spm=%ld",
        (long)steps_now, (long)steps_15s_ago,
        (unsigned)s_step_filled, (long)spm);

    s_step_ring[s_step_idx] = steps_now;
    s_step_idx = (s_step_idx + 1) % CADENCE_RING_SIZE;
    if (s_step_filled < CADENCE_RING_SIZE) s_step_filled++;

    render_cadence(spm);

    schedule_cadence_tick();
}

// ===== AppMessage inbox handler =====

static void inbox_handler(DictionaryIterator *iter) {
    // KEY_RUN_STOPPED arrives when the user stopped the run from the
    // companion (Home → Stop Run). Push run-summary on top and remove
    // ourselves; Back from the summary then exits the watchapp cleanly.
    // For watch-initiated stops the stopping screen catches RUN_STOPPED
    // first (it's the topmost window when CMD_STOP was sent), so this
    // path covers the companion-initiated case specifically.
    if (dict_find(iter, KEY_RUN_STOPPED)) {
        run_summary_show();
        window_stack_remove(s_window, false);
        return;
    }

    bool got_metric = false;

    Tuple *t = dict_find(iter, KEY_PACE_CURRENT);
    if (t) {
        format_pace((uint16_t)t->value->uint16, s_pace_buf, sizeof(s_pace_buf));
        text_layer_set_text(s_pace_value, s_pace_buf);
        got_metric = true;
    }
    t = dict_find(iter, KEY_DISTANCE);
    if (t) {
        s_dist_hundredths_mi = t->value->uint32;
        format_dist(s_dist_hundredths_mi, s_dist_buf, sizeof(s_dist_buf));
        text_layer_set_text(s_dist_value, s_dist_buf);
        got_metric = true;
    }
    t = dict_find(iter, KEY_TIME);
    if (t) {
        // Snap our local 1 Hz counter to the companion's authoritative value.
        // Between KEY_TIME messages, stale_tick_cb advances s_elapsed_sec by 1
        // each second so the field never freezes (companion updates can be 25+ s
        // apart depending on OpenTracks's notifyChange cadence).
        s_elapsed_sec = t->value->uint32;
        format_time(s_elapsed_sec, s_time_buf, sizeof(s_time_buf));
        text_layer_set_text(s_time_value, s_time_buf);
        got_metric = true;
    }
    if (got_metric) {
        s_last_inbox_ms = now_ms();
        if (s_dimmed) { s_dimmed = false; apply_color(false); }
    }
}

// ===== Buttons =====
//
// Spec §4.2.2:
//   Back   → exit watchapp; the run keeps recording in the companion.
//            Re-opening replays RUN_STARTED via the companion's onAppOpened
//            while RunSession.active is true.
//   Down   → open stop-confirm (spec §4.2.3). A small black square hint is
//            painted at the right edge of the screen next to the physical
//            Down button so the affordance is visible at a glance.
//   Select / Up → no-op.

static void back_click_handler(ClickRecognizerRef recognizer, void *ctx) {
    // pop_all empties the window stack; Pebble exits the app when the stack
    // becomes empty. Notably we do NOT send CMD_STOP — the run is meant to
    // continue recording in the companion while the user has the watchapp
    // dismissed.
    window_stack_pop_all(true);
}

static void down_click_handler(ClickRecognizerRef recognizer, void *ctx) {
    stop_confirm_show();
}

static void noop_click_handler(ClickRecognizerRef recognizer, void *ctx) {
    // Select/Up intentionally unused — no pause feature (spec §11) and we
    // don't want sweaty accidental presses to end up load-bearing.
}

static void click_config_provider(void *ctx) {
    window_single_click_subscribe(BUTTON_ID_BACK,   back_click_handler);
    window_single_click_subscribe(BUTTON_ID_DOWN,   down_click_handler);
    window_single_click_subscribe(BUTTON_ID_SELECT, noop_click_handler);
    window_single_click_subscribe(BUTTON_ID_UP,     noop_click_handler);
}

// ===== Stop-icon hint =====

static void stop_icon_update_proc(Layer *layer, GContext *ctx) {
    icons_draw_stop_square(ctx, layer_get_bounds(layer));
}

// ===== Layer construction =====

static TextLayer *make_label(GRect frame, const char *text) {
    TextLayer *t = text_layer_create(frame);
    text_layer_set_font(t, fonts_get_system_font(FONT_KEY_GOTHIC_18));
    text_layer_set_text_alignment(t, GTextAlignmentCenter);
    text_layer_set_background_color(t, GColorClear);
    text_layer_set_text_color(t, GColorBlack);
    text_layer_set_text(t, text);
    return t;
}

static TextLayer *make_value(GRect frame, const char *font_key, const char *initial) {
    TextLayer *t = text_layer_create(frame);
    text_layer_set_font(t, fonts_get_system_font(font_key));
    text_layer_set_text_alignment(t, GTextAlignmentCenter);
    text_layer_set_background_color(t, GColorClear);
    text_layer_set_text_color(t, GColorBlack);
    text_layer_set_text(t, initial);
    return t;
}

static void window_load(Window *window) {
    Layer *root = window_get_root_layer(window);
    window_set_background_color(window, GColorWhite);

    // === Row 1: HR (large) ===
    s_hr_label = make_label(
        GRect(0, ROW_HR_Y + 4, SCREEN_W, 22),
        "HEART RATE");
    s_hr_value = make_value(
        GRect(0, ROW_HR_Y + 26, SCREEN_W, 56),
        FONT_KEY_BITHAM_42_BOLD, "---");
    layer_add_child(root, text_layer_get_layer(s_hr_label));
    layer_add_child(root, text_layer_get_layer(s_hr_value));

    // === Row 2: PACE | CADENCE (medium) ===
    s_pace_label = make_label(
        GRect(COL_LEFT_X, ROW_MID_Y + 2, COL_W, 22),
        "PACE");
    s_pace_value = make_value(
        GRect(COL_LEFT_X, ROW_MID_Y + 24, COL_W, 44),
        FONT_KEY_BITHAM_30_BLACK, "--:--");
    s_cad_label  = make_label(
        GRect(COL_RIGHT_X, ROW_MID_Y + 2, COL_W, 22),
        "CADENCE");
    s_cad_value  = make_value(
        GRect(COL_RIGHT_X, ROW_MID_Y + 24, COL_W, 44),
        FONT_KEY_BITHAM_30_BLACK, "---");
    layer_add_child(root, text_layer_get_layer(s_pace_label));
    layer_add_child(root, text_layer_get_layer(s_pace_value));
    layer_add_child(root, text_layer_get_layer(s_cad_label));
    layer_add_child(root, text_layer_get_layer(s_cad_value));

    // === Row 3: DIST | TIME (small) ===
    // TIME column is shrunk from width 100 → 82 to leave an 18 px gutter at
    // the right edge for the stop-icon hint. Time text remains centered in
    // the narrower cell; "1:23:45" still fits at FONT_KEY_GOTHIC_24_BOLD.
    s_dist_label = make_label(
        GRect(COL_LEFT_X, ROW_BOT_Y + 2, COL_W, 22),
        "DIST mi");
    s_dist_value = make_value(
        GRect(COL_LEFT_X, ROW_BOT_Y + 24, COL_W, 40),
        FONT_KEY_GOTHIC_28_BOLD, "0.00");
    s_time_label = make_label(
        GRect(COL_RIGHT_X, ROW_BOT_Y + 2, 82, 22),
        "TIME");
    s_time_value = make_value(
        GRect(COL_RIGHT_X, ROW_BOT_Y + 24, 82, 40),
        FONT_KEY_GOTHIC_28_BOLD, "0:00");
    layer_add_child(root, text_layer_get_layer(s_dist_label));
    layer_add_child(root, text_layer_get_layer(s_dist_value));
    layer_add_child(root, text_layer_get_layer(s_time_label));
    layer_add_child(root, text_layer_get_layer(s_time_value));

    // Stop-icon hint at the right edge, aligned with the physical Down button.
    // 16×16 sits in the 18-px gutter freed up above; (184, 180) puts it
    // approximately at the Down button's vertical center (≈ y=188 on emery).
    s_stop_icon = layer_create(GRect(184, 180, 16, 16));
    layer_set_update_proc(s_stop_icon, stop_icon_update_proc);
    layer_add_child(root, s_stop_icon);

    // Cadence units shown inline in the value layer is wasteful; keep label
    // for the unit-bearing rows (DIST mi, CADENCE), value layer renders the
    // number plus an explicit unit suffix for "/mi" pace formatting elsewhere.
    // Skipping additional layers to keep the layout count manageable on emery.

    s_last_inbox_ms = 0;
    s_dimmed = false;
    s_elapsed_sec = 0;
    // Reset summary-screen accumulators so a fresh run doesn't inherit
    // distance/HR from a prior run that didn't reach window_unload.
    s_dist_hundredths_mi = 0;
    s_hr_sum = 0;
    s_hr_count = 0;

    // Cadence ring reset + seed. Seeding at t=0 makes the first valid
    // SPM appear at t≈15 s (after 3 ticks bring s_step_filled to 3),
    // not t≈20 s. We bump s_step_idx past the seed slot so the first
    // tick writes to slot 1 and the seed survives long enough to feed
    // the t=15 s delta computation as ring[s_step_idx=0].
    //
    // Probe the metric's accessibility up front. If Pebble Health is
    // disabled in the user's settings, or the platform doesn't expose
    // StepCount, peek_current_value silently returns 0 — which makes
    // every delta 0 and SPM render as a constant "0" for the entire run
    // (reported 2026-05-16). Logging the mask lets us tell from
    // `pebble logs` whether the source is reachable before we go looking
    // at the ring arithmetic.
    HealthServiceAccessibilityMask cad_access =
        health_service_metric_accessible(
            HealthMetricStepCount, time_start_of_today(), time(NULL));
    APP_LOG(APP_LOG_LEVEL_INFO,
        "cadence: HealthMetricStepCount accessibility mask=0x%lx "
        "(Available=0x%x)",
        (unsigned long)cad_access,
        HealthServiceAccessibilityMaskAvailable);

    s_step_ring[0] = (int32_t)health_service_peek_current_value(
        HealthMetricStepCount);
    s_step_idx = 1;
    s_step_filled = 1;
    APP_LOG(APP_LOG_LEVEL_INFO, "cadence: seed steps=%ld",
        (long)s_step_ring[0]);
    // Render the initial "---" eagerly so the layer text matches our
    // state (the value layer's window_load initializer also sets "---",
    // but a duplicate call here keeps the load-then-reshow path coherent
    // if active_run_show is ever called on an already-cached window).
    render_cadence(-1);

    schedule_stale_tick();
    schedule_cadence_tick();

    // Enable internal HRM at 0.2 Hz (one sample every 5 s) and subscribe to
    // updates. The 5 s period matches the companion's OpenTracks poll cadence,
    // so all of the active-run display metrics refresh on the same beat. Render
    // whatever value is already cached (often non-zero if the user's worn the
    // watch for a while) so the field doesn't sit on "---" waiting for the
    // first HealthEventHeartRateUpdate.
    health_service_set_heart_rate_sample_period(5);
    health_service_events_subscribe(health_event_handler, NULL);
    render_hr(health_service_peek_current_value(HealthMetricHeartRateBPM));
}

static void window_unload(Window *window) {
    // Stop HR sampling first so the sensor doesn't keep draining after we exit.
    // main.c's deinit also calls set_period(0); duplicate is harmless.
    health_service_events_unsubscribe();
    health_service_set_heart_rate_sample_period(0);

    if (s_stale_timer) { app_timer_cancel(s_stale_timer); s_stale_timer = NULL; }
    if (s_cad_timer)   { app_timer_cancel(s_cad_timer);   s_cad_timer   = NULL; }

    text_layer_destroy(s_hr_label);   text_layer_destroy(s_hr_value);
    text_layer_destroy(s_pace_label); text_layer_destroy(s_pace_value);
    text_layer_destroy(s_cad_label);  text_layer_destroy(s_cad_value);
    text_layer_destroy(s_dist_label); text_layer_destroy(s_dist_value);
    text_layer_destroy(s_time_label); text_layer_destroy(s_time_value);

    if (s_stop_icon) { layer_destroy(s_stop_icon); s_stop_icon = NULL; }

    // Clear our inbox handler so a lingering window pop doesn't dispatch
    // into freed state.
    app_message_set_inbox_handler(NULL);
}

// ===== Public API =====

void active_run_show(void) {
    if (!s_window) {
        s_window = window_create();
        window_set_window_handlers(s_window, (WindowHandlers){
            .load   = window_load,
            .unload = window_unload,
        });
        window_set_click_config_provider(s_window, click_config_provider);
    }
    app_message_set_inbox_handler(inbox_handler);
    window_stack_push(s_window, true);
}

void active_run_hide(void) {
    if (s_window) {
        window_destroy(s_window);
        s_window = NULL;
    }
}

Window *active_run_get_window(void) {
    return s_window;
}

void active_run_get_stats(RunStats *out) {
    // Safe to call even after the window has been removed from the stack —
    // the file-scope statics survive until app exit. The summary screen reads
    // these once in its window_load.
    out->time_sec = s_elapsed_sec;
    out->dist_hundredths_mi = s_dist_hundredths_mi;
    out->avg_hr = (s_hr_count > 0)
        ? (uint16_t)(s_hr_sum / s_hr_count)
        : 0;
}
