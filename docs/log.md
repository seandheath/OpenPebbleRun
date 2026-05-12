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

## 2026-05-12 — PebbleKitAndroid2 v1.1.0 on Maven Central (spec correction)

**Decision:** Pin `io.rebble.pebblekit2:client:1.1.0`. Update spec §10.2 to retire the JitPack/F-Droid risk paragraph and spec §11 to bump the version reference.
**Rationale:** The library's own `.github/workflows/publish.yml` runs `./gradlew publishToMavenCentral`, so the spec's "JitPack alpha v0.1.0, not on F-Droid's trusted Maven list" framing is obsolete. Verified by fetching the workflow + the v1.1.0 release metadata (`2026-04-28`) and the library's `README.MD` documenting the stable API surface (`BasePebbleListenerService`, `DefaultPebbleSender`, `DefaultPebbleInfoRetriever`, `PebbleDictionaryItem` sealed class).
**Alternatives considered:** Vendor the library. Rejected — pure overhead now that Maven Central distribution is stable.

## 2026-05-12 — PebbleMessenger lifecycle: process-scoped singleton

**Decision:** `PebbleMessenger` is an `object` (Kotlin singleton) holding a lazily-created `DefaultPebbleSender`. Closed explicitly from `DashboardActivity.onDestroy`.
**Rationale:** The sender holds an internal `SuspendingBindingConnection` to the Pebble companion app. Each Dashboard observer callback would otherwise build/tear down a binder per TrackPoint update (potentially several per second). Singleton amortizes the connection across a run.
**Alternatives considered:** Per-message instantiation (rejected — too churny). Bound to a custom Application class (rejected — spec §5.1 prohibits foreground services and we have no other reason to subclass Application).

## 2026-05-12 — RunSession is an in-memory @Volatile singleton

**Decision:** Cross-component state (`active`, `watchAppOpen`) lives in `object RunSession`; PebbleListenerService and DashboardActivity both read/write it.
**Rationale:** Both components run in the same process and the spec (§11) explicitly accepts "no run state persistence/recovery across companion restarts". DataStore / SharedPreferences would add async APIs and persistence we don't need.
**Alternatives considered:** SharedPreferences (rejected — async write, persistence is non-goal). LocalBroadcastManager (deprecated). EventBus library (overkill).

## 2026-05-12 — RUN_STARTED sent from DashboardActivity, not PebbleListenerService

**Decision:** PebbleListenerService receives CMD_START → fires `OpenTracksApi.startRecording`. RUN_STARTED is sent from `DashboardActivity.onCreate` once OpenTracks actually calls us back.
**Rationale:** Firing RUN_STARTED on receipt of CMD_START would lie about state if OpenTracks rejects the intent (Public API disabled, permission denied). The watch's 15 s timeout in pre-run (spec §4.2.1) covers the failure path naturally — if Dashboard never fires, the watch shows the error message.
**Alternatives considered:** Optimistic RUN_STARTED + retroactive RUN_FAILED on a separate timeout. Rejected — duplicates work the watch already does.

## 2026-05-12 — Active-run Back wired directly to CMD_STOP (transitional)

**Decision:** `active_run.c`'s Back handler sends CMD_STOP and pops to pre-run, without a confirmation screen.
**Rationale:** Step 9 introduces the proper stop-confirm window (spec §4.2.3) with vibration on ack; until then, having Back work end-to-end is more useful than disabling it. The eventual stop-confirm window calls the same `app_message_send_cmd(KEY_CMD_STOP)` from its Select handler — minimal rework when step 9 lands.

## 2026-05-12 — Pebble SDK via pebble.nix (CoreDevices fork)

**Decision:** `flake.nix` consumes [`github:pebble-dev/pebble.nix`](https://github.com/pebble-dev/pebble.nix) with `withCoreDevices = true` to provide `pebble-tool`, `pebble-qemu`, and the ARM embedded toolchain inside `nix develop`. The companion Android tools are layered on via `pebbleEnv`'s `packages` parameter.
**Rationale:** Spec §4.1 targets the `emery` platform (Pebble Time 2) which is only supported by the CoreDevices pebble-tool fork. pebble.nix already wraps both upstream and CoreDevices toolchains and ships a binary cache (`cachix use pebble`), so users skip a ~30-minute toolchain rebuild. Single composed dev shell beats two-shell juggling.
**Alternatives considered:** Vendor the Pebble SDK ourselves (rejected — that's pebble.nix's job and they do it better). Manual install instructions in README (rejected — workflow friction discouraged contributors).

## TODOs

<!-- TODO:FEATURE — HR sampling + cadence derivation on watch (spec §14 step 7) -->
<!-- TODO:FEATURE — external HR auto-detect state machine (spec §14 step 8) -->
<!-- TODO:FEATURE — stop-confirm screen + vibration (spec §14 step 9, replaces transitional Back→CMD_STOP) -->
<!-- TODO:FEATURE — first-launch instructions screen polish + OpenTracks settings deeplink (spec §14 step 10) -->
<!-- TODO:SECURITY — review <queries> manifest exposure and incoming Intent validation in DashboardActivity before publish -->
<!-- TODO:SECURITY — verify ContentObserver cursor handling does not leak Track URI grants across activity recreation -->
<!-- TODO:SECURITY — confirm PebbleAndroidAppPicker auto-select default is acceptable; consider exposing the manual picker dialog from client-ui before publish -->
<!-- TODO — populate watchapp/package.json `companionApp.android.url` with the canonical Codeberg repo URL once chosen -->
