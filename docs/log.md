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

## 2026-05-13 — Spec §5.3 pace: OpenTracks speed direct, no smoothing window

**Decision:** Drop `PaceWindow.kt` and the 15s rolling-mean approach. Compute current pace as `1609.344 / speed` (sec/mi) from the latest TrackPoint's `speed` column directly. When `speed` is null or non-positive, return null → watch renders `--:--`. The new helper is `TrackStats.paceFromSpeed`.
**Rationale:** Two reasons. First, OpenTracks's TrackRecordingService applies its own filtering before inserting TrackPoints (min-distance-from-previous, accuracy thresholds) — by the time a row hits the dashboard URI it's already curated, so additional smoothing on our side is duplicative. Second, the 15s window introduced complexity (sample buffer, trimming, time alignment) that didn't justify itself when OpenTracks is the canonical source of truth; the spec writer was preempting an unproven concern. If GPS speed jitter ever becomes a visible problem on the watch, restore the window later — but the *default* should be "show what OpenTracks reports."
**Side effect:** Until OpenTracks inserts a non-segment-marker TrackPoint with non-null `speed`, the watch shows `--:--`. Spec §11 already accepts that the watch displays `0:00` / `0.00 mi` / `--:--` for the early seconds of a run; this is consistent.
**Alternatives considered:** Track-level pace deltas (recompute pace = Δdistance / Δmovingtime across consecutive Track observer fires). Rejected — gives a derived metric that diverges from what users see in OpenTracks itself, breaks "OpenTracks is source of truth."

## 2026-05-13 — OpenTracks v4.27 dashboard API: DataProvider, /dashboard/ URIs, slim projection

**Discovery:** Sean's installed OpenTracks (v4.27.0, Codeberg) introduced a new `DataProvider` class that replaces `IntentDashboardUtils`. The dashboard URI scheme is now `/dashboard/tracks/<ids>`, `/dashboard/trackpoints/<ids>`, `/dashboard/markers/<ids>` (vs. the legacy `/tracks/<ids>`, `/trackpoints/trackid/<ids>`). v4.27 source: `codeberg.org/OpenTracksApp/OpenTracks/raw/tag/v4.27.0/src/main/java/de/dennisguse/opentracks/publicapi/DataProvider.java`.
**Key facts:**
- The provider applies strict projection maps (`DATA_PROJECTIONMAP_TRACKS`, `DATA_PROJECTIONMAP_TRACKPOINTS`) to filter columns. Both V2 (`movingtime`, `totaldistance`) and V3 (`duration_moving`, `distance`) column aliases are present; we use V2 names.
- TrackPoints projection in v4.27 is **`_id, trackid, latitude, longitude, time, type, speed`** — no `sensor_heartrate`, no `sensor_cadence`, no `accuracy`, no `altitude`. Spec §4.3 step-8 (HR from BLE strap via OpenTracks) is **not feasible** on v4.27 through this URI. TODO below.
- TrackPoints cursor typically holds only a `SEGMENT_START_MANUAL` row (`type=-2`, `speed=null`) until the device has moved past OpenTracks's "min recording distance" threshold — explains the `count=1` we saw with `speed=null` on first end-to-end test.

**OpenTracks project home moved**: from GitHub `OpenTracksApp/OpenTracks` (still mirrored, last tagged v4.22.0 Aug 2025) to Codeberg `OpenTracksApp/OpenTracks` (active, current tags through v4.27.0). docs/specification.md §6.2 references the Codeberg source going forward.

<!-- Resolved 2026-05-13: external HR via OpenTracks is deferred from v1; see decision-log entry "Target F-Droid only; defer external HR; Dashboard API confirmed as the channel". -->

## 2026-05-13 — Dashboard URI delivery + column-name corrections

**Discoveries from first end-to-end test:**

1. **URI shape.** Earlier the log flagged the `intent.data` + `intent.clipData[0]` layout as a guess. Actual delivery (per `IntentDashboardUtils.startDashboard` in OpenTracks): all three URIs ride in `intent.clipData` — `[0]` is Track, `[1]` is TrackPoints, `[2]` is Markers. `intent.data` is never populated. `DashboardActivity` now reads `clipData[0..1]`.

