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

## 2026-05-13 — v0.1: start runs from the companion app; abandon watch-initiated-from-background (supersedes CDM entry below)

**Decision:** The companion app's Home screen has a **Start Run** / **Stop Run** button. Runs are initiated by tapping it on the phone, not by pressing Select on the watch. The watch's `CMD_START` path is preserved as best-effort (works while the companion is foreground; silently blocked otherwise) but is documented as non-canonical (spec §11).

**Rationale:** Three Android-mechanism attempts today to allow a backgrounded bound service to dispatch `OpenTracks.publicapi.StartRecording` all failed on the user's Android 14+ test device for different reasons:

1. **PendingIntent with creator-side BAL** — crashed on first build (wrong API: `setPendingIntentBackgroundActivityStartMode` is sender-side on API 34+, not creator-side). After the fix it worked only while the PI was in memory; doesn't survive process death; user would have to re-open MainActivity every time the OS evicts the process. Fragile.
2. **In-service `startForeground()`** — throws `ForegroundServiceStartNotAllowedException` on Android 14+. The FGS-from-background gate is the same family of restriction as BAL itself; `BOUND_FOREGROUND_SERVICE` proc state isn't enough.
3. **CompanionDeviceManager pairing** — AOSP exempts UIDs with an active CDM association from BAL. But the pairing system dialog scans for BLE-advertising devices, and the Pebble doesn't BLE-advertise while connected to the Pebble Android app, so the dialog never finds the watch. Dropped to wildcard filter; the dialog showed *other* nearby BLE devices but not the Pebble. Verified the issue isn't the Pebble app's bond — it's that connected BLE peripherals suppress advertising. No way around this without disconnecting the Pebble from the Pebble app temporarily (gross UX) or implementing `NotificationListenerService` (Settings consent dance, comparable cost to CDM, no winner).

Rather than escalate further (NotificationListenerService, or rewriting OpenPebbleRun to record GPS itself and bypass OpenTracks entirely), accept the constraint: **runs are started from the phone**. Same pattern as Strava, RunKeeper, and most Android fitness apps. The watch remains the canonical *display* surface during a run, just not the initiator.

**Code changes** (subtractive):
- Deleted `companion/.../cdm/CdmManager.kt`.
- Manifest: removed `REQUEST_COMPANION_RUN_IN_BACKGROUND`, `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`, `uses-feature android.software.companion_device_setup`.
- `MainActivity`: removed `pairingLauncher`, `paired` state, `requestPairing()`; added `runActive` state + 1 Hz Compose tick reading `RunSession.active`; added `onStartTapped`/`onStopTapped` wired to `OpenTracksApi.startRecording`/`stopRecording` from the foreground context.
- `HomeScreen`: removed "Background access" status row and "Pair Pebble" button; added a primary Start Run / Stop Run button below the existing rows.
- `FirstLaunchScreen` + `strings.xml`: removed step 4 + the four pairing-related string resources.
- `PebbleListenerService`: removed the CDM diagnostic log; `handleStart` / `handleStop` are otherwise unchanged.
- Spec §5.1 / §5.2.1 / §5.2.2 / §9 / §11 updated to reflect the foreground-only start path.

**Preserved from earlier today** (still valuable, all working):
- 5 s polling of OpenTracks Dashboard URIs in `PebbleListenerService` (out of `DashboardActivity` lifecycle).
- `moveToLast` + skip-backward cursor fix; TIME=0 filter in `PebbleMessenger`.
- Watchapp local 1 Hz time tick + 0.2 Hz HR sampling.
- Foreground service while recording — promotion is triggered from `DashboardActivity.onCreate` (called by OpenTracks from *its* foreground context), so the FGS-from-background restriction doesn't apply.
- Multi-variant OpenTracks detection; build/log/install tooling; cursor extension helpers.

**Alternatives considered but not pursued:**
- *NotificationListenerService* — would grant our UID a BAL exemption similar to CDM. Requires Settings-page consent (Settings > Notification Access > OpenPebbleRun > Allow). Comparable cost to CDM; no improvement over the foreground-only start path; not worth the implementation overhead.
- *Custom GPS recording* — would bypass OpenTracks entirely and avoid the IPC. Massive rewrite, abandons the OpenTracks-as-source-of-truth design (spec §3, §6). Out of scope.

