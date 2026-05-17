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

**Update (2026-05-15):** Initial assumption (`intent.data` carrying the Track URI) was wrong on the carrier — both URIs arrive in `intent.clipData[0]` and `[1]`. The paths the assumption *implied* (`/dashboard/tracks/<id>` / `/dashboard/trackpoints/<id>`) turned out to be right, verified in the field. Authority is `<applicationId>.content`. See the 2026-05-15 corrigendum entry below.

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

## 2026-05-14 — Re-implement CompanionDeviceManager pairing (classic-BT filter)

**Decision:** Re-introduce CDM pairing for the Pebble. New `cdm/CdmManager.kt` wraps `CompanionDeviceManager`. `MainActivity` registers an `ActivityResultContracts.StartIntentSenderForResult` launcher, refreshes a `paired` Compose state on resume, and routes a Home-screen button to `CdmManager.requestPairing`. The Home screen gains a third status row "Background access" (✓ Paired / ✗ Not paired) and shows an outlined "Pair Pebble for background access" button when not paired. First-launch step 4 + `PebbleListenerService.handleStop` defensive log added. Manifest re-adds `REQUEST_COMPANION_RUN_IN_BACKGROUND` + `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`. Spec §5.1 / §5.2 / §9 / §11 updated.

**Rationale:** Real-hardware logcat showed `BAL_BLOCK` on watch-initiated `CMD_STOP` despite the listener service being in `FOREGROUND_SERVICE` state with `foregroundServiceType="connectedDevice"`. The 2026-05-13 log entry's working theory — "FGS state grants BAL for OpenTracks dispatches" — turns out to be wrong for run-realistic durations. Per the [official BAL documentation](https://developer.android.com/guide/components/activities/background-starts), the FGS exemption applies only for a brief window (~10 s) after the activity that promoted the service is backgrounded. Of the 13 enumerated BAL-exempt conditions, only #11 (CDM association) fits our "respond to action on a paired companion device" use case.

The previous CDM attempt (2026-05-13 entry "Adopt CompanionDeviceManager for BAL exemption", code since deleted by the 2026-05-13 v0.1 pivot) failed because the filter type was `BluetoothLeDeviceFilter`. BLE filters require the device to be **actively advertising**, which the Pebble doesn't do while bonded to the Pebble Android app. The corrected filter:

- **`BluetoothDeviceFilter`** (classic BT) — Pebble pairs via classic BT, not BLE.
- **`setAddress(macAddress)`** — pre-populated with the MAC extracted from PebbleKit's `WatchIdentifier.toString()`. With an explicit MAC, the system dialog shows the bonded device without performing a discovery scan, sidestepping the BLE-advertising requirement entirely.
- **`AssociationRequest.Builder.setDeviceProfile(DEVICE_PROFILE_WATCH)`** (API 30+) — clarifies the dialog's intent and bundles watch-appropriate permissions.

Matches the approach Gadgetbridge uses in its `BondingUtil` for Pebble (verified by reading the source on Codeberg).

**Implementation notes:**
- `CdmManager.isPaired` returns true if `myAssociations.size > 0` (API 33+) / `associations.size > 0` (API 26-32).
- The `paired` state in MainActivity is re-read in `onCreate`, `onResume`, and the pairing launcher's result callback.
- `handleStop` doesn't gate on CDM pairing — short runs still work via the FGS window, so refusing-without-CDM would be a regression. A `Log.w` warns when no pairing is present, surfacing the "long-run stop will fail" condition in logcat.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` + the FGS promotion stay. CDM handles BAL; FGS handles OS-kill resistance during long runs. Separable concerns.

**Alternatives considered and rejected (this time):**
- *PendingIntent with creator/sender BAL opt-in*: the BAL_BLOCK log's hypothetical fields (`resultIfPiCreatorAllowsBal: BAL_BLOCK`) showed PI opt-in alone wouldn't change the result — PI mechanisms need to be combined with one of the 13 conditions, of which #11 (CDM) is the only one that fits.
- *Switch `foregroundServiceType` to `location` or `mediaPlayback`*: those types have permanent BAL exemption but require permission claims we don't legitimately use. Play Store / F-Droid would flag.
- *`NotificationListenerService`*: not in the BAL-exempt list. Granting it doesn't bypass BAL.
- *Full-screen-intent notification*: user-visible. Doesn't fit "phone in pocket" use case.

## 2026-05-14 — CDM bonded-device fast path: `setAddress` + `setSingleDevice(true)`, drop `DEVICE_PROFILE_WATCH` (supersedes filter shape in the prior 2026-05-14 entry)

**Decision:** The CDM `AssociationRequest` uses a classic `BluetoothDeviceFilter` with `setAddress(pebbleMac)` and `AssociationRequest.Builder.setSingleDevice(true)`. **No `setDeviceProfile(DEVICE_PROFILE_WATCH)`.** No BLE filter, no name pattern, no scan-side configuration. `CdmManager.requestPairing` refuses to dispatch with a `Log.w` if `pebbleMac` is null (PebbleKit hasn't reported a connected watch yet); the prior code's name-pattern fallback was dropped because it doesn't trigger the fast path and produces a dialog hang.

**Rationale:** Empirical: with `DEVICE_PROFILE_WATCH` in place the CDM dialog stuck at "Looking for a watch…" for several minutes, and `dumpsys bluetooth_manager` confirmed it was running a 0-result BLE scan. A 10-second unfiltered `BluetoothLeScanner` probe caught ~50 nearby BLE devices but never the Pebble, confirming the watch is GAP-silent while bonded to `coredevices.coreapp`. From that we initially concluded CDM was fundamentally impossible.

Then we re-read AOSP source. `CompanionDeviceDiscoveryService.checkBoundDevicesIfNeeded()` (`packages/CompanionDeviceManager/src/com/android/companiondevicemanager/CompanionDeviceDiscoveryService.java`) is the bonded-device fast path: when **all** of (a) a classic `BluetoothDeviceFilter` is present, (b) the filter has `setAddress(...)` set, (c) `request.isSingleDevice()` is true, the discovery service skips scanning and pulls candidates straight from `BluetoothAdapter.getBondedDevices()`. Code, quoted:

```java
if (btFilters.isEmpty() || !request.isSingleDevice()) return false;
final BluetoothDeviceFilter singleMacAddressFilter =
    find(btFilters, filter -> !TextUtils.isEmpty(filter.getAddress()));
if (singleMacAddressFilter == null) return false;
```

`DEVICE_PROFILE_WATCH` overrides this path with a watch-flavored dialog that always scans. The prior entry's filter had (a)+(b)+(c) but the profile setting was nullifying them.

**The four filter attempts that didn't work (so the next person doesn't redo them):**

| Attempt | Filter | `setSingleDevice` | Hits fast path? |
|---|---|---|---|
| 1 | `BluetoothLeDeviceFilter` (with or without ScanFilter+address) | n/a | No — LE filter forces a BLE scan path; the bonded list is ignored. |
| 2 | `BluetoothDeviceFilter` (classic) no fields | false | No — without an address, fast path is skipped. |
| 3 | `BluetoothDeviceFilter.setDeviceProfile(DEVICE_PROFILE_WATCH)` + `setAddress` + `setSingleDevice(true)` | true | No — `DEVICE_PROFILE_WATCH` overrides the fast path. |
| 4 | `BluetoothDeviceFilter.Builder().build()` permissive | false | No — neither address nor single-device → fast path skipped. |

