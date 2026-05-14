#include "icons.h"

/*
 * Drawer implementations. See icons.h for the contract.
 *
 * All three drawers paint inside the caller's rect using GColorBlack. Line
 * glyphs (check, X) request anti-aliasing — emery is a color display
 * (Basalt+) and `graphics_context_set_antialiased` is a no-op on aplite-style
 * platforms anyway, so this is safe across the SDK's platform matrix even
 * though emery is our only build target today.
 */

#define ICON_STROKE_WIDTH 3

void icons_draw_stop_square(GContext *ctx, GRect r) {
    // Solid filled square. corner_radius=0 + GCornerNone gives a sharp box,
    // which reads as "stop" more clearly than a rounded variant at this size.
    graphics_context_set_fill_color(ctx, GColorBlack);
    graphics_fill_rect(ctx, r, 0, GCornerNone);
}

void icons_draw_check(GContext *ctx, GRect r) {
    // Two-segment checkmark proportional to `r`:
    //   short stroke from (15%, 55%) of the rect down-right to (40%, 80%)
    //   long stroke from there up-right to (90%, 15%)
    // The kink at (40%, 80%) is the bottom point of the ✓.
    graphics_context_set_antialiased(ctx, true);
    graphics_context_set_stroke_color(ctx, GColorBlack);
    graphics_context_set_stroke_width(ctx, ICON_STROKE_WIDTH);

    GPoint p_left   = GPoint(r.origin.x + (r.size.w * 15) / 100,
                             r.origin.y + (r.size.h * 55) / 100);
    GPoint p_bottom = GPoint(r.origin.x + (r.size.w * 40) / 100,
                             r.origin.y + (r.size.h * 80) / 100);
    GPoint p_right  = GPoint(r.origin.x + (r.size.w * 90) / 100,
                             r.origin.y + (r.size.h * 15) / 100);

    graphics_draw_line(ctx, p_left, p_bottom);
    graphics_draw_line(ctx, p_bottom, p_right);
}

void icons_draw_x(GContext *ctx, GRect r) {
    // Two crossing diagonals, inset 3 px from each corner so the stroke
    // doesn't bleed off the rect edges at stroke width 3.
    graphics_context_set_antialiased(ctx, true);
    graphics_context_set_stroke_color(ctx, GColorBlack);
    graphics_context_set_stroke_width(ctx, ICON_STROKE_WIDTH);

    int16_t inset = 3;
    GPoint tl = GPoint(r.origin.x + inset,                 r.origin.y + inset);
    GPoint br = GPoint(r.origin.x + r.size.w - inset - 1,  r.origin.y + r.size.h - inset - 1);
    GPoint tr = GPoint(r.origin.x + r.size.w - inset - 1,  r.origin.y + inset);
    GPoint bl = GPoint(r.origin.x + inset,                 r.origin.y + r.size.h - inset - 1);

    graphics_draw_line(ctx, tl, br);
    graphics_draw_line(ctx, tr, bl);
}

void icons_draw_play(GContext *ctx, GRect r) {
    // Solid right-pointing isoceles triangle: top-left + bottom-left of `r`
    // form the vertical edge, apex is at the midpoint of the right edge.
    // Filled with GColorBlack via gpath_draw_filled — the simplest primitive
    // that handles a triangular fill cleanly across emery's color display.
    // Allocate + destroy per call because the rect can vary between callers
    // (cheap — 3 points, no caching needed for a static icon redrawn once
    // per layer_mark_dirty()).
    GPoint points[3] = {
        { (int16_t)(r.origin.x),                  (int16_t)(r.origin.y) },
        { (int16_t)(r.origin.x),                  (int16_t)(r.origin.y + r.size.h - 1) },
        { (int16_t)(r.origin.x + r.size.w - 1),   (int16_t)(r.origin.y + r.size.h / 2) },
    };
    GPathInfo info = { .num_points = 3, .points = points };
    GPath *path = gpath_create(&info);
    if (!path) return;
    graphics_context_set_antialiased(ctx, true);
    graphics_context_set_fill_color(ctx, GColorBlack);
    gpath_draw_filled(ctx, path);
    gpath_destroy(path);
}