## 2026-05-13 — Adopt CompanionDeviceManager for BAL exemption (supersedes today's foreground-service-from-background attempt below)

**Decision:** Pair the Pebble via Android's CompanionDeviceManager (CDM) at first launch. Manifest declares `REQUEST_COMPANION_RUN_IN_BACKGROUND` + `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`; both are normal permissions that activate when the CDM association is in place. The foreground-service promotion from the prior entry stays — it's still useful UX-wise — but now actually *works* because the CDM grant unlocks the foreground-service-from-background path on API 34+.

**Rationale:** The entry directly below this one proposed promoting `PebbleListenerService` to foreground via `Service.startForeground()` from within `handleStart`. Live-tested today on a real Pixel running Android 14+; it failed silently on every CMD_START. `Service.startForeground()` is itself subject to the same family of "no background activity launches" restrictions as BAL on Android 14+ — calling it from a Bluetooth-triggered service callback throws `ForegroundServiceStartNotAllowedException`. `BOUND_FOREGROUND_SERVICE` proc state isn't enough; the system requires an actual foreground-eligible state at call time.

The OS-blessed fix is CDM. AOSP's `BackgroundActivityStartController` short-circuits its BAL gate to `BAL_ALLOW_ALLOWLISTED_COMPONENT` for any UID with an active CDM association, and the FGS-restrictions list grants the same exemption to apps with `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`. Gadgetbridge — the established open-source Pebble companion — uses exactly this pattern to control watches from a background service.

**Trade-off:** One user-visible step at first launch (the CDM system dialog showing the Pebble; user taps "Allow"). The user explicitly accepted "open the companion app once to allow permissions". The pairing is permanent; survives reinstalls of OpenPebbleRun? — no, it's tied to the package and is cleared on uninstall. Re-paired on next first launch.

**Code shape:**
- New `companion/.../cdm/CdmManager.kt` encapsulates `CompanionDeviceManager.associate(...)` (modern callback path on API 33+, legacy `onDeviceFound` on 26-32) and `myAssociations` check.
- MainActivity gains a `pairingLauncher: ActivityResultLauncher<IntentSenderRequest>` that drives the CDM system dialog, plus a `paired` state and a "Pair Pebble for background access" button on Home (only when not paired).
- PebbleListenerService gets a defensive `Log.w` if `CdmManager.isPaired == false` at CMD_START time — same code as before; the CDM grants make the existing `promoteToForeground()` + `startActivity` calls succeed.
- AssociationRequest filter targets the Pebble's BT MAC (extracted from `PebbleInfoRetriever.getConnectedWatches()` — PebbleKit gives us the MAC via `WatchIdentifier`) so the dialog is one-tap, single-device. Falls back to a name regex when MAC isn't known yet.
- Both REQUEST_COMPANION_* permissions declared in the manifest as `<uses-permission>`. Normal level — no runtime prompt — but the underlying grant only takes effect once the CDM association is created.

**Alternatives considered:**
- *In-service `startForeground` (the entry below)* — implemented today, broken on Android 14+ as described above. Code stays in tree because once CDM is in place it actually works and gives a nice "Recording" notification.
- *PendingIntent with creator-side BAL* — earlier attempt today. Fragile; PI grant dies on process kill. Discarded.
- *CompanionDeviceService + startObservingDevicePresence* — useful for presence-based lifecycle callbacks (stop polling when Pebble out of BT range). Not needed for the BAL fix; association alone grants the exemption. Future v1.0 enhancement.

## 2026-05-13 — Adopt foreground service for run state (supersedes spec §5.1 no-foreground-service prohibition)

**Decision:** `PebbleListenerService` is promoted to a foreground service (`foregroundServiceType="connectedDevice"`) while a run is active (between CMD_START and CMD_STOP). A persistent low-importance notification is posted during the run, dismissed on stop. `POST_NOTIFICATIONS` is requested at first launch on API 33+. Spec §5.1 ("No foreground service. No `POST_NOTIFICATIONS`.") is amended; matching §9 and §11 also updated.