The address-on-filter + single-device requirement isn't documented at the public-API level — only AOSP source surfaces it. PebbleKitAndroid2 has no BAL guidance; the Pebble Android app (`coredevices.coreapp`, source at `github.com/coredevices/mobileapp`) exposes no IPC for relaying foreground intents either.

**Other changes in this commit:**
- Manifest drops `BLUETOOTH_SCAN` (and its `neverForLocation` flag) — the bonded fast path doesn't scan, so the permission is unnecessary.
- `MainActivity` drops `BleProbe` (diagnostic-only) and its `BLUETOOTH_SCAN` runtime request.
- `build.gradle.kts` drops `buildConfig = true` — was added for a `BuildConfig.DEBUG` gate on the BleProbe.
- `CdmManager.requestPairing` now returns `Boolean` (true on dispatch, false on prerequisite failure) instead of failing silently.

**Verification:** real-hardware run on Android 14+ — CDM dialog opens, shows the Pebble immediately (no spinner), tap Allow, Home shows ✓ Paired. Start a run, pocket the phone for >30 s, press the watch's stop sequence — watch transitions to run-summary and `adb logcat` shows `BAL_ALLOW_ALLOWLISTED_COMPONENT` on the `publicapi.StopRecording` dispatch.

**Alternatives considered and rejected (this round):**
- *Disconnect-pair-reconnect dance* (user disables Pebble app's BT connection so the watch starts advertising, run CDM, reconnect). Workable but ugly UX; obsolete now that the fast path works.
- *Full-screen intent notification as BAL workaround*: user-tap-driven; defeats "phone in pocket". Kept in back-pocket as a fallback if the fast path turns out to be OEM-modified on some Android variants.

## 2026-05-14 — Bigger watchapp text

**Decision:** Bump every visible glyph on the watchapp up at least one size class. Concrete changes:

- **idle** prompt `GOTHIC_18` → `GOTHIC_24` ("Start a run on your phone" now wraps to 2 lines instead of 3).
- **active-run** labels `GOTHIC_14` → `GOTHIC_18`; PACE / CADENCE values `GOTHIC_28_BOLD` → `BITHAM_30_BLACK` (heavier weight, slightly taller); DIST / TIME values `GOTHIC_24_BOLD` → `GOTHIC_28_BOLD`. Cell heights expanded to match.
- **stop-confirm** "Stop run?" `GOTHIC_28_BOLD` → `BITHAM_42_BOLD`. The previously-empty 200×112 region between the two button-hint icons hosts the larger glyph.
- **stopping** "Stopping…" `GOTHIC_24_BOLD` → `GOTHIC_28_BOLD`. The error variant ("Couldn't stop. / Up = retry / Back = ok") still fits in three lines.
- **run-summary** title `GOTHIC_18_BOLD` → `GOTHIC_28_BOLD`; labels `GOTHIC_14` → `GOTHIC_18`; values `GOTHIC_18_BOLD` → `GOTHIC_28_BOLD`; `ROW_STRIDE` 38 → 44 to accommodate the taller cells; `LABEL_W` shrunk from 120 to 108 so larger labels don't run into the value column.

**Rationale:** User feedback — text was too small to read at arm's length while running or walking, and there was substantial unused vertical space on every screen (most obvious on run-summary, which had ~half the screen empty). Active-run gets the most attention since that's the screen the user looks at mid-run; run-summary gets the second-biggest bump since the user is stopped and reading the final stats.

No protocol or behavior changes. RAM footprint moves from ~7747 to ~8433 bytes (still well under the 128 KB Pebble app budget).

## 2026-05-14 — Robust stop handoff via RUN_STOPPED ack

**Decision:** Add `KEY_RUN_STOPPED` (key 111, companion → watch). The companion sends it after every `OpenTracksApi.stopRecording` dispatch — both when handling `CMD_STOP` from the watch and when the user taps Stop Run in the companion. A new `screens/stopping.{c,h}` screen is inserted between stop-confirm and run-summary; it sends `CMD_STOP`, displays "Stopping…", and waits for `RUN_STOPPED` before pushing run-summary. On a 10 s timeout it shows an error state with Up=retry / Back=fall-through-to-summary. `active_run.c`'s inbox handler also catches `RUN_STOPPED` so companion-initiated stops drop the watchapp's active-run screen to the summary directly.

**Rationale:** The previous flow was fire-and-forget: stop-confirm sent `CMD_STOP` and immediately showed run-summary regardless of whether the message reached the phone or OpenTracks honored it. User reported observing OpenTracks still recording on the phone while the watch showed run-summary — the watch UI was lying. Three failure modes silently caused this: BT drops, BAL-blocked intent dispatch, and OpenTracks itself ignoring the StopRecording intent. With the explicit ack the watch surfaces all three as the timeout error, prompting retry or manual verification on the phone, rather than misleading the user.

The same key also resolves the previously-open companion-initiated stop TODO — the watch now transitions out of active-run on its own when the user taps Stop Run on the companion.

**Implementation notes:**
- `app_message.h` + `Keys.kt`: define key 111 `RUN_STOPPED`.
- `PebbleMessenger.sendRunStopped`: mirrors `sendRunStarted`.
- `PebbleListenerService.handleStop`: after `stopRecording` + demote + `RunSession.clear()`, fires `sendRunStopped` on `coroutineScope`.
- `MainActivity.onStopTapped`: mirrors that — fires `sendRunStopped` after `stopRecording`.
- `screens/stopping.{c,h}`: new transition screen with a two-state machine (STOPPING → ERROR on 10 s timeout). On ack, calls `run_summary_show()` and removes active-run from the stack.
- `stop_confirm.c`: Up handler now pushes `stopping_show()` and removes self; `active_run` stays in the stack as a fall-back during the wait.
- `active_run.c`: inbox handler checks `KEY_RUN_STOPPED` first; on receipt pushes run-summary and removes self, skipping metric processing.
- Spec §4.2 intro bumped to "Five screens"; new §4.2.4 "Stopping" inserted; old §4.2.4 "Run summary" renumbered to §4.2.5; §7.2 gains the `RUN_STOPPED` row.

**Alternatives considered:**
- *Watch-side silent retry every 3 s before showing an error* — adds complexity and burns radio without clear benefit if the ack mechanism is reliable. The user can always press Up to retry from the error state.
- *Reuse stop-confirm's window for the "Stopping…" state* — state machine creep; the icons would have to be hidden, click handlers rebound. A separate screen has cleaner lifecycle.
- *Have `handleStop` only send `RUN_STOPPED` when `stopRecording` returns true* — we tried this approach mentally but rejected it: the intent dispatch returning true is itself a "best signal we have"; OpenTracks's actual stop is async and we can't observe it directly. Sending the ack unconditionally keeps the watch's UI honest in the common case and surfaces failures via the user retrying.

## 2026-05-14 — Wire promoteToForeground from DashboardActivity

**Decision:** Resolve the orphan flagged in the prior sweep commit. `PebbleListenerService.onStartCommand` now handles a new `ACTION_PROMOTE_FOREGROUND` action by calling `promoteToForeground()`. `DashboardActivity.onCreate` fires `startForegroundService` with that action after stashing the dashboard URIs and before sending `RUN_STARTED` to the watch.

**Rationale:** Without this wiring, the service ran in plain bound state for the whole run — Android can reap bound services under memory pressure, which on long runs would silently kill our poll loop. The standard mitigation is a foreground service with an ongoing low-importance notification, per spec §5.1. The prior sweep commit removed the only caller (`handleStart`) but kept the helper around; this commit gives it back its (correctly-placed) caller.

**Why startForegroundService over a static-instance hack:**
- Standard Android pattern; survives the service not yet being bound.
- DashboardActivity is foreground when OpenTracks calls back, so the "started from foreground context" rule that gates `startForegroundService` is satisfied — no `ForegroundServiceStartNotAllowedException` risk.
- Gives us the OS-enforced 5 s deadline to call `startForeground`; the override calls `promoteToForeground` directly, well within budget.
- No new dependency on instance/lifecycle ordering between Pebble's bind and OpenTracks's callback.

**Implementation notes:**
- `PebbleListenerService.kt`: new companion-object const `ACTION_PROMOTE_FOREGROUND`, new `onStartCommand` override returning `START_NOT_STICKY` (Pebble Android rebinds on next watchapp open — that's the right re-entry trigger). Dropped the `@Suppress("unused")` annotation and orphan-TODO comment from `promoteToForeground` and its section comment.
- `DashboardActivity.kt`: adds `import android.content.Intent`, `android.os.Build`, and `PebbleListenerService`. New `startForegroundService` / `startService` branch in `onCreate` after `RunSession.active = true`.
- `BasePebbleListenerService` (PebbleKitAndroid2 1.1.0) extends `android.app.Service` directly and only overrides `onBind`; verified by inspection of the published AAR. Adding `onStartCommand` to our subclass is safe.
- Demotion path unchanged: `handleStop` calls `demoteFromForeground` on CMD_STOP, and `onDestroy` has a defensive `stopForeground` for the torn-down-mid-run case.

**Alternatives considered:**
- *Static `liveInstance: PebbleListenerService?` + `promoteForRun()` companion method.* Simpler in line count but races the Pebble Android app's bind callback — if `DashboardActivity` runs before `onCreate` fires, `liveInstance` is null and we silently skip promotion. `startForegroundService` removes the race.
- *Bind from DashboardActivity and call promoteToForeground directly via the IBinder.* Extra ServiceConnection lifecycle for no real win.

## 2026-05-14 — Sweep dead CMD_START / RUN_FAILED protocol

**Decision:** Remove key 1 (`CMD_START`) and key 111 (`RUN_FAILED`) from both watch and companion sides, including all surrounding code (`PebbleMessenger.sendRunFailed`, `PebbleListenerService.handleStart`, the `Keys.CMD_START` / `Keys.RUN_FAILED` consts, and the `KEY_CMD_START` / `KEY_RUN_FAILED` `#define`s in `watchapp/src/c/app_message.h`). Spec §7's protocol table is collapsed to one Watch→Companion row (`CMD_STOP`) and four Companion→Watch rows (`RUN_STARTED`, `PACE_CURRENT`, `TIME`, `DISTANCE`). Numbers 1 and 111 remain pinned and unallocated.

**Rationale:** When the idle screen replaced pre-run on 2026-05-13, `CMD_START` lost its only sender. `RUN_FAILED` was its companion-side failure response, only fired from `handleStart`. Both halves became orphans. Keeping dead protocol surface around invites confusion in future work — better to delete and let `git log` carry the history.

**Implementation notes:**
- `Keys.kt`: dropped both consts; docstring's `data[KEY_CMD_START]` example switched to `data[KEY_CMD_STOP]`.
- `PebbleMessenger.kt`: removed `sendRunFailed`.
- `PebbleListenerService.kt`: removed `handleStart` + its branch in `onMessageReceived`; class-level docstring + `resolveVariant` docstring rewritten to drop "watch is the control surface" framing. `promoteToForeground` is now orphaned (was only called from `handleStart`); left in the file with `@Suppress("unused")` and a TODO comment — the intended rewire is to drive it from `DashboardActivity.onCreate` so long runs survive OS pressure (spec §5.1).
- `RunSession.kt` + `HomeScreen.kt`: comment updates only.
- `app_message.h`: dropped both `#define`s; protocol-table comment rewritten with a note that numbers 1 / 111 stay pinned.

**Side effect (deferred):** `promoteToForeground` is orphaned. Wire-up is a separate follow-up; flagged inline in the file.

**Alternatives considered:**
- *Keep the dead path for hypothetical future watch-initiated start* — premature; if that comes back it'll need fresh BAL/FGS handling anyway.
- *Hide the constants behind a feature flag* — no feature-flag mechanism exists in v0.1; over-engineering.

## 2026-05-14 — Re-implement watch-initiated start (CDM bonded-device fast path makes it tractable)

**Decision:** Restore watch-initiated start, swept on the same date in the "Sweep dead CMD_START / RUN_FAILED protocol" entry above. Re-add `KEY_CMD_START = 1` to the protocol (no `RUN_FAILED` — failure is signaled via the watch's starting-screen timeout, no extra wire surface). Idle screen's Select button now sends `CMD_START`; new `screens/starting.{c,h}` mirrors the existing `stopping.{c,h}` with a 15 s timeout (longer than stopping's 10 s — first GPS fix in OpenTracks can push the end-to-end ack past 10 s on a cold cache). Companion's `PebbleListenerService.onMessageReceived` gains a `handleStart` arm: resolves the OpenTracks variant package, requires an active CDM association (NACK with `Log.w` if not — there is no FGS BAL window to fall back on the way the stop path has), dispatches `OpenTracksApi.startRecording`. `RUN_STARTED` is still sent only from `DashboardActivity.onCreate` once OpenTracks has called us back, so the watch sees a single canonical "start completed" message regardless of who initiated.

**Rationale:** The three failures documented on 2026-05-13 ("v0.1: start runs from the companion app" entry) were variants of the same Android security gate: a backgrounded service can't dispatch `startActivity` without a BAL exemption. The CDM-association path was tried then and failed for filter-shape reasons that the 2026-05-14 bonded-device fast-path entry resolved: a classic `BluetoothDeviceFilter` with `setAddress(bondedMac)` + `setSingleDevice(true)` (and no `setDeviceProfile`) triggers AOSP's `CompanionDeviceDiscoveryService` no-scan fast path, surfacing the bonded-but-non-advertising Pebble in the dialog within a frame. With an association in place, `REQUEST_COMPANION_RUN_IN_BACKGROUND` + `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` cover the BAL condition #11 exemption ("app responds to an action the user performed on a paired companion device"). Watch-initiated stop already exercises this path successfully — start is the same dispatch surface with `StartRecording` swapped in for `StopRecording`.

**Implementation notes:**
- `watchapp/src/c/app_message.h`: re-adds `KEY_CMD_START = 1`. Header comment table documents the implicit-ack-via-timeout model (no `RUN_FAILED`).
- `watchapp/src/c/screens/icons.{c,h}`: new `icons_draw_play` — solid right-pointing triangle via `gpath_create` + `gpath_draw_filled`, allocated per call (cheap; called only on layer redraw). Drawn at 20×20 at the right-edge gutter, vertically aligned with the Select button — same column the stop-confirm screen uses for ✓/✕.
- `watchapp/src/c/screens/idle.{c,h}`: Select now wired to `select_click_handler` (send `KEY_CMD_START` + `vibes_short_pulse()` + `starting_show()`). Prompt text "Start a run on your phone" → "Press Select to start" (still `GOTHIC_24`, still wraps to two lines). New play-icon `Layer` painted via `play_icon_update_proc`. `idle.h` exposes two new helpers: `idle_arm_inbox()` (used by starting.c's Back-from-error path to put idle back in charge of inbox dispatches before popping) and `idle_get_window()` (parallels `active_run_get_window()` for future stack surgery; currently consumed by starting.c through the active-run handoff).
- `watchapp/src/c/screens/starting.{c,h}`: near-verbatim from `stopping.{c,h}`. 15 s vs. 10 s timeout. `STARTING / STARTING_ERROR` states. Success path pushes active-run + removes self (idle stays under). Back-from-error path arms idle's handler then pops to idle. Inbox handler clears in `window_unload` so trailing dispatches don't reach freed state.
- `companion/.../Keys.kt`: adds `CMD_START: UInt = 1u`.
- `companion/.../PebbleListenerService.kt`: new `handleStart`. Hard requires CDM association (vs. stop's soft warn-but-continue) because there is no FGS BAL window — we are explicitly *not yet* in FGS state at this point. `RUN_STARTED` is **not** sent from here; `DashboardActivity.onCreate` remains the canonical sender so the FGS-promotion + URI-stash + ack ordering stays a single linear path.
- `docs/specification.md`: §4.2.1 idle rewritten with the new Select binding and play-icon affordance; new §4.2.1a Starting screen; §7.1 protocol table re-adds `CMD_START` with a sentence on the implicit-ack model; §7.2 `RUN_STARTED` description updated to cover both initiation paths; §8.2 "Run start fails" rewritten to match the new starting-screen UX, with a new §8.2a for companion-initiated failures.

**Alternatives considered:**
- *Add `RUN_FAILED` (or `RUN_START_FAILED`) as an explicit negative ack* — rejected. The watch's 15 s timeout + retry is consistent with the existing stop flow, and CDM-not-paired diagnostics already live in the companion's Home screen via the "Pair Pebble for background access" button. One ack key per direction is enough.
- *Send `RUN_STARTED` from `handleStart` directly on intent-dispatch success* — rejected. The dispatch returns immediately while OpenTracks's recording-start, our URI grant, and the FGS promotion all take real time. An early ack would race the URI stash, and the watch's active-run screen would appear before the metric pipe was ready.
- *Use Up instead of Select for the start affordance, mirroring stop-confirm's Up=confirm* — rejected on user feedback; Select reads as "main action" and matches Pebble platform convention. The play icon at the Select gutter makes the binding unambiguous.
- *Drop the starting screen, rely on idle's existing RUN_STARTED handler to transition directly* — rejected. No feedback on a dispatch that fails silently (e.g. CDM not paired) — the user would press Select and stare at idle forever. Starting+timeout is the same engineering pattern as stopping+timeout for the same reason.

## 2026-05-14 — Cadence derivation on the watch (3-slot ring, 5 s polling, 15 s window)

**Decision:** Implement spec §4.3 cadence on the active-run screen via a 3-slot ring buffer of `HealthMetricStepCount` samples polled every 5 s, with `SPM = (steps_now − steps_15s_ago) × 4`. Seeded at `window_load` so the first valid SPM lands at t≈15 s; renders `---` while the ring fills, `0` when standing still, `N` SPM (no upper clamp) otherwise. Cadence is purely watch-local — not surfaced in `RunStats`, not pushed via AppMessage.

**Rationale:** Closes the last unimplemented field on active-run, and the lone outstanding watchapp feature in spec §14. The algorithm is what spec §4.3 already pins; the implementation choices are about ring sizing and warm-up timing.

Ring size = 3 (not 4). With N slots spaced 5 s apart, the oldest slot in circular order is exactly `(N − 1) × 5 s` older than the most recent write — i.e. 10 s older for N=3, 15 s older for N=4. We want a 15 s span between **the slot we read** and **the slot we're writing**, which is `5 × (writes-since-the-read-target)`. Since we read the slot we're about to overwrite, that distance is `5 × (N − 1)` writes apart in time. For a 15 s window, N − 1 = 3 ⇒ N = 4 (writes apart) — but writes happen at the END of each tick, so the read targets data that's been sitting one tick longer than the gap-between-writes suggests. Working it out: 3 slots, seed at t=0 ⇒ first SPM at t=15 s reads ring[0] = sample(0), writes ring[0] = sample(15). 4 slots gives a 20 s window. Trace fully verified before committing.

Seed-at-load (rather than waiting for the first 5 s tick) saves 5 s of `---` placeholder at the top of every run. Without the seed, first SPM lands at t=20 s. The seed costs one extra `peek_current_value` call in `window_load` — negligible.

Midnight rollover defense: `delta < 0` clamped to 0. `HealthMetricStepCount` is a daily-cumulative counter; a run straddling midnight observes a transient negative delta. Clamping produces a single zero-SPM tick before the ring repopulates with the post-midnight baseline. No upper clamp — extreme readings (200+ SPM during interval workouts) are real and a cap would mask sensor issues.

**Alternatives considered:**
- *Rolling sum across all samples in the window* — would need per-second polling and step-delta accumulation. More CPU + battery for no accuracy gain over the simple endpoints-only delta, which is what the spec specifies.
- *Faster polling (e.g. 1 s)* — would give a snappier-looking CADENCE field but is wasted granularity given that step counts at 1 Hz are quantized and the visual update is already keyed to the 5 s metric beat shared with HR and the companion pipe.
- *Skip the seed, accept a 20 s warm-up* — rejected because the spec explicitly says "15 s rolling window" and aligning the first valid SPM to that exact moment matches user expectation. The seed is one extra line of code.
- *Push cadence over AppMessage so the companion can log/persist it* — explicitly rejected by spec §4.3 ("Display locally. Not sent to companion."). HR and cadence both live entirely on the watch; only pace/distance/time are companion-derived.
- *Average cadence in `RunStats` for the run-summary screen* — rejected on spec §4.2.5 grounds, which lists DIST / TIME / AVG PACE / AVG HR only. The summary screen contract is unchanged.

## 2026-05-14 — `FLAG_ACTIVITY_MULTIPLE_TASK` on `publicapi.StartRecording` / `StopRecording`

**Decision:** `OpenTracksApi.startRecording` and `stopRecording` now dispatch their Intents with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_MULTIPLE_TASK` instead of `FLAG_ACTIVITY_NEW_TASK` alone.

**Rationale:** Watch-initiated start worked the first time after every cold OpenTracks launch but failed silently on the second start (or any start with OpenTracks's task already in recents — including the common "stop a run, immediately start another" case). adb logcat traced it to ATMS:

```
START u0 {act=...publicapi.StartRecording flg=0x10000000 ...
   cmp=...playstore/...publicapi.StartRecording (has extras)} with
   LAUNCH_MULTIPLE from uid 10456 (run.openpebble.companion.debug)
   (BAL_ALLOW_ALLOWLISTED_COMPONENT) result code=3
```

`result code=3` is `ActivityManager.START_DELIVERED_TO_TOP`. With `FLAG_ACTIVITY_NEW_TASK` alone plus a matching `taskAffinity`, Android routes the second dispatch into OpenTracks's existing task and delivers the Intent to the still-listed (finished-but-not-purged) `publicapi.StartRecording` instance at the task root via `onNewIntent`. OpenTracks's `AbstractAPIActivity` only does its work in `onCreate` (binds `TrackRecordingService`, calls `execute`, fires the Dashboard callback) and has no `onNewIntent` override, so the second Intent is silently dropped: no `startNewTrack`, no `IntentDashboardUtils.startDashboard`, no `DashboardActivity.onCreate`, no `RUN_STARTED`. The watch's `starting` screen times out to "Retry" and the user sees OpenTracks foregrounded but no run recording.

`FLAG_ACTIVITY_MULTIPLE_TASK` (in combination with `FLAG_ACTIVITY_NEW_TASK`) forces Android to always create a brand-new task instead of reusing one with matching affinity. Each dispatch gets a fresh `onCreate` invocation; the empty task is cleaned up when `AbstractAPIActivity` calls `finish()` after `execute()`. `DashboardActivity`'s existing `OpenTracksApi.openApp` call still foregrounds OpenTracks's main UI after the run actually starts, so end-state UX is unchanged.

Applied symmetrically to `stopRecording` because the same class of bug would surface on a watch-initiated stop following an externally-started OpenTracks run (the `publicapi.StopRecording` instance from the previous stop can linger the same way). Not currently reachable in the watch-driven flow but cheap to harden.

**Alternatives considered:**
- *`FLAG_ACTIVITY_CLEAR_TASK`* — also forces a fresh `onCreate`, but tears down OpenTracks's existing UI task before the launch. Worse UX: the user's OpenTracks track-list / settings state would be wiped on every start.
- *`FLAG_ACTIVITY_NEW_DOCUMENT`* — the API-21+ document-task model. Conceptually similar to `MULTIPLE_TASK` but adds per-Intent document-identity semantics that aren't relevant for fire-and-forget publicapi calls. `MULTIPLE_TASK` is the smaller, more direct fix.
- *Detect "already recording" in `handleStart` and short-circuit by sending `RUN_STARTED` directly (no `StartRecording` dispatch)* — viable when `RunSession.active` is true, but only covers the case where our companion knows about the active run. The root-cause bug is in Intent dispatch routing, and fixing it at the dispatch level also helps any future code path that needs to fire publicapi Intents reliably.
- *Override the listener service's `taskAffinity` to mismatch OpenTracks's* — would dodge the affinity-match reuse, but our service is not the source of the affinity match (it's `publicapi.StartRecording`'s declared affinity that controls task assignment). No effect.

## 2026-05-14 — Drop the `POST_NOTIFICATIONS` runtime permission

**Decision:** Remove the `POST_NOTIFICATIONS` `<uses-permission>` from the manifest and the corresponding branch in `MainActivity.maybeRequestRuntimePermissions`. The `PebbleListenerService` notification object is untouched — it's still built and passed to `startForeground` because Android's FGS contract requires it.

**Rationale:** The permission only governs *visibility* of the notification on API 33+; the FGS itself works regardless. We were paying for one extra first-launch permission dialog just to make our "Recording — see your watch" notification visible alongside OpenTracks's own ongoing recording notification, which is functionally the same thing for the user. Dropping the request halves the first-launch dialog count (from BLUETOOTH_CONNECT + POST_NOTIFICATIONS to just BLUETOOTH_CONNECT) and de-clutters the shade during a run. The FGS — the actual mechanism keeping us alive when the watchapp is closed during a long backgrounded run — is unchanged.

Users who *do* want our tap-to-open-Home affordance can enable our Recording channel via system notification settings (App info → Notifications → Recording). No in-app surface for this; it's a minority case and the system path is the standard way to grant per-channel notification visibility.

**Alternatives considered:**
- *Drop the FGS entirely* — gives up OOM-killer immunity. On the explicit spec §4.2.2 "Back exits watchapp, run keeps recording" path, the service would be reaped on aggressive vendors (Samsung, Xiaomi) after minutes. Watch metrics freeze until next watchapp open. The actual GPX recording is unaffected (OpenTracks's own FGS handles that), but the watch-as-display promise breaks. Rejected — the visibility savings aren't worth that regression.
- *Keep the request but auto-deny / preselect-deny* — Android doesn't expose an "ask but recommend deny" affordance. Either we ask (granting becomes the default obvious answer) or we don't.
- *Build the notification object lazily / conditionally* — `startForeground` requires it unconditionally. No path to "no notification at all" while keeping FGS.
- *Use a different `foregroundServiceType`* that doesn't require a notification — none of the FGS types skip the Notification requirement. `connectedDevice` is the right type for our use anyway.

## 2026-05-13 — Start Run foregrounds OpenTracks; defer track name to its setting

**Decision:** When the user taps Start Run on the companion, the companion now (in addition to launching the watchapp and dispatching `publicapi.StartRecording`) calls `OpenTracksApi.openApp` to bring OpenTracks's main activity to the foreground. The `TRACK_NAME` extra is removed from the StartRecording intent; OpenTracks's own "Default track name" preference (Date ISO 8601 / Date local / Number) applies instead. `TRACK_CATEGORY` and `TRACK_ICON` ("running") are preserved.

**Rationale:** One-tap "start the run and put the phone away" UX — previously the user landed on the companion's idle Home screen and had to navigate to OpenTracks manually to confirm recording was actually running. Deferring the track name to OpenTracks's setting respects the user's configuration without requiring privileged SharedPreference reads (a third-party app cannot read another app's `SharedPreferences` on modern Android; OpenTracks does not expose its preferences via a ContentProvider).

**Implementation notes:**
- `OpenTracksApi.kt`: removed `EXTRA_TRACK_NAME` constant and the `putExtra` line. Class header docstring updated to explain the omission.
- `DashboardActivity.onCreate`: after stashing URIs in `RunSession` and dispatching `sendRunStarted`, calls `OpenTracksApi.openApp` to foreground OpenTracks. Done from here rather than `MainActivity.onStartTapped` because OpenTracks's callback to `DashboardActivity` races a launcher Intent fired from `MainActivity` — the race lands `DashboardActivity` on top of OpenTracks (the "Recording — see your watch" screen the user reported seeing in the first attempt). Firing `openApp` from inside the callback inverts the order: OpenTracks foregrounds *after* its callback to us has been delivered, with `DashboardActivity` underneath in our task (URI grants preserved per its class-header note).
- `MainActivity.onStartTapped`: now just `startWatchapp` + `startRecording`. The earlier-iteration `openApp` call there is removed.
- Spec §5.2.2 updated with the corrected flow.
- `openApp` was already defined and used by `FirstLaunchScreen`'s "Open OpenTracks settings" button; no helper changes.

**Alternatives considered:**
- *Pass ISO 8601 explicitly from companion* — overrides whatever the user configured in OpenTracks's settings.
- *Pass localized date from companion* — same problem; locale-coupled which complicates testing.
- *Read OpenTracks's `track_name_key` SharedPreference via reflection or a content provider hack* — preferences are private; fragile, version-coupled.
- *Launch `de.dennisguse.opentracks.TrackRecordingActivity` directly* — that activity is not exported by OpenTracks (no intent-filter); only `publicapi.StartRecording` / `StopRecording` / `CreateMarker` are. The launcher Intent + OpenTracks's own resume-active-recording behavior is the supported path.

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

## 2026-05-15 — DashboardActivity Intent validation

**Decision:** Reject incoming Dashboard intents in `DashboardActivity.onCreate` unless (a) `getReferrer()` resolves to a package in `OpenTracksVariant.PROBE_ORDER`, (b) the Track / TrackPoints URIs have an authority matching `de.dennisguse.opentracks*.publicapi` with the expected `/dashboard/tracks/` or `/dashboard/trackpoints/` path prefix, and (c) the intent has `FLAG_GRANT_READ_URI_PERMISSION` set. On failure: `Log.w` + `finish()` before any RunSession mutation.

**Rationale:** The Activity is exported (OpenTracks dispatches via `setComponent`, requires `exported=true`). Pre-fix, any installed app could push arbitrary content URIs into `RunSession.trackUri`/`trackPointsUri`; the listener service's 5 s poll loop would then read them. Blast radius limited (no network, no exfil) but gratuitous attack surface and explicit pre-publish TODO:SECURITY. Closes the corresponding TODO from this log's TODO section.

**Alternatives considered:**
- *Drop `exported="true"`*: rejected — OpenTracks's `setComponent` dispatch requires it.
- *Add `android:permission` on the Activity*: rejected — OpenTracks holds no permission of ours; would break the integration.
- *`Binder.getCallingUid()`*: returns -1 for `startActivity` (no IPC). `getReferrer()` is the documented API for exported, no-result Activities.
- *Check just the URI shape, skip the caller check*: rejected — a malicious app could construct a valid-looking URI but only OpenTracks variants will have actually issued one with a working grant; the caller check is the cheapest defense against forged-URI calls that happen to coincide with a real Track id.

**Implementation notes:**
- `DashboardActivity.kt`: added private helpers `isCallerOpenTracks` (uses `Activity.getReferrer()`, checks `android-app://<pkg>` against `OpenTracksVariant.PROBE_ORDER`) and `isValidDashboardUri(uri, expectedPathPrefix)`. Guard inserted before the existing `RunSession.trackUri = trackUri` assignment.
- `getReferrer()` returns the launcher-supplied package on `startActivity`; `EXTRA_REFERRER_NAME` is system-signed-only, so the host is trustworthy under the v0.1 threat model (non-privileged installed apps). Root-equivalent attackers fall outside the model — they can install and trust any app anyway.

## 2026-05-15 — DashboardActivity validation: corrected URI authority (corrigendum)

**Decision:** Fix the authority half of the validator landed earlier today (the path half turned out to be correct as originally written): authority suffix is `<applicationId>.content`, **not** `.publicapi`. Also add an `intent.action == "Intent.OpenTracks-Dashboard"` positive check. Track URI path stays `/dashboard/tracks/<id>` and TrackPoints stays `/dashboard/trackpoints/<id>` — these are what OpenTracks actually emits, contrary to a misread of its source during diagnosis.

**Rationale:** First repro of the watch-initiated start path post-validator (logcat 11:35:57) showed `DashboardActivity` launching with the correct payload but the validator rejecting. Initial diagnosis blamed both authority AND path on a source read that conflated OpenTracks's *internal* `TracksColumns.CONTENT_URI` paths (`/tracks`, `/trackpoints/trackid`) with its *public* Dashboard API URI builder. The second repro (13:54:14 logcat) made the actual shape unambiguous:
```
trackUri=content://de.dennisguse.opentracks.playstore.content/dashboard/tracks/138
trackPointsUri=content://de.dennisguse.opentracks.playstore.content/dashboard/trackpoints/138
```
Authority confirmed as `<applicationId>.content`; paths confirmed as `/dashboard/tracks/<id>` and `/dashboard/trackpoints/<id>` (no `/trackid/` infix). The original `DashboardActivity.kt:36-37` doc comments had the paths right — only the validator's incorrect `.publicapi` authority needed fixing.

**Alternatives considered:**
- *Drop URI authority/path validation entirely*: rejected — the URI grant itself blocks reads of provider URIs the caller doesn't own, but the cheap shape check rejects obvious spoofs before the cursor read and keeps the failure mode loud.
- *Switch to a regex authority match*: `^de\.dennisguse\.opentracks(\.playstore|\.nightly)?(\.debug)?\.content$` would be tighter; `startsWith + endsWith` is simpler and equivalent for our use.

**Implementation notes:**
- `DashboardActivity.kt`: `isValidDashboardUri` matches `.content` authority; guard uses `/dashboard/tracks/` and `/dashboard/trackpoints/` (the originals); new `ACTION_DASHBOARD` const + action check; class doc comments updated to reflect the verified-in-field shapes, with an explicit note distinguishing the public Dashboard API paths from OpenTracks's internal `TracksColumns` paths so the conflation isn't made again.
- No change to `isCallerOpenTracks` — OpenTracks dispatches via `this.startActivity()` *before* `finish()` per source, so the referrer propagates normally (confirmed by `caller=android-app://de.dennisguse.opentracks.playstore` in the 13:54 logcat).
- **Known gap:** `OpenTracksVariant.PROBE_ORDER` lacks `<release-variant>.debug` applicationIds. Users testing against a debug-built OpenTracks would hit a rejection. Out of scope for this branch — filed in `docs/pre-release.md`.
- **Dev-workflow gotcha (diagnosed same session):** `make companion-install` runs `adb uninstall` first, which drops the package's CDM associations. After every reinstall the user must re-pair via the companion's "Pair Pebble for background access" button before the watch-initiated start path works. Filed as **I5** in `docs/pre-release.md`.

## 2026-05-15 — Android 15 edge-to-edge insets

**Decision:** Call `enableEdgeToEdge()` in `MainActivity.onCreate` and `DashboardActivity.onCreate` (before `super.onCreate` for the Activity-extends-ComponentActivity case). Apply `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)` between `fillMaxSize()` and the existing `padding(24.dp)` on the outer `Column` of both `HomeScreen` and `FirstLaunchScreen`. For `DashboardActivity`'s legacy `LinearLayout`+`TextView` tree, install `ViewCompat.setOnApplyWindowInsetsListener` on the root layout and additively pad with `systemBars() or displayCutout()` insets on top of the existing 48px content padding. Bumped `compose-bom` `2024.09.03 → 2026.05.00` and `activity-compose` `1.9.2 → 1.13.0` (M8) in the same diff per audit recommendation.

**Rationale:** `targetSdk=35` (`build.gradle.kts:21`, kept on 35 per spec §5.1) forces edge-to-edge on Android 15+. Pre-fix, no `enableEdgeToEdge()` call existed and no inset modifiers were applied; the Home screen's primary Start/Stop button drew under the gesture bar (audit C1, the last remaining Critical / publish blocker). M8 bundled because (a) the new BOM ships the insets-handling APIs we depend on with current bug fixes, and (b) `enableEdgeToEdge()` itself is in `androidx.activity:activity-compose` ≥ 1.9, but the older 1.9.2 predates several light/dark scrim fixes the new 1.13.0 ships.

**Alternatives considered:**
- *`WindowCompat.setDecorFitsSystemWindows(window, true)` opt-out on `DashboardActivity`*: rejected — no-op when edge-to-edge is enforced under targetSdk=35; Google's own [Android 15 behavior-changes doc](https://developer.android.com/about/versions/15/behavior-changes-15#edge-to-edge) is explicit. Must handle insets properly.
- *Compose port of `DashboardActivity`'s view tree*: deferred — listed as optional simplification S5 in `docs/pre-release.md`; the legacy `setOnApplyWindowInsetsListener` path is ~10 lines and the screen is rarely seen.
- *`Scaffold` with default insets in `MainActivity`*: rejected — `HomeScreen`/`FirstLaunchScreen` are not Scaffold-shaped (no top app bar, no FAB), so the explicit `windowInsetsPadding(safeDrawing)` on the outer `Column` is more direct.
- *`safeContent` instead of `safeDrawing` insets*: `safeDrawing` is the canonical choice for content that should never be drawn under decoration; `safeContent` additionally excludes IME, which we don't need for these screens.

**Implementation notes:**
- `MainActivity.kt`: `enableEdgeToEdge()` called as the first line of `onCreate` before `super` is the documented pattern (per [Compose edge-to-edge codelab](https://developer.android.com/codelabs/edge-to-edge)).
- `HomeScreen.kt` / `FirstLaunchScreen.kt`: order matters — `windowInsetsPadding` must come before `padding(24.dp)` so insets are consumed first; otherwise the 24dp gets stacked under the bars and the safe area shrinks by 24dp.
- `DashboardActivity.kt`: returns `WindowInsetsCompat.CONSUMED` to halt traversal — this Activity has only one root view, no nested insets-aware children. Combines `systemBars() or displayCutout()` so cutout-rich Pixel 9-class devices get correct horizontal padding too.
- Manual validation deferred to user (per CLAUDE.md "Never merge without my manual validation"): install on Android 15+ device, confirm Start/Stop button is above the gesture bar, status rows are clear of the status bar, and the dashboard fallback text stays centered without clipping.

## 2026-05-15 — Listener service reliability (audit H1 + H3)

**Decision:** Move `PebbleListenerService`'s 5 s Dashboard poll off the main looper onto `Dispatchers.IO`, and add a 3-strike reconnect to `PebbleMessenger` so a stuck `DefaultPebbleSender` rebuilds its bound-service connection automatically.

**Rationale:**
- **H1 — main-thread `ContentResolver` I/O.** The poll loop used `Handler(Looper.getMainLooper())` + a posted `Runnable`; `readTrack` / `readLatestTrackPoint` issue cross-process `ContentResolver.query` calls against OpenTracks's `CustomContentProvider`. PebbleKit's bound-service callbacks (`onMessageReceived`, `onAppOpened`, `onAppClosed`) also dispatch on the service's main looper (`BasePebbleListenerService` from PebbleKitAndroid2 v1.1.0). A slow query would queue them, hurting CMD_START/CMD_STOP ack latency. Service has no UI so user-visible ANR risk is nil, but it's the strict-mode antipattern called out by [Android — Coroutines best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices). The fix uses the `coroutineScope` already supplied by `BasePebbleListenerService` (same scope as `pushMetrics`/`sendRunStopped`/`onAppOpened` already use); cancellation falls out for free on service destroy. `ContentObserver(null)` lets `onChange` deliver on whichever thread the provider notifies on — readers are safe off-main (only cursor I/O + `@Volatile` writes + `coroutineScope.launch`).
- **H3 — silent persistent send failure.** `PebbleMessenger.send` and `startWatchapp` logged a non-Success `TransmissionResult` (or a `null` "Pebble app not reachable" result) and returned. The cached `DefaultPebbleSender` stayed bound to a potentially-broken connection; subsequent sends hit the same broken state. Watch's 30 s stale-dim was the only UX signal. Three consecutive non-successes now trigger `close()`, dropping the cached sender so the next call rebuilds the binder via `getOrCreate`. Covers the "Pebble Android app was killed / crashed" case without adding a retry/backoff scheme — retry stays at the screen layer per spec §4.5 (`starting`/`stopping` screens own timeouts). Counter is an `AtomicInteger` because send/startWatchapp are launched concurrently from the listener service's `coroutineScope` (e.g. one in-flight `sendMetrics` + a `sendRunStarted` on `onAppOpened`).

**Alternatives considered:**
- *H1 — keep `Handler` but move just the reads to a worker thread*: rejected — leaves the dispatcher concern split across two mechanisms; the coroutine scope already exists and the `while (isActive) { delay }` pattern is the documented coroutine-equivalent of `postDelayed`.
- *H1 — `WorkManager`-style periodic job*: rejected — periodicity is bound to the FGS lifetime (~minutes), well below `WorkManager`'s 15 min minimum and unrelated to its scheduling semantics.
- *H3 — exponential backoff + retry inside the messenger*: rejected — duplicates the screen-level retry surface (`starting`/`stopping` already time out and re-arm), would mask the connection-dead state instead of recovering it, and contradicts the "single in-flight AppMessage, no library-level retry" invariant in `docs/pre-release.md` "What NOT to change".
- *H3 — close immediately on first failure*: rejected — a single non-Success is common in real BT conditions (transient peer-unreachable). Three is the smallest number that distinguishes "flap" from "stuck"; ~15 s of dropped sends in the worst case before reconnect.

**Implementation notes:**
- The `ContentObserver(null)` constructor accepts `null` for the handler parameter; the [`ContentObserver` reference](https://developer.android.com/reference/android/database/ContentObserver) documents this delivers `onChange` on the notifying thread, which for OpenTracks's provider is its own binder pool — perfectly fine for our read-only cursor work.
- `recordResult(success)` resets the counter on every success and clears it on `close()` so an externally-driven close (e.g. `PebbleListenerService.onDestroy`) doesn't leave a stale counter to bite the next run.
- No new dependencies. `java.util.concurrent.atomic.AtomicInteger` is JDK stdlib; `kotlinx.coroutines.Dispatchers` / `delay` / `isActive` are already pulled in via `kotlinx-coroutines-android` (used elsewhere in this file).
- Manual validation deferred to user per CLAUDE.md: smoke test per `docs/pre-release.md` §"How to validate" entries for H1 and H3.

## 2026-05-15 — URI grant delegated to FGS via ClipData (audit H2)

**Decision:** `DashboardActivity` no longer holds the OpenTracks URI grant alive via its task-in-recents presence. Instead, it attaches the validated Track + TrackPoints URIs as `ClipData` on the `ACTION_PROMOTE_FOREGROUND` start intent (with `FLAG_GRANT_READ_URI_PERMISSION` set), re-delegating the grant from the Activity to `PebbleListenerService`. The service owns the URIs as per-instance `@Volatile` fields; `RunSession.trackUri` / `trackPointsUri` are removed. `ensureObservers()` collapses to a one-shot call from `onStartCommand` (the poll loop just reads).

**Rationale:**
- **H2 root cause.** Pre-fix, the grant lifetime was tied to `DashboardActivity`'s task remaining in recents. Mid-run, the user is in OpenTracks's UI; swiping us from recents (or even letting the OS reap the task under memory pressure) revokes the grant. The next `contentResolver.query` from `PebbleListenerService` then throws `SecurityException`, gets caught silently at the poll loop's blanket `Exception` catch, metrics freeze, and the watch dims to `---` after 30 s. The audit's option (a) — catch `SecurityException` and re-foreground OpenTracks — was a defensive band-aid that left the grant-lifetime invariant unchanged and required the user to interact with OpenTracks to recover; option (b) eliminates the invariant entirely.
- **Why the FGS is a valid grant target.** Per Android's URI-permission model, any component capable of receiving an `Intent` can be a grant target when the sender holds the grant. A `startForegroundService` call delivers the intent (with ClipData + the grant flag) to `Service.onStartCommand`; the service component then holds the grant for as long as it stays alive — independent of the Activity that delegated it. Documented at [Sharing simple data — extending permissions](https://developer.android.com/training/secure-file-sharing/share-file#GrantPermissions).
- **Why URIs moved off `RunSession`.** With the service as sole reader (and sole grant holder), keeping them on `RunSession` would have left a stale cross-component invariant for zero benefit. Per-instance fields on `PebbleListenerService` colocate ownership with lifecycle, and `ensureObservers()` becomes a one-shot from `onStartCommand` rather than a poll-tick recheck.
- **Why `ensureObservers()` logic is preserved.** It still handles a mid-process second promotion (different URIs from a back-to-back run) by unregister-then-register. Clearing on stop now goes through `unregisterObserversIfAny()` + nulling the per-instance fields directly in `handleStop`, not through `ensureObservers` — cleaner separation.

**Alternatives considered:**
- *Option (a) — catch `SecurityException` and re-foreground OpenTracks.* Rejected. Doesn't address the root cause (grant lifetime still tied to Activity task); requires user interaction to recover (re-foregrounding OpenTracks is user-visible); and adds a defensive code path that would silently rot when the underlying lifetime issue resurfaces in a future Android release.
- *Keep URIs on `RunSession` and also attach them to the promotion intent.* Rejected — preserves two sources of truth (RunSession field vs ClipData) for one piece of state. Whichever the service reads, the other is dead weight that will eventually drift.
- *Use a bound-service callback instead of an FGS-start intent to deliver URIs.* Rejected — `PebbleListenerService` already extends `BasePebbleListenerService` and is bound by the Pebble Android app, not by the companion. The existing `ACTION_PROMOTE_FOREGROUND` start intent is the natural vehicle for grant transfer and already gates the foreground promotion.

**Implementation notes:**
- The intent's ClipData carries `ClipData.newRawUri("dashboard-uris", trackUri)` with `addItem(ClipData.Item(trackPointsUri))` for the second URI. The label is arbitrary but logged in some traces; "dashboard-uris" makes it greppable.
- `onStartCommand` validates the inbound ClipData defensively (both URIs present, grant flag set) — if anything is wrong it still calls `promoteToForeground()` to satisfy the 5 s OS deadline, but skips the URI stash; the poll loop then idles. This shouldn't fire in practice because `DashboardActivity` only sends well-formed promotion intents, but a malformed intent crashing `startForegroundService` would be a worse failure mode than an empty poll loop.
- `handleStop` now nulls the per-instance URIs and calls `unregisterObserversIfAny()` in addition to the existing `RunSession.clear()` + `demoteFromForeground()`. Without this, the poll loop would keep firing `readTrack`/`readLatestTrackPoint` against revoked URIs (the FGS demotion drops the grant) until the next service teardown.
- `DashboardActivity.onDestroy` still calls `RunSession.clear()`, but `clear()` now only resets `active = false` (the URIs aren't on `RunSession` anymore). The Activity's destruction is no longer load-bearing for the metric pipe — confirmed by the H2 reproducer in `docs/pre-release.md`.
- No new dependencies. `ClipData`, `Intent.FLAG_GRANT_READ_URI_PERMISSION`, and `startForegroundService` are framework APIs already in use.
- Manual validation deferred to user per CLAUDE.md: install on Android 15+, run the H2 reproducer (start a run, swipe OpenPebbleRun from recents, confirm metrics keep ticking on the watch and `logcat -s PebbleListenerService` keeps logging Track/TrackPoint reads). Also smoke-test the baseline happy-path run-flow and the watch-initiated start path — both go through the same `DashboardActivity.onCreate` and exercise the new ClipData attachment.


## 2026-05-16 — Pace smoothing: 15 s rolling window over Track deltas (reversing 2026-05-13)

**Decision:** Replace the instantaneous `TrackPoint.speed` → pace conversion with a 15 s rolling window over cumulative `Track.movingtime` / `Track.totaldistance` deltas. New `PaceWindow` class (`companion/metrics/PaceWindow.kt`) holds the buffer; `TrackStats.paceFromMeanSpeed` does the arithmetic. The poll loop no longer reads TrackPoints — `readLatestTrackPoint()`, the `trackPointsObserver`, `observedTrackPointsUri`, `trackPointsUri`, and the `COL_SPEED`/`COL_TIME` constants are removed from `PebbleListenerService`. `DashboardActivity` still validates both Dashboard URIs that OpenTracks sends and still forwards both in the FGS ClipData (forward-compat), but the service silently ignores `ClipData[1]`.

**Rationale:** Field test (2026-05-16 run) showed pace was jittery enough to be unusable — OpenTracks's per-point speed filtering doesn't smooth the GPS noise the way an averaged window does. The 2026-05-13 "OpenTracks is source of truth" argument was preempting a non-issue; the actual issue is unsmoothed instantaneous speeds. HR, time, and distance worked correctly in the same run.

**Why moving-time as window axis:** `Track.movingtime` excludes paused periods, so the window naturally slides only on actual running time. No special pause-handling code, and pace stays consistent with the displayed TIME field (which is derived from the same column).

**Why endpoint delta:** with cumulative counters, `(newest.dist − oldest.dist) / (newest.time − oldest.time)` is mathematically identical to the time-weighted mean speed over the spanned interval. No per-sample integration buys anything.

**Window reset:** in `onStartCommand` on every fresh PROMOTE_FOREGROUND URI stash and in `handleStop` after the FGS demotion. Covers both "back-to-back run" mid-process and the standard stop-then-start lifecycle.

**Alternatives considered:**
- *10 s window* — rejected, only ~2 Track samples per window at 5 s poll, barely smooths.
- *20 s window* — rejected, laggy response to genuine pace changes (hills, finish kicks).
- *Faster poll (2 s)* — discussed and rejected for v1. Window width drives smoothness, not poll rate; faster polling makes the displayed value update more *often*, not more *smoothly*. Increases BT activity on phone+watch. Reconsider if responsiveness feels lacking after the next field test.
- *Per-sample integration* — rejected, mathematically equivalent to endpoint delta for cumulative counters.
- *Watch-side smoothing instead of companion-side* — rejected, the watch already has its hands full with HR sampling + cadence ring + AppMessage inbox; companion side has plenty of CPU budget.

## 2026-05-16 — Cadence diagnostics: APP_LOG of step-count source pending real-run capture

**Decision:** Add diagnostic logging to `active_run.c` to investigate the "cadence stays at 0 for the entire run" report from the 2026-05-16 field test. No behavior change — the existing 3-slot ring + `peek_current_value(HealthMetricStepCount)` polling stays. New logs: (a) `health_service_metric_accessible(HealthMetricStepCount, …)` mask at `window_load`, (b) seed step count at `window_load`, (c) per-tick line in `cadence_tick_cb` showing `steps_now / oldest / filled / spm`.

**Rationale:** The ring-buffer arithmetic (`active_run.c:254`) is correct on its own terms — verified by walking through tick-by-tick. So the bug is upstream in the data source: either Pebble Health is disabled (peek returns 0 unconditionally), the platform doesn't expose StepCount, or updates arrive in batches misaligned with the 5 s tick. We can't pick the right fix without the data.

**Follow-up:** after the next run, capture logs via `pebble logs` and decide. Branches:
- `steps_now` constant at 0 → Pebble Health setting / hardware limitation. User-side fix or accept the limitation.
- `accessibility mask = 0` but values increment → API mismatch; switch to event-driven via `HealthEventMovementUpdate`.
- Values grow but in sparse batches → switch to event-driven so we sample on the watch's update beat.
- Values grow correctly and `delta > 0` but `spm = 0` → actual logic bug; re-investigate.

**Alternatives considered (and deferred until we have data):**
- *Switch to `HealthEventMovementUpdate` event-driven now* — rejected as premature. Bigger refactor; pointless if peek isn't the problem.
- *Render the raw step count on-screen for visual debugging* — rejected, intrusive on the active-run UI for a temporary diagnostic.

<!-- TODO:FEATURE — first-launch instructions screen polish + OpenTracks settings deeplink (spec §14 step 10) -->
<!-- TODO:SECURITY — verify ContentObserver cursor handling does not leak Track URI grants across activity recreation -->
<!-- TODO:SECURITY — confirm PebbleAndroidAppPicker auto-select default is acceptable; consider exposing the manual picker dialog from client-ui before publish -->
<!-- TODO — populate watchapp/package.json `companionApp.android.url` with the canonical Codeberg repo URL once chosen -->
<!-- TODO:FEATURE — pull `pebble logs` from next run, diagnose cadence-zero, implement targeted fix per 2026-05-16 entry -->
