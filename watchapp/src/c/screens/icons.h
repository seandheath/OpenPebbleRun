/*
 * Shared iconographic glyph drawers — used by active-run (stop square) and
 * stop-confirm (check + X) to render small button hints next to the physical
 * Pebble buttons. Hand-drawn via graphics_* primitives so we don't ship PNG
 * resources in package.json — three trivial shapes don't justify the asset
 * pipeline overhead, and keeping the binary resource-free preserves the
 * single-ELF deployment story the project relies on.
 *
 * Each drawer paints inside the caller-supplied GRect. Caller is responsible
 * for wrapping the call in a LayerUpdateProc:
 *
 *     static void my_layer_update(Layer *l, GContext *ctx) {
 *         icons_draw_stop_square(ctx, layer_get_bounds(l));
 *     }
 *
 * Geometry is proportional to `r`, so the same drawer renders cleanly at
 * 16×16 (active-run gutter) or 20×20 (stop-confirm whole-screen).
 *
 * Colors are GColorBlack on whatever background the caller's window uses
 * (we don't set a fill background — Layer transparency keeps the surrounding
 * window background visible). Stroke width 3 px for line glyphs (check, X);
 * AA enabled so diagonals look smooth on emery's color display.
 */

#pragma once

#include <pebble.h>

void icons_draw_stop_square(GContext *ctx, GRect r);
void icons_draw_check(GContext *ctx, GRect r);
void icons_draw_x(GContext *ctx, GRect r);