**Rationale:** Android 12+'s Background Activity Launch (BAL) policy silently refuses `context.startActivity` from a bound service with no visible window — observed in live testing today as `BAL_BLOCK, result code=102` on every CMD_START and CMD_STOP arriving from the watch while the companion's MainActivity wasn't foreground. This defeats spec §5.2's "watch is the canonical control surface" requirement. OpenTracks itself solves the identical problem with a foreground service — `TrackRecordingService` is declared with `foregroundServiceType="location|connectedDevice"`, the publicapi.StartRecording activity briefly visible to promote it, then `finish()`es. Our trigger model (watch press → bound service handler) can't mimic the briefly-visible-Activity entrance, but we can mimic the foreground-service part: promote our already-running bound service to foreground via `startForeground()` from within `handleStart()`, demote in `handleStop()`. The foreground state grants BAL for the StartRecording / StopRecording dispatches.

The cost is one ongoing notification while a run is active. That's the standard fitness-app UX — users already see one from OpenTracks during the same run.

**Alternatives considered:**
- *PendingIntent with creator-side BAL* — attempted earlier today (`OpenTracksApi.prepareLaunchTokens` called from MainActivity). Fragile: PI captures BAL allowance only while MainActivity is foreground; doesn't survive process death; user has to remember to open the companion app after each install or process kill. First implementation crashed on API 34+ due to an API misuse (`setPendingIntentBackgroundActivityStartMode` is for the SENDER at send time, not the CREATOR at create time). Reverted as part of this entry.
- *CompanionDeviceManager + REQUEST_COMPANION_RUN_IN_BACKGROUND* — cleaner Android-blessed mechanism; ties BAL allowance to a paired device's BLE presence. Requires a visible pairing dialog (extra one-time UX), a CDM association lifecycle, and substantial new code. Considered for a future v1.0 enhancement.
- *Briefly-visible Activity (direct OpenTracks mimic)* — doesn't fit our trigger model. The service can't launch any activity from background without BAL allowance, defeating the bootstrap.

**Code changes:**
- `companion/app/src/main/AndroidManifest.xml` — added `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` (API 34+), `POST_NOTIFICATIONS` (API 33+); added `foregroundServiceType="connectedDevice"` on the listener service.
- `PebbleListenerService.kt` — notification channel + builder, `promoteToForeground()` called in `handleStart` before the dispatch, `demoteFromForeground()` in `handleStop` after the dispatch (and defensive demote in `onDestroy`).
- `OpenTracksApi.kt` — PendingIntent infrastructure removed; back to direct `context.startActivity`. Cleaner code; the service's foreground state is now responsible for BAL.
- `MainActivity.kt` — `prepareOpenTracksLaunchTokens` removed; POST_NOTIFICATIONS runtime permission requested on API 33+ via `ActivityResultContracts.RequestPermission`.
- `res/values/strings.xml` — notification channel name/description, recording title/text.

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

## 2026-05-13 — Companion-start race: install pre-run inbox handler synchronously

**Decision:** `pre_run_show()` calls `app_message_set_inbox_handler(inbox_handler)` synchronously before `window_stack_push`, in addition to the existing install in `window_appear`.
**Rationale:** Companion-started runs (spec §11) send `RUN_STARTED` from `DashboardActivity.onCreate` ~100–150 ms after the user taps Start — often before the watchapp's just-started event loop dispatches the `window_appear` callback that installs the pre-run inbox handler. With `s_inbox_handler` still `NULL` at that moment, `app_message.c:inbox_received_handler` silently drops the message, stranding the watch on "Press Select to start" forever (the companion's `onAppOpened` retry doesn't fire either, because `RunSession.active` is still `false` when the watchapp first opens). Synchronous install mirrors `active_run.c:354`'s pattern and eliminates the race window entirely.
**Alternatives considered:**
- Track a `runStartPending` flag on the companion and have `onAppOpened` send `RUN_STARTED` on that flag too: adds state, risks lying to the watch if OpenTracks denies the recording.
- Delay the `DashboardActivity.onCreate` send by 500 ms: brittle, depends on BT latency and PebbleKit binding state.

