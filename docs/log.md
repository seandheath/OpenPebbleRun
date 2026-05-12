# OpenPebbleRun — Decision Log

## 2026-05-12 — Monorepo layout

**Decision:** Develop both components in a single git repo at `/pebblerun` with `watchapp/` and `companion/` subdirs.
**Rationale:** Atomic commits across the watch/companion AppMessage protocol boundary (spec §7) are the dominant source of cross-cutting change. One flake, one decision log, one Makefile is simpler than mirroring boilerplate across two repos. Splittable later via `git filter-repo` if/when distribution requires it.
**Alternatives considered:** Two sibling repos matching spec §10.4 published names (`pebble-opentracks-watchapp`, `pebble-opentracks-companion`). Rejected because IzzyOnDroid, F-Droid (via `subdir:` in metadata), and Pebble Appstore all accept monorepo source layouts.

## 2026-05-12 — Skeleton-first implementation scope

**Decision:** First implementation pass covers spec §14 steps 1–4 only: companion skeleton + variant detection, Public API + Dashboard wiring, derived metrics (computed and logged, not transmitted), watchapp skeleton + pre-run screen with AppMessage send/receive. Steps 5–12 deferred to follow-up plans.
**Rationale:** The watch ↔ companion protocol can't be exercised end-to-end until both sides reach AppMessage capability simultaneously (step 5). Splitting the skeleton from end-to-end wiring keeps each plan reviewable in one sitting and lets manual validation happen at meaningful boundaries.
**Alternatives considered:** Single mega-plan covering all 12 steps. Rejected because each phase needs manual hardware validation (PT2 + Android device + GPS) before merging per global CLAUDE.md.

## 2026-05-12 — Version starts at v0.1.0

**Decision:** Both repos' version begins at `0.1.0`. Spec header rewritten from "v2.0 (simplified)" to "v0.1.0".
**Rationale:** Spec §13 already mandates semver and v1.0.0 as the v1 release target. Pre-1.0 numbering signals the AppMessage protocol, Dashboard schema reads, and PebbleKitAndroid2 binding (still alpha per §11) are all unstable. The "v2.0" header was a draft artifact, not a release.
**Alternatives considered:** Inherit v2.0 from the spec draft. Rejected — implies a v1.0 exists somewhere that downstream packagers would hunt for.

## 2026-05-12 — Dashboard URI delivery assumption

**Decision:** `DashboardActivity` reads the Track URI from `intent.data` and the
TrackPoints URI from `intent.clipData.getItemAt(0).uri`.
**Rationale:** OpenTracks's Dashboard API documentation is thin; this matches
the pattern used by other OpenTracks dashboard receivers (e.g. OpenTracksFitness)
and aligns with the URI-grant semantics of `ClipData`-attached Intents on Android.
**Alternatives considered:** Treating two named Intent extras as the carriers. Will
revisit during Phase C manual testing — if OpenTracks delivers via a different
mechanism, the activity logs every Intent shape it receives, so adjustment is
quick.
<!-- TODO — verify Dashboard URI delivery shape against current OpenTracks build during manual test -->

## TODOs

<!-- TODO:FEATURE — wire CMD_START / CMD_STOP through PebbleKitAndroid2 to OpenTracksApi (spec §14 step 5) -->
<!-- TODO:FEATURE — active-run screen layout (5 metrics, dim-on-stale) (spec §14 step 6) -->
<!-- TODO:FEATURE — HR sampling + cadence derivation on watch (spec §14 step 7) -->
<!-- TODO:FEATURE — external HR auto-detect state machine (spec §14 step 8) -->
<!-- TODO:FEATURE — stop-confirm screen + vibration (spec §14 step 9) -->
<!-- TODO:FEATURE — first-launch instructions screen polish + OpenTracks settings deeplink (spec §14 step 10) -->
<!-- TODO:SECURITY — review <queries> manifest exposure and incoming Intent validation in DashboardActivity before publish -->
<!-- TODO:SECURITY — verify ContentObserver cursor handling does not leak Track URI grants across activity recreation -->
<!-- TODO — Pebble SDK Nix packaging: verify `pebble-sdk` resolves on current nixpkgs channel; vendor or document manual install otherwise -->
<!-- TODO — PebbleKitAndroid2 is JitPack-only (alpha v0.1.0); may need vendoring for F-Droid official inclusion (spec §10.2 risk) -->