2. **Column-name case.** Spec §6.2 had `MOVINGTIME`, `TOTALDISTANCE`, `SENSOR_HEARTRATE` (UPPER_SNAKE). OpenTracks's `TracksColumns.java` / `TrackPointsColumns.java` declare them as **lowercase** Java string constants (`movingtime`, `totaldistance`, `sensor_heartrate`). SQLite is case-insensitive in unquoted SQL, but Android's `Cursor.getColumnIndexOrThrow` is case-sensitive on most ContentProvider implementations — so uppercase requests threw `IllegalArgumentException`, our defensive try/catch swallowed it, every `longOrNull`/`floatOrNull` returned null → watch saw zero/missing for every metric even when the track was recording. Spec §6.2 and `DashboardActivity`'s constants are now lowercase.

Both fixes verified against the upstream files (`pebble-dev/.../IntentDashboardUtils.java` and `OpenTracksApp/.../TrackPointsColumns.java` on `main` as of 2026-05-13).

## 2026-05-13 — Restore multi-variant OpenTracks probe (supersedes F-Droid-only below)

**Decision:** Restore the four-package OpenTracks probe (`de.dennisguse.opentracks`, `.playstore`, `.debug`, `.nightly`) and the Home-screen variant label. The "F-Droid only" portion of the entry below is reversed; the deferred-external-HR and Dashboard-API-channel decisions in that entry still stand.

**Rationale:** Stripping the probe added friction for Play Store users with no real benefit. The variant branches were ~10 lines total (manifest `<package>` entries, probe list, label table, optional `variantPackage` parameter on `OpenTracksApi`) and added no measurable maintenance load — first-resolved-wins is dead-simple. The earlier simplification was premature: removing optionality that's actually exercised by real users.

**Edits made:** spec §2 (drop the F-Droid-only non-goal), §5.2.1 (revert step 1 wording), §5.2.2 (variant name back in Home row), §5.4 (four-variant probe), §6.1 (variable `<package>` in component), §9 (`<queries>` lists all four). Six code files restored via `git checkout HEAD -- …` — working-tree-only revert, no commit existed between the two decisions.

**Alternatives considered:** F-Droid + Play Store only (skip the dev variants). Rejected as marginal — once we're already paying for two variants the cost of `.debug`/`.nightly` is zero, and they're occasionally useful for development against an OpenTracks dev build.

## 2026-05-13 — Target F-Droid only; defer external HR; Dashboard API confirmed as the channel

**Decision:** The OpenTracks integration channel is the Dashboard API (typed content-provider URIs delivered via ClipData) — same channel OSMDashboard uses, same channel Gadgetbridge consumes. Companion targets the single F-Droid package `de.dennisguse.opentracks`. External HR (BLE strap → OpenTracks → companion → watch) is deferred from v1.

