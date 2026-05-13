# OpenPebbleRun — Specification v0.1.0

A run-tracking app for Pebble Time 2 that integrates with OpenTracks on Android.

## 1. Overview

Two components:

- **Watchapp** — C app on Pebble Time 2 (`emery`). Reads HR and step count locally, displays metrics, controls run start/stop.
- **Android companion** — Kotlin bridge app. Translates watchapp commands to OpenTracks Public API Intents, reads track data via Dashboard API, computes derived metrics, pushes to watch.

OpenTracks records GPS. OpenPebbleRun does not. OpenTracks must be installed.

## 2. Goals and non-goals

### Goals

- Start and stop GPS-tracked runs from the watch
- Display 5 metrics live: heart rate, current pace, cadence, distance, time
- No network, no analytics, no tracking

### Non-goals

- Pause/resume (OpenTracks Public API has no Pause intent — documented limitation)
- Configurable layout / metric picker
- Multi-sport, watchface variant, run history view
- Auto-pause, audio cues, mile-split alerts
- HR writeback to GPX from watch HRM (Public API doesn't accept it).
- Other Pebble platforms, iOS, languages other than English
- Run state persistence/recovery across companion restarts
- In-app diagnostics, troubleshoot screens, settings UI
- External HR via OpenTracks (deferred — v4.27's dashboard URI no longer projects `sensor_heartrate`; revisit once core metrics are stable)

## 3. Architecture

```
┌──────────────────┐   AppMessage    ┌───────────────────┐
│  Pebble Time 2   │◄───────────────►│  Android phone    │
│  Watchapp (C)    │  PebbleKitA2    │  Companion (Kotlin)│
│                  │                  │     │ Intents     │
│  - HR sensor     │                  │     ▼             │
│  - Step counter  │                  │  ┌──────────────┐ │
│  - 5 metrics UI  │                  │  │ OpenTracks   │ │
└──────────────────┘                  │  └──────────────┘ │
                                      └───────────────────┘
```

The companion uses **PebbleKitAndroid2** (`pebble-dev/PebbleKitAndroid2`, currently v0.1.0 alpha). Its bound-service architecture is woken by the Pebble companion app (`coredevices.coreapp`, min v1.0.7.7) when the watchapp opens. **No foreground service required.**

The companion uses OpenTracks's **Public API** (Intents) to start/stop and **Dashboard API** (content URIs + ContentObserver) for live data.

## 4. Pebble watchapp

### 4.1 Platform

- **SDK**: Pebble C SDK 4.9.148 or later
- **Target platform**: `emery` only (200×228, 64-color)
- **Capabilities** in `package.json`: `["health"]`
- **App kind**: watchapp

### 4.2 Screens

Four screens. Launch lands on idle; linear transitions
idle → active-run → stop-confirm → run-summary → (Back exits watchapp).

#### 4.2.1 Idle

Watchapp entry point. Shown until the companion confirms a run is active.

```
┌────────────────────────┐
│                        │
│    OpenPebbleRun       │   title (bold)
│                        │
│   Start a run on       │   prompt (regular)
│      your phone        │
│                        │
└────────────────────────┘
```

- Title: "OpenPebbleRun" centered, `FONT_KEY_GOTHIC_28_BOLD`.
- Prompt: "Start a run on your phone", wraps to two lines, `FONT_KEY_GOTHIC_18`.
- Inbox handler watches `KEY_RUN_STARTED`. On arrival, pushes active-run
  (§4.2.2) on top of the stack. Covers two paths: (a) the cold-launch-with-
  run-active case, where the companion's `PebbleListenerService.onAppOpened`
  replays `RUN_STARTED` within a few hundred ms — idle is effectively a
  brief flash; (b) the launch-then-tap-Start case, where the user opens the
  watchapp first and then taps Start Run on the companion.
- Buttons:
  - **Back**: exit the watchapp (`window_stack_pop_all`).
  - **Select / Up / Down**: no-op. There is no on-watch start affordance.

#### 4.2.2 Active run

Pushed by idle when `RUN_STARTED` arrives. Not a launch entry point —
showing this screen with "---" placeholders when no run is recording looks
broken, so idle keeps the watch on a clearly-idle screen until the
companion signals.

Layout (200×228):

```
┌────────────────────────┐
│        HEART RATE      │  large
│         ### bpm        │
│                        │
│  PACE         CADENCE  │  medium
│  M:SS /mi     ### spm  │
│                        │
│  DIST       TIME    ▪  │  small + stop icon
│  0.00 mi    MM:SS      │
└────────────────────────┘
```

A small filled-square stop icon sits at the right edge of the bottom row,
vertically aligned with the physical Down button — it marks the
Down=open-stop-confirm affordance at a glance. The TIME cell is narrowed
from 100 to 82 px wide to leave the 18 px gutter for the icon.

HR, pace, and cadence are visually prominent. Distance and time secondary.

Buttons:
- **Back**: exit watchapp. The run keeps recording in the companion;
  re-opening the watchapp lands on this same screen and the companion
  replays `RUN_STARTED`. No `CMD_STOP` is sent — stop is gated behind the
  explicit Down+Up confirm path so a stray Back press can't end a run.
- **Down**: open stop-confirm screen (§4.2.3).
- **Select / Up**: no-op.

If AppMessages stop arriving from companion >30s: dim metrics 50% to indicate
stale. No vibration. Resume full brightness when next message arrives.

#### 4.2.3 Stop confirm

Shown when Down pressed on active-run.

```
┌────────────────────────┐
│                     ✓  │
│      Stop run?         │
│                     ✕  │
└────────────────────────┘
```

A checkmark icon at the right edge aligned with the physical Up button
indicates confirm; an X icon at the right edge aligned with Down indicates
cancel. The centered title is the only on-screen text.

Buttons:
- **Up** (✓): send `CMD_STOP`, single short vibration on ack, transition
  to run-summary (§4.2.4).
- **Down** (✕): return to active-run (which has been ticking underneath —
  its inbox handler stayed installed, so stats are up-to-date on return).
  Invariant: from active-run, pressing **Down twice** (once to enter
  stop-confirm, once to cancel) lands you back on the live stats.
- **Back**: mirrors Down (cancel) — Pebble convention is Back = go back.
- **Select**: no-op.

#### 4.2.4 Run summary

Shown after a confirmed stop. Snapshots the run's final stats from active-run's accumulators (`s_elapsed_sec`, latest `KEY_DISTANCE`, mean of internal-HRM samples).

```
┌────────────────────────┐
│      RUN COMPLETE      │  bold title
│                        │
│  DISTANCE   X.XX mi    │
│  TIME       MM:SS      │
│  AVG PACE   M:SS /mi   │
│  AVG HR     ### bpm    │  "---" if no HR samples
└────────────────────────┘
```

- Distance / time: copied from the most recent `KEY_DISTANCE` / locally-ticked elapsed second.
- Avg pace: `time_sec * 100 / dist_hundredths_mi` (sec/mi), capped at 3600 ≡ "--:--".
- Avg HR: arithmetic mean of all non-zero `HealthEventHeartRateUpdate` samples received while active-run was up. "---" if no samples ever fired.

Buttons:
- **Back**: exit the watchapp entirely (Pebble default — pops to the
  launcher or the previously-foregrounded app). With active-run +
  stop-confirm already removed by the Up-confirm path, the window stack
  here is just `[run_summary]`, so pop-all empties cleanly.
- **Select / Up / Down**: ignored. A stray button press shouldn't yank the
  user out of the summary before they've read it.

No inbox handler — a stopped run produces no further metrics.

### 4.3 Sensors

**Heart rate:**

Single source — Pebble's built-in optical HRM. External HR (BLE strap via OpenTracks) is deferred; see §11.

Watch behavior:
- On app launch: `health_service_set_heart_rate_sample_period(1)` (1 Hz). Subscribe to `HealthEventHeartRateUpdate`. Display from internal HRM.
- **On any exit path: `health_service_set_heart_rate_sample_period(0)`**. Required to stop battery drain.

Companion plays no role in HR. It does not forward HR, does not read `sensor_heartrate` from OpenTracks, and sends no HR-related AppMessage keys.

**Cadence:**
- Poll `health_service_peek_current_value(HealthMetricStepCount)` every 5s
- 15s rolling window: SPM = (steps_in_window / 15) × 60
- Display locally. Not sent to companion.

### 4.4 Vibration

One pattern: `vibes_short_pulse` on stop confirmation. Nothing else.

### 4.5 AppMessage flow control

AppMessage delivers one message at a time and ACKs each. Throttle sends to one per `outbox_sent` callback. Do not implement custom protocol versioning — AppMessage failure callbacks are sufficient.

## 5. Android companion

### 5.1 Platform

- **minSdk**: 26 (Android 8.0)
- **targetSdk**: 35
- **Language**: Kotlin
- **Background**: PebbleKitAndroid2 bound service. Runs are started exclusively by tapping **Start Run** on the companion's Home screen (foreground; no Background Activity Launch restrictions apply). The watch UI offers no start affordance — the v0.1 watchapp launches onto an idle screen (§4.2.1) and waits for the companion's `RUN_STARTED` before transitioning to active-run. The watch-side `CMD_STOP` path is preserved for the in-app stop-confirm flow; this works because the user has the watchapp open (foreground) at that moment. PendingIntent, in-service `startForeground`, and CompanionDeviceManager workarounds for *watch-initiated start while companion is backgrounded* were all attempted and rejected for v0.1 (see docs/log.md).
- **Foreground service while recording**: between Start and Stop the service is promoted to foreground (`foregroundServiceType="connectedDevice"`), matching OpenTracks's `TrackRecordingService` pattern. A low-importance ongoing notification ("Recording — see your watch") is posted during a run and dismissed on stop. Foreground promotion is initiated by DashboardActivity (which OpenTracks calls back into our app from its own foreground context), so the FGS-from-background restriction doesn't apply. `POST_NOTIFICATIONS` is requested at first launch on API 33+; if denied the service still gets foreground state — the notification simply isn't visible.

### 5.2 Screens

#### 5.2.1 First-launch instructions

One screen, shown only if Public API check fails. No multi-step wizard.

- Text: "OpenPebbleRun needs OpenTracks with Public API enabled."
- Steps shown inline:
  1. Install OpenTracks (button → IzzyOnDroid / F-Droid link)
  2. In OpenTracks: Settings → Public API → enable both **Public API** and **Automatic data transfer** (the dashboard-callback gate; recording starts without it but `DashboardActivity` never fires)
  3. Pair Pebble in the official Pebble app
- Button: "Open OpenTracks settings" (Intent to OpenTracks; falls back to launcher Intent)
- Button: "Done"

#### 5.2.2 Home

Steady-state. Shown after first-launch.

- Pebble: ✓/✗
- OpenTracks: ✓ (variant name) / ✗
- Text: "Use the button below to start a run, then look at your watch."
- Primary action: a **Start Run** button (becomes **Stop Run** while a run is active, switched live by a 1 Hz Compose tick reading `RunSession.active`). Disabled when OpenTracks isn't installed.
- No settings, no troubleshoot, no run history. (Use OpenTracks for history.)
- While a run is active, an ongoing notification ("Recording — see your watch") is shown in the shade. Tapping it opens this Home screen.

**Start Run side effects.** Tapping the button triggers, in order: (1) launch the watchapp on the Pebble (`PebbleKit startAppOnTheWatch`), (2) fire OpenTracks's `publicapi.StartRecording` Intent. OpenTracks then calls our `DashboardActivity` back (via the `STATS_TARGET_PACKAGE`/`STATS_TARGET_CLASS` extras), and `DashboardActivity` (a) stashes the dashboard URIs in `RunSession`, (b) sends `RUN_STARTED` to the watch, and (c) fires OpenTracks's package launcher Intent (`OpenTracksApi.openApp`) to bring OpenTracks's recording UI to the foreground. Step (c) is the "one-tap start and put the phone away" affordance — it has to live in `DashboardActivity` rather than alongside (1) and (2) because OpenTracks's callback to us races a launcher-Intent fired earlier and would otherwise land `DashboardActivity` on top of OpenTracks.

**Track name.** The companion does not set `TRACK_NAME` on the StartRecording Intent. OpenTracks's own "Default track name" preference applies (Settings → Recording → Default track name; options: Date ISO 8601 / Date local / Number). This honors the user's configured choice without requiring SharedPreferences reads — which third-party apps can't do. `TRACK_CATEGORY` and `TRACK_ICON` are still set to `"running"` (OpenTracks has no equivalent default-category preference).

### 5.3 Computed metrics

Companion derives metrics from OpenTracks Dashboard URIs and pushes to watch.

| Key | Metric | Source | Format |
|---|---|---|---|
| 120 | Pace (current) | TrackPoint `speed` (m/s) → `1609.344 / speed` → sec/mi, capped at 3600 | uint16 sec/mi |
| 122 | Time | Track `movingtime` (ms) → seconds | uint32 sec |
| 123 | Distance | Track `totaldistance` (m) → hundredths of a mile | uint32 |

Update on each Dashboard `ContentObserver` notification. Pace uses OpenTracks's reported `speed` directly — no smoothing window. When OpenTracks's dashboard cursor holds only a SEGMENT_START marker (`type = -2`, `speed = null`), pace is null and the watch renders `--:--`; once OpenTracks inserts a normal TrackPoint with a non-null `speed`, the watch updates.

HR and cadence come from the watch and are displayed there directly. The companion does not read or forward HR. v4.27's dashboard `DataProvider.DATA_PROJECTIONMAP_TRACKPOINTS` exposes only `_id, trackid, latitude, longitude, time, type, speed` — `sensor_heartrate` / `sensor_cadence` are not available — but this no longer matters for §4.3 since the dual-source state machine is removed.

### 5.4 OpenTracks variant detection

Probe in this order, use the first that resolves via `PackageManager.getPackageInfo()`:

1. `de.dennisguse.opentracks` (F-Droid default)
2. `de.dennisguse.opentracks.playstore` (lowercase — note the README typo shows capital S, the actual `applicationId` is lowercase)
3. `de.dennisguse.opentracks.debug`
4. `de.dennisguse.opentracks.nightly`

Cache result in SharedPreferences. Re-probe `onResume`. No picker UI — first resolved wins.

## 6. OpenTracks integration

### 6.1 Public API

**Start recording:**
- Action: `de.dennisguse.opentracks.publicapi.StartRecording`
- Component: `<package>/de.dennisguse.opentracks.publicapi.StartRecording`
- Extras:
  - `TRACK_NAME`: `"Run"` (no template)
  - `TRACK_CATEGORY`: `"running"`
  - `TRACK_ICON`: `"running"`
  - `STATS_TARGET_PACKAGE`: companion package
  - `STATS_TARGET_CLASS`: companion's Dashboard receiver activity
- Use `setComponent(...)` + `startActivity(...)`. Add `FLAG_ACTIVITY_NEW_TASK` if started from non-Activity context.

**Stop recording:**
- Action: `de.dennisguse.opentracks.publicapi.StopRecording`
- Component: `<package>/de.dennisguse.opentracks.publicapi.StopRecording`

`CreateMarker` is not used.

### 6.2 Dashboard API

After `StartRecording` (with the *Automatic data transfer* toggle enabled — see §5.2.1), OpenTracks invokes companion's Dashboard activity with three content URIs packed in `intent.clipData` (`FLAG_GRANT_READ_URI_PERMISSION` set on all):

- `clipData[0]` — Track URI, shape `content://de.dennisguse.opentracks.publicapi/dashboard/tracks/<ids>`
- `clipData[1]` — TrackPoints URI, shape `content://de.dennisguse.opentracks.publicapi/dashboard/trackpoints/<ids>`
- `clipData[2]` — Markers URI (unused by this app)

`intent.data` is **not** populated; ignore it. Authority and path shapes verified against `DataProvider.java` (v4.27.0).

**Track URI columns used** (lowercase, read by name with `getColumnIndexOrThrow`):
- `movingtime` (long, ms)
- `totaldistance` (float, meters)

**TrackPoints URI columns used:**
- `speed` (float, m/s)
- `time` (long, epoch ms)

The v4.27 dashboard projection (`DataProvider.DATA_PROJECTIONMAP_TRACKPOINTS`) exposes only `_id, trackid, latitude, longitude, time, type, speed`. This is the **baseline** projection we target; tolerating missing columns is no longer a fallback strategy but the steady-state assumption. `sensor_heartrate` and `sensor_cadence` are absent and not read.

Column identifiers are lowercase Java String constants in `TracksColumns.java` / `TrackPointsColumns.java`. SQLite is case-insensitive in unquoted SQL but Android's `Cursor.getColumnIndexOrThrow` is case-sensitive on most providers — uppercase names throw silently.

Register `ContentObserver` on both URIs; recompute current pace on TrackPoints changes, recompute distance/time on Track changes.

## 7. Watch ↔ Companion AppMessage protocol

No version negotiation. Both sides ignore unknown keys.

### 7.1 Watch → Companion

| Key | Name | Type | Payload |
|---|---|---|---|
| 1 | `CMD_START` | uint8 | (none) |
| 2 | `CMD_STOP` | uint8 | (none) |

### 7.2 Companion → Watch

| Key | Name | Type | Payload |
|---|---|---|---|
| 110 | `RUN_STARTED` | uint8 | (none) |
| 111 | `RUN_FAILED` | uint8 | (none) |
| 120 | `PACE_CURRENT` | uint16 | sec/mi (capped 3600) |
| 122 | `TIME` | uint32 | seconds |
| 123 | `DISTANCE` | uint32 | hundredths of a mile |

Keys 113, 114, and 124 (HR source switching + forwarded HR) are reserved — they were defined for the deferred external-HR feature (§4.3) and remain unallocated until that work resumes.

### 7.3 Update cadence

| Direction | Message | Frequency |
|---|---|---|
| Companion → Watch | Metric updates (120, 122, 123) | On each ContentObserver change |

## 8. Failure modes

### 8.1 Bluetooth disconnect mid-run

- Watch: metrics dim at 50% after 30s without messages
- Companion: OpenTracks continues recording
- On reconnect: full brightness, no alert

### 8.2 Run start fails

- Watch shows "Couldn't start. Open companion app on phone." for 5s, then returns to pre-run
- Companion logs to `Log.w` only (no in-app log viewer)

### 8.3 OpenTracks crashes / phone GPS lost

- Metrics naturally freeze. No banner, no vibration. Companion logs the event.

## 9. Permissions

### Companion

- `BLUETOOTH_CONNECT` (runtime, API 31+) — for PebbleKit
- `FOREGROUND_SERVICE` (Android 9+; normal permission) — for run-state foreground service (§5.1)
- `FOREGROUND_SERVICE_CONNECTED_DEVICE` (API 34+; normal permission) — required to match the service's `foregroundServiceType="connectedDevice"` declaration
- `POST_NOTIFICATIONS` (API 33+; runtime, requested at first launch) — for the recording notification posted while a run is active
- `<queries>` manifest block listing:
  - `de.dennisguse.opentracks` (and `.playstore`, `.debug`, `.nightly`)
  - Intent action `io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH` (required for PebbleKitAndroid2 picker on Android 11+)
  - Intent actions `de.dennisguse.opentracks.publicapi.StartRecording` / `…StopRecording` (used for `resolveActivity` probes)

### Not requested

- No location, storage, or internet permissions
- No `QUERY_ALL_PACKAGES`

## 10. Distribution

### 10.1 Watchapp

Pebble Appstore (`apps.repebble.com`). Submit `.pbw` via `dev-portal.rebble.io` or `pebble publish`. Use `package.json` (not deprecated `appinfo.json`).

### 10.2 Companion

**Primary: IzzyOnDroid** (`apt.izzysoft.de/fdroid`). Accepts developer-signed APKs directly. Submission via Codeberg issue at `codeberg.org/IzzyOnDroid/repo`. Requires FOSS license + public source repo + Fastlane metadata.

**Secondary: F-Droid official**. Submit metadata MR to `gitlab.com/fdroid/fdroiddata`. **Reproducible builds not required.**

PebbleKitAndroid2 publishes to **Maven Central** as of v1.0.0 (April 2026; see `pebble-dev/PebbleKitAndroid2/.github/workflows/publish.yml`). Coordinate: `io.rebble.pebblekit2:client`. The earlier draft of this spec warned about JitPack-only distribution; that's no longer the case and F-Droid official inclusion is not blocked on the dependency side. Reproducible builds for the companion app itself remain to be evaluated separately.

### 10.3 License

Apache 2.0 (both repos). Matches OpenTracks.

### 10.4 Repositories

Codeberg, two repos:
- `pebble-opentracks-watchapp`
- `pebble-opentracks-companion`

Each: `README.md`, `LICENSE`, one-line privacy statement.

## 11. Known limitations

- No pause. OpenTracks Public API has no Pause intent. To "pause," stop and start a new run, or accept that paused-stats display zero pace until you resume motion.
- Externally-started OpenTracks recordings cannot be detected. Concurrent start behavior is whatever OpenTracks does.
- HR from watch HRM is displayed only, not in GPX.
- External HR (BLE strap via OpenTracks) is deferred. v4.27's dashboard `DataProvider.DATA_PROJECTIONMAP_TRACKPOINTS` omits `sensor_heartrate`, and the Pebble SDK can't act as a BLE GATT central — so the only realistic future path is companion-mediated BLE forwarding. Out of scope for v1.
- Public API enablement is not auto-verified.
- PebbleKitAndroid2 v1.1.0 (April 2026) is the current pinned version. Pin in `build.gradle.kts`; expect API drift across minor versions.
- Pebble Time 2 touchscreen, speaker, second mic, and RGB backlight are not enabled in firmware as of May 2026. Buttons-only UI.
- An ongoing notification ("Recording — see your watch") is shown while a run is active and cannot be dismissed until you stop the run. Matches OpenTracks's own behaviour; users typically see both side-by-side during the same run.
- **Runs are started from the companion app's Home screen, not from the watch.** The watchapp launches onto an idle screen (§4.2.1) with the text "Start a run on your phone" and waits for the companion's `RUN_STARTED` before transitioning to active-run. No on-watch start affordance — pre-run was removed in v0.1. The watch retains the in-app stop path (Down → stop-confirm → Up sends `CMD_STOP`), which works because the user has the watchapp foregrounded at that moment. Three workarounds for *watch-initiated start while companion is backgrounded* were attempted in v0.1 development — PendingIntent, in-service `startForeground`, CompanionDeviceManager pairing — and each failed on Android 14+ in different ways (`ForegroundServiceStartNotAllowedException`, fragile PI lifetime, CDM scan unable to find the Pebble while connected to the Pebble Android app). v0.1 ships with the foreground-app-only start constraint; same pattern as Strava and most Android fitness apps.

## 12. Testing

Manual only. No automated tests.

## 13. Versioning

Semantic versioning, both repos in lockstep. v1 release: `1.0.0`. No protocol version field — AppMessage ACKs and ignored-unknown-keys policy handle compatibility.

## 14. Implementation order

1. Companion: Android project skeleton, OpenTracks installed-check (single F-Droid package)
2. Companion: OpenTracks Public API (StartRecording, receive Dashboard URIs, log TrackPoint stream)
3. Companion: derived metrics (current pace, distance, time)
4. Watchapp: emery project skeleton, pre-run screen, AppMessage send/receive
5. Watchapp + Companion: end-to-end run start/stop with stub metrics on watch
6. Watchapp: active-run screen layout (5 metrics)
7. Watchapp: HR sampling + cadence derivation (local display only)
8. Watchapp: stop-confirm + run-summary screens
9. Companion: home screen polish, first-launch instructions
10. Manual testing on real PT2 + Android device
11. IzzyOnDroid submission, Pebble Appstore submission

End of specification.
