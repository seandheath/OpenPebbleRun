# Pre-release audit findings

Full code audit of v0.1.0 performed 2026-05-14/15. Each item below is a discrete unit of work; tick as we go. File:line refs were correct at audit time — re-verify before editing.

Scope verified: watchapp (C, SDK 3, emery), companion (Kotlin/Compose, minSdk 26 / targetSdk 35 / compileSdk 36), shared protocol (6 AppMessage keys in lockstep), docs, flake.

## Executive summary

- Architecture is sound. FGS+CDM split, OpenTracks Public/Dashboard integration, watch-side local clock with companion-snap, watch-derived cadence — all correct choices.
- One Critical publish blocker: edge-to-edge insets not handled. `targetSdk=35` forces edge-to-edge on Android 15; the Start/Stop button sits under the gesture bar.
- One Critical security item: `DashboardActivity` (exported) accepts unvalidated `intent.clipData` URIs from any caller.
- Two High reliability items: main-thread `ContentResolver` I/O in the FGS poll loop; URI-grant lifetime tied to Activity-task-in-recents.
- No new dependencies required for any item except L1 (junit, build-only).
- Most "smells" are deliberate — see "What NOT to change" at the bottom.

---

## Critical

### C1 — Edge-to-edge insets not applied (Android 15 publish blocker)
- [ ] **Where:** `companion/app/src/main/kotlin/run/openpebble/companion/MainActivity.kt:103-117`; `ui/HomeScreen.kt:49-50`; `ui/FirstLaunchScreen.kt`; `DashboardActivity.kt:80-89`
- **Problem:** No `enableEdgeToEdge()` call anywhere; no `WindowInsets.safeDrawing` modifier on outer `Column`s. With `targetSdk=35` (`build.gradle.kts:21`), Android 15+ draws behind status and gesture bars by default. The Home `Start Run`/`Stop Run` button — the only primary action — sits under the gesture bar.
- **Recommendation:** Call `enableEdgeToEdge()` in `MainActivity.onCreate` *before* `setContent`. Wrap the `HomeScreen`/`FirstLaunchScreen` outer `Column` modifier with `.windowInsetsPadding(WindowInsets.safeDrawing)`. `DashboardActivity` either does the same with a Compose port (preferred for consistency) or stays on the legacy View tree with `WindowCompat.setDecorFitsSystemWindows(window, true)`.
- **Sources:** [edge-to-edge codelab](https://developer.android.com/codelabs/edge-to-edge), [Compose edge-to-edge setup](https://developer.android.com/develop/ui/compose/system/setup-e2e)
- **Effort:** S

### C2 — DashboardActivity accepts unvalidated Intent extras (security)
- [x] **Where:** `companion/app/src/main/kotlin/run/openpebble/companion/DashboardActivity.kt:47-58` — **Done** in `feat/dashboard-validation` (2026-05-15). See `docs/log.md` entry for the same date.
- **Problem:** Exported Activity reads `intent.clipData.getItemAt(0).uri` with zero validation — no caller-package check, no URI authority check, no `FLAG_GRANT_READ_URI_PERMISSION` check. Any installed app can launch us with arbitrary content URIs; the listener service then polls those URIs every 5 s. Blast radius limited (no network, no exfil), but gratuitous attack surface and an explicit pre-publish TODO (`docs/log.md:545`).
- **Recommendation:** In `onCreate`, validate three things before stashing:
  1. `callingActivity?.packageName` (or `referrer`) ∈ `OpenTracksVariant.PROBE_ORDER`
  2. `trackUri.authority` starts with `de.dennisguse.opentracks` and path starts with `/dashboard/tracks/`
  3. `intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0`
  
  Any failure → `Log.w` + `finish()`. ~10 lines closes the TODO.
- **Sources:** [SEI CERT DRD07-X](https://wiki.sei.cmu.edu/confluence/display/android/DRD07-X.+Protect+exported+services+with+strong+permissions)
- **Effort:** S

---

## High

### H1 — 5 s poll runs ContentResolver on main thread
- [ ] **Where:** `companion/app/src/main/kotlin/run/openpebble/companion/pebble/PebbleListenerService.kt:58, 69-80, 106, 111`
- **Problem:** `Handler(Looper.getMainLooper())` posts `pollRunnable` which calls `readTrack()`/`readLatestTrackPoint()` — each a cross-process `ContentResolver.query` against OpenTracks's provider. Service has no UI so user-visible ANR risk is nil, but PebbleKit's bound-service callbacks (`onMessageReceived`, `onAppOpened`, `onAppClosed`) also dispatch on the main looper and will queue behind a slow query. Strict-mode antipattern.
- **Recommendation:** Replace the `Handler`+`Runnable` with a coroutine on the existing `coroutineScope`:
  ```kotlin
  coroutineScope.launch(Dispatchers.IO) {
      while (isActive) {
          delay(POLL_PERIOD_MS)
          try {
              if (RunSession.trackUri != null) readTrack()
              if (RunSession.trackPointsUri != null) readLatestTrackPoint()
              ensureObservers()
          } catch (e: Exception) { Log.w(TAG, "poll read failed", e) }
      }
  }
  ```
  Drop the `mainHandler` field. ContentObserver constructor accepts `null` for the handler, which delivers `onChange` on whichever thread the provider notifies on.
- **Sources:** [Coroutines best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
- **Effort:** S

### H2 — URI grant lifetime tied to DashboardActivity task-in-recents
- [ ] **Where:** `DashboardActivity.kt:28-33` (comment); `PebbleListenerService.kt:75-77`
- **Problem:** Comments correctly state that `FLAG_GRANT_READ_URI_PERMISSION` survives Activity destroy only while the Activity's *task* remains in recents. If the user swipes us from recents mid-run (likely — they're in OpenTracks's UI), the next `contentResolver.query` throws `SecurityException`, caught silently at `PebbleListenerService.kt:75-77`, metrics freeze, watch dims after 30 s. User reports as "watch said --- mid-run".
- **Recommendation (two options):**
  - **(a) Cheap mitigation:** Catch `SecurityException` specifically in `readTrack`/`readLatestTrackPoint` and re-foreground OpenTracks once per run with `OpenTracksApi.openApp`. Document the trade-off.
  - **(b) Proper fix:** Pass URIs to the FGS via `Intent.setClipData` + `FLAG_GRANT_READ_URI_PERMISSION` on the `ACTION_PROMOTE_FOREGROUND` start (`DashboardActivity.kt:64-66`). A foreground service is a valid grant target. Service holds the grant for the run's lifetime, independent of the Activity's task. Removes the recents-swipe footgun and lets `ensureObservers()` collapse to a one-shot call.
- **Effort:** S (a) / M (b). (b) is a refactor and needs explicit approval.

### H3 — PebbleMessenger silently drops on persistent failure with no reconnect
- [ ] **Where:** `companion/app/src/main/kotlin/run/openpebble/companion/pebble/PebbleMessenger.kt:129-151`
- **Problem:** When `sendDataToPebble` returns `null` ("Pebble app not reachable") or a non-Success `TransmissionResult`, the messenger just logs. The cached `DefaultPebbleSender` stays bound to a potentially-broken connection; subsequent sends hit the same broken state. The watch's 30 s stale dim is the only UX signal.
- **Recommendation:** Track consecutive non-Success results in a counter; after 3 in a row, call `close()` (already implemented at line 50-55) to null `sender` so the next call rebuilds the binder connection. ~8 lines; covers the "Pebble companion app was killed" case without adding a retry/backoff scheme.
- **Effort:** S

---

## Medium

### M1 — 1 Hz Compose polling of @Volatile RunSession.active
- [ ] **Where:** `MainActivity.kt:185-190`
- **Problem:** `LaunchedEffect(Unit) { while (true) { delay(1000); runActive = RunSession.active } }` busy-polls a `@Volatile` for Compose recomposition. Cost is negligible; idiom is poor. Comment at line 181-184 correctly diagnoses the reason.
- **Recommendation:** Replace `RunSession.active: Boolean` with `MutableStateFlow<Boolean>` (and similarly for `watchAppOpen` if desired). In MainActivity: `val runActive by RunSession.active.collectAsStateWithLifecycle()`. Pulls in `lifecycle-runtime-compose` (small; transitively present). **Touches RunSession's public API → refactor; requires explicit approval per CLAUDE.md.**
- **Effort:** S (touches 4 files: RunSession.kt, MainActivity.kt, DashboardActivity.kt, PebbleListenerService.kt)

### M3 — OpenTracks column names hardcoded without diagnostic logging
- [ ] **Where:** `PebbleListenerService.kt:458-461, 470-482`
- **Problem:** `getColumnIndexOrThrow` throws `IllegalArgumentException` on schema rename; the `Cursor.longOrNull`/`floatOrNull` extensions tolerantly return `null`; result is silent all-zero metrics. No way to detect schema drift from logcat after a problem report.
- **Recommendation:** On first successful Track read per run, log `c.columnNames.joinToString()` at INFO (one-shot guard with a `private var loggedColumnsThisRun`). Cheap diagnostic.
- **Effort:** S (3 lines)

### M4 — Cadence midnight rollover shows single zero-SPM tick
- [ ] **Where:** `watchapp/src/c/screens/active_run.c:254-279, particularly 266-269`
- **Problem:** `HealthMetricStepCount` resets to 0 at local midnight. The clamp `if (delta < 0) delta = 0` produces a misleading 0-SPM reading for one tick before the ring repopulates. Documented in the code; cosmetic.
- **Recommendation:** On detecting `delta < 0`, reset the ring (`s_step_filled = 0`, `s_step_idx = 0`, seed `s_step_ring[0] = steps_now`, `s_step_idx = 1`, `s_step_filled = 1`) and render `"---"`. The next 15 s show `"---"` instead of a false `"0"`. ~6 lines.
- **Effort:** S

### M7 — Document deliberate no-retry policy on outbox
- [ ] **Where:** `watchapp/src/c/app_message.c:15-17`
- **Problem:** Outbox failure handler logs and drops, no retry. This is correct per spec §4.5 (user-facing retry surfaces in `starting`/`stopping` screens own the recovery) but a future maintainer may "improve" it.
- **Recommendation:** Add a one-line invariant comment: "Retry is owned by the screen-level timeouts (`starting`, `stopping`); see spec §4.5."
- **Effort:** S (one line)

### M8 — Stale compose-bom + activity-compose
- [ ] **Where:** `companion/app/build.gradle.kts:65, 69`
- **Problem:** `compose-bom:2024.09.03` is ~8 months old; `activity-compose:1.9.2` predates several insets-handling fixes. Material3 has shipped improvements relevant to C1.
- **Recommendation:** Bump while doing C1. Verify compat against Kotlin 2.3.20 + AGP 8.9.3 — pin to whatever the current stable BOM cuts.
- **Effort:** S

---

## Low

### L1 — Pure-function unit tests for TrackStats.kt
- [ ] **Where:** `companion/app/src/main/kotlin/run/openpebble/companion/metrics/TrackStats.kt`
- **Rationale:** Three pure functions (`movingTimeMsToSec`, `meterToHundredthsMile`, `paceFromSpeed`) with clear contracts and boundary cases (null inputs, negative inputs, 3600-cap, 0-speed → null). Highest test ROI in the codebase by a wide margin; the only purely-functional surface.
- **Recommendation:** Add `companion/app/src/test/kotlin/run/openpebble/companion/metrics/TrackStatsTest.kt` with ~20 JUnit cases. Add `testImplementation("junit:junit:4.13.2")`. Build-only dep; no runtime impact. Update `companion/Makefile` `test` target to actually invoke it.
- **Spec impact:** Spec §12 says "manual testing only" — needs a one-line spec update to carve out the pure-function exception.
- **Effort:** S

### L2 — No heap instrumentation on the watch
- [ ] **Where:** `watchapp/src/c/main.c:init`, `screens/active_run.c:window_load`
- **Problem:** No `heap_bytes_used()` / `heap_bytes_free()` logs. Total footprint is small but no signal if a future addition starts pressuring the heap.
- **Recommendation:** Add `APP_LOG(APP_LOG_LEVEL_DEBUG, "heap used=%u free=%u", heap_bytes_used(), heap_bytes_free());` in `init()` and at the end of `active_run.window_load`. Free dev-time signal.
- **Sources:** [Rebble FAQ](https://developer.repebble.com/faqs/)
- **Effort:** S (3 lines)

### L5 — Document inbox-handler synchronous-install invariant in app_message.h
- [ ] **Where:** `watchapp/src/c/app_message.h`; enforced at `idle.c`, `starting.c`, `stopping.c`, `active_run.c` `*_show()`
- **Problem:** Multiple screens install inbox handlers synchronously in `*_show()` *before* `window_stack_push` to avoid the push-vs-`window_appear` race that would drop `RUN_STARTED`/`RUN_STOPPED`. Documented per-file but not in the protocol header — a future screen author could miss it.
- **Recommendation:** One-line invariant comment at top of `app_message.h`: "Inbox handlers MUST be installed synchronously in `*_show()`, before `window_stack_push`, or RUN_STARTED/RUN_STOPPED may be dropped."
- **Effort:** S

### L8 — `time_ms()` for stale tracking is wall-clock (informational, no action)
- [x] **Where:** `watchapp/src/c/screens/active_run.c:195-199`
- **Problem:** `time_ms()` is wall-clock. Timezone change or NTP correction mid-run could spuriously fire or suppress staleness. Comment says "OS doesn't jump it during a run" — that's an assumption.
- **Recommendation:** Leave it. Pebble SDK has no monotonic ms counter exposed; `time_ms()` is the accepted pattern. Negligible real-world risk; noted for awareness only.
- **Effort:** N/A

---

## Open TODO:SECURITY items (`docs/log.md:543-547`)

| TODO | Disposition |
|---|---|
| Review `<queries>` manifest exposure + Intent validation in DashboardActivity | **Closed by C2.** Validation added in `DashboardActivity.onCreate` (`feat/dashboard-validation`, 2026-05-15). |
| Verify ContentObserver cursor handling doesn't leak Track URI grants across Activity recreation | **Covered by H2 option (b).** If H2(a) instead, audit `unregisterObserversIfAny()` is called on all paths — it is (`PebbleListenerService.kt:230-232`). |
| Confirm PebbleAndroidAppPicker auto-select default is acceptable; consider exposing manual picker | **Not actionable in code** — the library's `PebbleAndroidAppPicker` is the access-control mechanism. Document the trade-off in spec §10.2 and decide whether to expose a Settings option to override. |
| Populate `companionApp.android.url` | **Already done** at `watchapp/package.json:21`. Remove this TODO from `docs/log.md`. |

- [ ] Remove the stale `companionApp.android.url` TODO from `docs/log.md`.
- [ ] Document the PebbleAndroidAppPicker trade-off in spec §10.2.

---

## Simplification opportunities

Specific deletions worth the diff:

- [ ] **Drop `mainHandler` field in `PebbleListenerService`** — falls out of H1.
- [ ] **Extract `format_dist` + `format_time` into shared `watchapp/src/c/format.c/h`** — currently duplicated byte-for-byte at `active_run.c:165-178` and `run_summary.c:62-74`. Saves ~14 LOC + a divergence risk. Documented as deliberate at `run_summary.c:56-60` but the rationale weakens with each maintenance pass.
- [ ] **Extract `logTransmissionResults(result, tag)` helper in PebbleMessenger** — `startWatchapp` (lines 91-95) and `send` (lines 146-150) repeat the same iteration logic. Saves 8 LOC.
- [ ] **Move `Cursor.longOrNull`/`floatOrNull` extensions** out of `PebbleListenerService.kt:470-482` into `metrics/CursorExt.kt`. Pure utilities, not service-coupled. Optional cohesion improvement.
- [ ] **Port `DashboardActivity`'s LinearLayout/TextView (lines 80-89) to Compose** for consistency with the rest of the app. Optional.

---

## Informational (no action)

- **I1** — `companionApp.android.url` is already populated at `watchapp/package.json:21`. Phase 1 finding was stale.
- **I2** — No protocol version field. Spec §13 deferred this to "ignore unknown keys". Acceptable for an end-to-end-controlled protocol with 6 keys.
- **I3** — Android 16's `startObservingDevicePresence` is wrong shape for OpenPebbleRun (we want service alive specifically during a run, not whenever the watch is nearby) and unavailable until minSdk hits 36. Do not adopt. Noted for future awareness.
- **I4** — `OpenTracksVariant.PROBE_ORDER` covers the four release-flavor applicationIds but not their `.debug` variants (e.g. `de.dennisguse.opentracks.playstore.debug`). Users testing against a self-built debug OpenTracks would hit `DashboardActivity`'s caller validation rejection. Expand the list (or switch to a pattern match) before publication if debug-OpenTracks support is desired.
- **I5 (dev-workflow gotcha)** — `make companion-install` runs `adb uninstall` first (`companion/Makefile:24-26`) to dodge `INSTALL_FAILED_UPDATE_INCOMPATIBLE` from debug-keystore mismatches across dev machines. Android drops the package's CompanionDeviceManager associations on uninstall, so **every reinstall wipes the CDM pairing**. After `make companion-install`, re-pair via the companion's Home screen "Pair Pebble for background access" button before testing the watch-initiated start path; otherwise `CMD_START` NACKs with `start would BAL_BLOCK` (`PebbleListenerService.kt:368-372`).

---

## What NOT to change (deliberate, correct as-is)

These look like smells but are correct:

- **5 s poll cadence** matching HR sample period — deliberate beat alignment (`active_run.c:489`, `PebbleListenerService.kt:445`).
- **Synchronous inbox-handler install** in every `*_show()` — required to avoid the push-vs-`window_appear` race.
- **`foregroundServiceType="connectedDevice"`** — exactly the right FGS type.
- **No `POST_NOTIFICATIONS` permission** — deliberate UX choice; FGS contract only needs the Notification object.
- **No `BLUETOOTH_SCAN`** — bonded-device fast path eliminates the need.
- **CDM filter with BR/EDR public address, `setSingleDevice(true)`, no `DEVICE_PROFILE_WATCH`** — load-bearing per `CdmManager.kt:30-48` and `docs/log.md` 2026-05-14 entry. Don't "fix".
- **`FLAG_ACTIVITY_MULTIPLE_TASK` on StartRecording** — works around documented `onNewIntent` race in OpenTracks.
- **`tools:ignore="ExportedService"`** — required for PebbleKit bind, defended by UUID filter at `PebbleListenerService.kt:318-322`.
- **`@Volatile` mutable singletons in RunSession** — correct for two-callers-same-process. StateFlow (M1) is optional polish.
- **Hand-rolled DCL in PebbleMessenger** (lines 32-47) — `by lazy` can't reset on `close()`. Don't replace.
- **Window recreate-on-push** in `starting`/`stopping`/`stop_confirm`/`run_summary` — documented terseness choice; caching adds bookkeeping.
- **HR sample-period-zero called from both `main.c:deinit` and `active_run.c:window_unload`** — defensive duplication, covers all exit paths. Both required.
- **`gpath_create`/`destroy` per paint in `icons_draw_play`** — idle screen, paints rarely.
- **Single in-flight AppMessage, no library-level retry** — spec §4.5; user-facing retries (`starting`, `stopping`) cover failures.
- **No automated tests beyond TrackStats** — spec §12.
- **No CI** — explicit decision.

---

## How to validate findings

- **C1:** Install on a Pixel running Android 15+, observe Start/Stop button drawn under the gesture bar.
- **C2:** From `adb shell am start-activity -n run.openpebble.companion.debug/run.openpebble.companion.DashboardActivity --es ...` with garbage clipData — observe `RunSession.active = true` set without validation.
- **H1:** Enable strict mode (`StrictMode.setThreadPolicy(...)` in a debug build of MainActivity); observe disk/network reads logged from the listener service's main thread.
- **H2:** Start a run, swipe app from recents, observe metric pipe freezing within one poll cycle.
- **M4:** Set device clock to 23:59:30, start a run with active stepping, observe single 0-SPM tick at midnight.

---

## Sources

- [Android — Edge-to-edge enforcement (codelab)](https://developer.android.com/codelabs/edge-to-edge)
- [Android — Compose edge-to-edge setup](https://developer.android.com/develop/ui/compose/system/setup-e2e)
- [Android — Window insets in Compose](https://developer.android.com/develop/ui/compose/system/insets-ui)
- [Android — Behavior changes targeting 16](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Android — Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android — Background activity start restrictions](https://developer.android.com/guide/components/activities/background-starts)
- [Android — CompanionDeviceManager](https://developer.android.com/reference/android/companion/CompanionDeviceManager)
- [Android — Companion device pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)
- [Android — Coroutines best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
- [Android — Lifecycle-aware coroutines](https://developer.android.com/topic/libraries/architecture/coroutines)
- [Android — ContentResolver](https://developer.android.com/reference/android/content/ContentResolver)
- [Pebble dev — Advanced Communication / AppMessage](https://developer.rebble.io/guides/communication/advanced-communication/)
- [Pebble dev — AppMessage C reference](https://developer.rebble.io/docs/c/Foundation/AppMessage/)
- [Pebble dev — FAQ (heap budget)](https://developer.repebble.com/faqs/)
- [PebbleKitAndroid2 repo](https://github.com/pebble-dev/PebbleKitAndroid2)
- [SEI CERT — DRD07-X protect exported services](https://wiki.sei.cmu.edu/confluence/display/android/DRD07-X.+Protect+exported+services+with+strong+permissions)