**Rationale:**
- *Channel.* OpenTracks's README §"Dashboard API" makes explicit that Gadgetbridge is a *consumer* of the Dashboard API, not an alternate surface. Routing pebblerun through Gadgetbridge would mean depending on Gadgetbridge's historically thin Pebble support, with no signal benefit (we already get typed columns directly). The alternative "Notification bridge" path (NotificationListenerService parsing OpenTracks's notification text) loses typed metrics, requires the intrusive Notification Access permission, and still wouldn't help with HR.
- *F-Droid only.* The four-variant probe (`de.dennisguse.opentracks` / `.playstore` / `.debug` / `.nightly`) was added speculatively early on. F-Droid is the supported install for everyone Sean wants to reach; every additional variant adds a `<queries>` entry plus a label branch plus probe ceremony for nobody. Cutting these collapses `OpenTracksVariant` from ~100 lines to a constant + one `getPackageInfo` call.
- *Defer HR.* Two reasons stack here. (1) v4.27's `DataProvider.DATA_PROJECTIONMAP_TRACKPOINTS` exposes only `_id, trackid, latitude, longitude, time, type, speed` — `sensor_heartrate` is no longer in the projection, so the existing "read TrackPoints' SENSOR_HEARTRATE and forward" mechanism is dead. (2) The Pebble SDK cannot act as a BLE GATT central — `HealthService` exposes only the on-board optical sensor; PebbleKit BLE is phone↔watch transport. So "BLE strap → watch directly" is not implementable. The only realistic future external-HR path is *companion-mediated BLE forwarding*, which is real work that shouldn't block shipping pace/time/distance end-to-end.

**Spec changes:** §2 (non-goals add F-Droid-only + deferred-external-HR), §4.3 (single-source HR, drop companion auto-detect), §5.2.1 (single install source), §5.2.2 (drop variant chip), §5.3 (drop the v4.27 caveat tying §4.3 step 8 to the projection), §5.4 (single package, no probe loop), §6.1 (component hard-codes F-Droid package), §6.2 (drop `sensor_heartrate` column, declare v4.27 projection as baseline), §7.2 (reserve keys 113/114/124, no metric-table entries), §7.3 (drop HR rows), §9 (`<queries>` lists only `de.dennisguse.opentracks`), §11 (HR limitation simplified), §14 (renumber: step 8 external HR removed).

**Code changes:** `OpenTracksVariant` reduces to `PACKAGE` const + `isInstalled()`; `Detection` data class deleted; `MainActivity` / `HomeScreen` / `PebbleListenerService` updated to use a boolean. `OpenTracksApi.startRecording / stopRecording / openApp` lose the `variantPackage` parameter (always F-Droid). `pebble/Keys.kt` removes `HR_SOURCE_EXTERNAL`, `HR_SOURCE_INTERNAL`, `HR_EXTERNAL`. `AndroidManifest.xml` `<queries>` trimmed to a single OpenTracks `<package>`. `strings.xml` first-launch step-1 already names F-Droid; no change required.

**Alternatives considered:**
- *Notification-listener bridge (Gadgetbridge-style).* Rejected — loses typed metrics, needs Notification Access, no HR benefit.
- *Keep multi-variant for flexibility.* Rejected — speculative, every variant costs a manifest entry + probe branch + label arm.
- *Pebble-side BLE strap.* Rejected — blocked by Pebble SDK (no BLE central role for watchapps).

## 2026-05-13 — OpenTracks gates dashboard callback behind a second toggle

**Discovery:** OpenTracks's Public API settings expose two switches, not one:
1. **Public API** (`publicapi_enabled_key`) — gates StartRecording / StopRecording.
2. **Automatic data transfer** (`publicapi_dashboard_enabled_key`) — gates the dashboard callback (the `STATS_TARGET_PACKAGE` / `STATS_TARGET_CLASS` invocation).

Recording starts unconditionally when (1) is on, but our `DashboardActivity` only fires when (2) is also on (`StartRecording.java:39` in OpenTracks: `if (PreferencesUtils.isPublicAPIDashboardEnabled()) { startDashboardAPI(...) }`). With only (1) enabled, the watch sat on "Starting…" until the 15 s timeout and then displayed the failure message — confusingly, because the run *had* started in OpenTracks; we just never got the callback we send `RUN_STARTED` from.

Spec §5.2.1 and `strings.xml`'s first-launch step 2 updated to require both toggles.

**Why this matters for first-launch UX:** Right now we can't programmatically check whether the second toggle is on — only that OpenTracks is installed. Spec §11 already accepts "Public API enablement is not auto-verified". Step 10 (home polish) is a good place to add a "Recording started but no dashboard data in 30 s? Check OpenTracks → Public API → Automatic data transfer" hint when the user reports the watch as stuck.

<!-- TODO:FEATURE — surface the dashboard-toggle hint in companion UI on detected dashboard timeout (step 10) -->

## 2026-05-12 — Auto-bootstrap pebble-tool + SDK in shellHook

**Decision:** `flake.nix`'s `shellHook` now installs `pebble-tool` via `uv` and runs `pebble sdk install latest` on first `nix develop` (idempotent on subsequent entries). `OPENPEBBLERUN_SKIP_SETUP=1` opts out. Top-level `make pebble-setup` re-runs the same logic explicitly; `make pebble-setup-clean` wipes both.
**Rationale:** Research into pebble.nix's current state (May 2026) confirmed no upstream-endorsed pattern beats our hybrid approach: their bundled `pebble-tool` is stuck at v5.0.5 (mainline) / v5.0.21 (stalled PR #12 since Jan 2026), modern SDK manifests require ≥ v5.0.32, and v5.0.35 is on PyPI where `uv` reaches. Auto-installing in the shellHook eliminates the "run these two commands first" preamble for new contributors. The implicit Rebble TOS acceptance is surfaced in a one-line disclosure before the SDK install so consent isn't silent.
**Alternatives considered:**
- Printf-only (status quo before this change): explicit but high-friction. Rejected — the two install commands are the same on every fresh checkout, so manual entry is pure ceremony.
- Custom Nix derivation pinning pebble-tool: rejected upthread (~90 lines of Nix we'd own, re-bump every upstream release).
- File a PR against pebble.nix bumping pebble-tool: explicitly out of scope for this plan; can revisit if PR #12 stalls indefinitely.

## 2026-05-12 — Trusted-users + cachix as a documented prerequisite

**Decision:** `pebble.cachix.org` is essentially required (the alternative is a doomed `arm-embedded-toolchain-4.7` source build), but consuming it requires the invoking nix user to be in `nix.settings.trusted-users`. README documents both the cachix and the trusted-users requirements; we do not auto-enforce them.
**Rationale:** A trusted user can substitute any nix store path via custom substituters and disable the sandbox — effectively a root-elevation path for any malicious process running as that user. The marginal risk over having sudo is moderate (mainly userland-malware persistence vectors), but real. For a single-user dev machine this is the standard developer setup and what the wider nix community defaults to; for multi-user/shared boxes it's effectively passwordless sudo and should be avoided. Documenting the tradeoff in the README lets each contributor make an informed choice rather than enforcing one default.
**Alternatives considered:**
- Vendor the prebuilt toolchain into the repo: ~50 MB of binary blobs in git history, breaks pebble.nix coupling, gets stale.
- Use `--option extra-substituters` flags on every nix invocation: same trusted-users restriction applies; doesn't help.
- nix-ld + downloaded ARM binaries instead of pebble.nix: would replace one NixOS-specific config requirement with another.

## 2026-05-12 — Switched to hybrid pebble-tool: uv install + pebble.nix binaries (superseded above)

**Decision:** Bypass `pebbleEnv` and assemble `mkShell` directly. Install `pebble-tool` via `uv tool install pebble-tool` (canonical upstream method per [developer.repebble.com/sdk](https://developer.repebble.com/sdk)). Use pebble.nix's `arm-embedded-toolchain`, `pebble-qemu`, and `pebble-toolchain-bin` as the actual binaries pebble-tool shells out to, wiring them via `PEBBLE_EXTRA_PATH` and `PEBBLE_QEMU_PATH`.
**Rationale:** pebble.nix's `coredevices.pebble-tool` is pinned at v5.0.5; the current CoreDevices SDK manifests require pebble-tool ≥ 5.0.32 (`This SDK has the following unmet requirements: pebble-tool>=5.0.32` on first `pebble build`). Writing and maintaining a custom Nix derivation that tracks pebble-tool's hatchling-based 5.0.35+ pyproject was tried briefly and rejected — that's ~90 lines of Nix to own and re-bump on every upstream release. The hybrid approach lets `uv` track upstream automatically while pebble.nix continues to do the hard NixOS work of patching the downloaded ARM binaries.
**How it works:** `pebble sdk install latest` still drops glibc-linked binaries under `~/.pebble-sdk/SDKs/<v>/toolchain/arm-none-eabi`, but pebble-tool prepends `PEBBLE_EXTRA_PATH` to `PATH` *after* that directory (see `pebble_tool/sdk/__init__.py:64-78` in v5.0.35), so the nix-patched binaries on `PEBBLE_EXTRA_PATH` take precedence at build time. The downloaded toolchain is effectively dead weight on disk.
**Alternatives considered:**
- Custom derivation forking `coredevices/pebble-tool` (rejected — see above).
- Pure-upstream install (rejected — `pebble sdk install` drops binaries that won't execute on NixOS without nix-ld; can't assume contributors have that set up).
- Pin an older SDK whose manifest accepts pebble-tool 5.0.5 (rejected — SDK and tool versions move together; pinning either ages the project out of upstream's support window).

## TODOs

<!-- TODO:FEATURE — HR sampling + cadence derivation on watch (spec §14 step 7) -->
<!-- TODO:FEATURE — stop-confirm screen + vibration (spec §14 step 9, replaces transitional Back→CMD_STOP) -->
<!-- TODO:FEATURE — first-launch instructions screen polish + OpenTracks settings deeplink (spec §14 step 10) -->
<!-- TODO:SECURITY — review <queries> manifest exposure and incoming Intent validation in DashboardActivity before publish -->
<!-- TODO:SECURITY — verify ContentObserver cursor handling does not leak Track URI grants across activity recreation -->
<!-- TODO:SECURITY — confirm PebbleAndroidAppPicker auto-select default is acceptable; consider exposing the manual picker dialog from client-ui before publish -->
<!-- TODO — populate watchapp/package.json `companionApp.android.url` with the canonical Codeberg repo URL once chosen -->