## 2026-05-13 — Active-run Back exits without stopping; new run-summary screen

**Decision:** Active-run Back exits the watchapp without sending `CMD_STOP`; the run continues in the companion and re-opening the watchapp resumes the active-run display via the existing `onAppOpened` → `RUN_STARTED` replay (gated on `RunSession.active`). Select on active-run opens stop-confirm; on confirm the watchapp shows a new run-summary screen with distance / time / avg pace / avg HR before any button dismisses to pre-run.
**Rationale:** Single-button Back-to-stop is too easy to fire accidentally on a wrist watch; Pebble's first-party Workout app uses Select for stop-entry plus a confirm step. Resume-on-reopen makes Back a non-destructive escape hatch, matching how Strava/Runkeeper/etc. behave when their app is backgrounded. The run-summary screen lets the user see what they just ran without switching to the phone — closing the loop end-to-end on the watch.
**Companion-side correlate:** `PebbleListenerService.handleStop` and `MainActivity.onStopTapped` now call `RunSession.clear()` so a re-open *after* a stop doesn't falsely replay `RUN_STARTED`. Previously `RunSession.active` only flipped back to `false` when the Android task tore down `DashboardActivity` — typically long after the run had actually ended.
**Alternatives considered:**
- Long-press Back as stop-entry: less discoverable; no chord support in current code.
- Skip the summary, return straight to pre-run: matches old spec but leaves the user reaching for the phone.
- Send a `RUN_STOPPED` key from companion-initiated stop so those runs also surface a summary on the watch: deferred — requires a new inbox key and active-run handler; out of scope for this change. Companion-initiated stop currently leaves the watch on active-run with stale data; user dismisses with Back.

## 2026-05-13 — Walk back "launch into active-run"; add minimal idle screen

**Decision:** Add `screens/idle.{c,h}` — a two-line screen ("OpenPebbleRun" / "Start a run on your phone") that is the watchapp's launch entry point. Its inbox handler watches for `RUN_STARTED` and pushes active-run on top when one arrives. `main.c` now calls `idle_show()` instead of `active_run_show()`.

**Rationale:** Earlier today the watchapp was wired to launch directly into active-run on the assumption that placeholders ("---", "0.00", "0:00") would be acceptable in the "no run active" state. Manual testing on hardware proved otherwise — `pebble install` auto-launches the watchapp, and the user immediately sees what looks like a stuck/broken run-stats display. The idle screen with explicit prompt text makes the "nothing is happening yet, do this next" state unambiguous. The transition path is the same as the deleted pre-run handled (inbox watches RUN_STARTED → push active-run), minus the obsolete Select-to-start state machine.

**Implementation notes:**
- Idle screen is essentially pre-run minus IDLE→STARTING and the ERROR/timeout states. No `CMD_START` is ever sent.
- Inbox handler installed synchronously in `idle_show` to win the companion-start race (same fix the deleted pre_run.c carried; failure mode is identical).
- `active_run.h` docstring walked back from "watchapp entry point" to "pushed by idle when RUN_STARTED arrives".
- Spec §4.2 reverted from "Three screens, launch on active-run" to "Four screens, launch on idle"; §4.2.1 re-introduced with the new idle definition; §5.1 and §11 wording updated accordingly.

**Alternatives reconsidered:**
- *Keep launching into active-run* — original v0.1 design; the placeholder state looks broken in practice (user-reported).
- *Auto-exit if no run within 3 s of launch* — surprising UX, and a quick `pebble install` launch would close itself before the user even sees it.
- *Resurrect pre-run with state machine intact* — drags back the IDLE/STARTING/ERROR/timeout state and the dead `CMD_START` path. Idle is just the useful subset.

## 2026-05-13 — Iconographic stop buttons + remove pre-run screen

**Decision:** Stop entry on active-run moves from Select to **Down**, with a small filled-square stop-icon hint painted at the right edge of the screen vertically aligned with the physical Down button. Stop-confirm uses **Up = ✓ / Down = ✕** at the right edge (Back mirrors Down for Pebble's "Back = go back" convention; Select is a no-op). Run-summary's **Back** exits the watchapp entirely (`window_stack_pop_all`) and Select/Up/Down are ignored. The pre-run "Press Select to start" screen is **deleted** — the watchapp launches straight into active-run, and `pre_run.{c,h}` are removed.

**Rationale:** Runs are started from the companion phone app (today's earlier v0.1 pivot entry); pre-run's Select-to-start affordance no longer does anything useful, and a screen whose only prompt has been disabled is actively user-confusing. Removing it eliminates the "press a button that does nothing" launch state and reflects the watch's real role in v0.1: a display surface for in-progress runs and the stop-confirm flow. Icon hints next to physical buttons remove the "which button does what" ambiguity during a sweaty run, and "Down twice from active-run returns to active-run" is a discoverable invariant the user can rely on without reading docs. Run-summary's Back-only binding prevents a stray Up/Down press from dismissing the summary before the user has read their final stats.

**Implementation notes:**
- New shared module `watchapp/src/c/screens/icons.{c,h}` exposes `icons_draw_stop_square`, `icons_draw_check`, `icons_draw_x`. Hand-drawn via `graphics_fill_rect` / `graphics_draw_line` (stroke width 3, AA on for line glyphs). No PNG resources — `package.json`'s `resources.media` stays empty.
- `active_run.c`: new `Layer *s_stop_icon` at `GRect(184, 180, 16, 16)`; TIME label/value width shrunk 100 → 82 to free an 18 px right-edge gutter; Down rebound to `stop_confirm_show`; Select/Up no-op.
- `stop_confirm.c`: dropped the "Select = Yes / Back = No" prompt entirely; new check (20×20 at y=40) and X (20×20 at y=180) layers; Up confirms (CMD_STOP + vibrate + run-summary + window-stack-remove dance), Down/Back cancel, Select no-op.
- `run_summary.c`: `back_click_handler` → `window_stack_pop_all`; Select/Up/Down → `noop_click_handler`.
- `main.c`: launches `active_run_show()` instead of `pre_run_show()`; deinit calls `active_run_hide()`.

**Side effects (deferred cleanup):** the companion's `PebbleMessenger.sendRunFailed` and `PebbleListenerService.handleStart` (the CMD_START dispatch) become dead code — nothing on the watch ever sends `CMD_START` any more. The dead code is harmless; a sweep can happen in a separate change.

**Alternatives considered:**
- *Replace pre-run with a "Start on phone" idle screen* — same information ("there's no run yet"), more code, no functional gain over active-run's existing placeholder + 30 s stale-dim.
- *Auto-exit if no run is active within 3 s of launch* — apps that close themselves are confusing.
- *ActionBarLayer for the icons* — reserves a ~30 px column on emery, forces a full active-run grid reflow. Inline 16-px gutter is tighter and lets us keep the existing 100/100 column split on the upper rows.
- *Bitmap icons via `package.json` resources* — three PNGs for three trivial primitives; breaks the resource-free deployment invariant for negligible visual gain.
- *Long-press Back as stop-entry* — less discoverable than a visible icon; no `multi_click` chord pattern exists elsewhere in the codebase to mirror.

## TODOs

<!-- TODO:FEATURE — HR sampling + cadence derivation on watch (spec §14 step 7) -->
<!-- TODO:FEATURE — first-launch instructions screen polish + OpenTracks settings deeplink (spec §14 step 10) -->
<!-- TODO:FEATURE — companion-initiated stop should also trigger watch run-summary (requires new RUN_STOPPED key; see 2026-05-13 active-run-Back entry) -->
<!-- TODO — sweep dead companion-side CMD_START path: PebbleMessenger.sendRunFailed, PebbleListenerService.handleStart (no watch consumer after 2026-05-13 icons entry) -->
<!-- TODO:SECURITY — review <queries> manifest exposure and incoming Intent validation in DashboardActivity before publish -->
<!-- TODO:SECURITY — verify ContentObserver cursor handling does not leak Track URI grants across activity recreation -->
<!-- TODO:SECURITY — confirm PebbleAndroidAppPicker auto-select default is acceptable; consider exposing the manual picker dialog from client-ui before publish -->
<!-- TODO — populate watchapp/package.json `companionApp.android.url` with the canonical Codeberg repo URL once chosen -->
