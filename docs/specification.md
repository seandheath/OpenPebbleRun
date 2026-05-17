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
- External HR via OpenTracks (the dashboard URI does not project `sensor_heartrate`)

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

The companion uses **PebbleKitAndroid2** (`pebble-dev/PebbleKitAndroid2`). Its bound-service architecture is woken by the Pebble companion app (`coredevices.coreapp`, min v1.0.7.7) when the watchapp opens. The bound service is promoted to a foreground service for the duration of an active run (§5.1).

The companion uses OpenTracks's **Public API** (Intents) to start/stop and **Dashboard API** (content URIs + ContentObserver) for live data.

## 4. Pebble watchapp

### 4.1 Platform

- **SDK**: Pebble C SDK 4.9.148 or later
- **Target platform**: `emery` only (200×228, 64-color)
- **Capabilities** in `package.json`: `["health"]`
- **App kind**: watchapp

### 4.2 Screens

Five screens. Launch lands on idle; linear transitions
idle → active-run → stop-confirm → stopping → run-summary → (Back exits
watchapp). Companion-initiated stop short-circuits stop-confirm + stopping
and takes active-run directly to run-summary.

#### 4.2.1 Idle

Watchapp entry point. Shown until a run is active.

```
┌────────────────────────┐
│                        │
│    OpenPebbleRun       │   title (bold)
│                        │
│   Press Select       ▶ │   prompt (regular) + play icon
│     to start           │     next to the Select gutter
│                        │
└────────────────────────┘
```

- Title: "OpenPebbleRun" centered, `FONT_KEY_GOTHIC_28_BOLD`.
- Prompt: "Press Select to start", wraps to two lines, `FONT_KEY_GOTHIC_24`.
- Play icon: solid right-pointing triangle at the right edge, vertically
  aligned with the physical Select button — same column the stop-confirm
  screen uses for its ✓/✕ icons, so the affordance reads consistently
  across screens.
- Inbox handler watches `KEY_RUN_STARTED`. On arrival, pushes active-run
  (§4.2.2) on top of the stack. Covers three paths: (a) the cold-launch-
  with-run-active case, where the companion's `PebbleListenerService.onAppOpened`
  replays `RUN_STARTED` within a few hundred ms — idle is effectively a
  brief flash; (b) the launch-then-tap-Start case, where the user opens
  the watchapp first and then taps Start Run on the companion; (c) the
  back-from-error path on the starting screen (§4.2.1a), where idle
  re-arms its inbox handler to keep catching the companion-initiated
  fallback.
- Buttons:
  - **Back**: exit the watchapp (`window_stack_pop_all`).
  - **Select**: send `CMD_START` to the companion, vibrate, and push the
    starting screen (§4.2.1a) which waits for `RUN_STARTED` with a 15 s
    timeout. Requires an active CDM association on the companion side —
    see §5.1.
  - **Up / Down**: no-op.

#### 4.2.1a Starting

Shown after Select pressed on idle, while waiting for the companion to
dispatch OpenTracks's StartRecording and return `RUN_STARTED`.

```
┌────────────────────────┐
│                        │
│      Starting…         │
│                        │
└────────────────────────┘
```

- 15 s timeout. The success path is a multi-stage round trip
  (CMD_START → companion → OpenTracks → Dashboard callback →
  RUN_STARTED), with cold-GPS lock latency dominating the worst case.
- On `RUN_STARTED` arrival: push active-run (§4.2.2), silently remove
  this screen. Idle stays on the stack underneath active-run.
- On timeout: swap title to "Couldn't start. Up = retry, Back = ok".
  - **Up**: re-send `CMD_START`, restart the 15 s timer.
  - **Back**: pop back to idle (re-arms idle's inbox handler so a later
    companion-initiated start still works).
- Other buttons inert in both states.

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

Inbox: a `RUN_STOPPED` message from the companion (sent when the user
stops the run from the companion's Stop Run button) pushes run-summary
on top and removes this screen. The watchapp's UI stays in sync with
OpenTracks's recording state regardless of which side initiated the stop.

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
- **Up** (✓): send `CMD_STOP`, single short vibration, transition to
  the stopping screen (§4.2.4) which waits for the companion's
  `RUN_STOPPED` ack.
- **Down** (✕): return to active-run (which has been ticking underneath —
  its inbox handler stayed installed, so stats are up-to-date on return).
  Invariant: from active-run, pressing **Down twice** (once to enter
  stop-confirm, once to cancel) lands you back on the live stats.
- **Back**: mirrors Down (cancel) — Pebble convention is Back = go back.
- **Select**: no-op.

#### 4.2.4 Stopping

Shown after Up on stop-confirm while the companion's `RUN_STOPPED`
acknowledgment is pending. A single centered "Stopping…" title; no
icons.

State machine:
- **STOPPING** (entered on push). 10-second timeout. Inbox handler
  listens for `RUN_STOPPED`. On `RUN_STOPPED`: push run-summary (§4.2.5)
  on top, remove active-run and self from the stack.
- **ERROR** (on timeout). Title swaps to "Couldn't stop. Up = retry,
  Back = ok." Up resends `CMD_STOP` and re-enters STOPPING. Back falls
  through to run-summary anyway (the run likely did stop and the user
  can verify on the phone). Select / Down are inert in both states.

This screen exists so the watch UI doesn't lie when the stop fails
silently — Bluetooth drops, BAL-blocked intent dispatch, or OpenTracks
misconfigurations all surface as the error state instead of an
inappropriate run-summary.

#### 4.2.5 Run summary

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

Single source — Pebble's built-in optical HRM. External BLE chest straps are not supported.

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
- **Background**: PebbleKitAndroid2 bound service. Runs are started by tapping **Start Run** on the companion's Home screen. The watchapp launches onto an idle screen (§4.2.1) and waits for the companion's `RUN_STARTED` before transitioning to active-run. The watch sends `CMD_STOP` from the in-app stop-confirm flow.
- **CompanionDeviceManager pairing**: the user pairs the Pebble via Android's CDM on first launch. The association grants `REQUEST_COMPANION_RUN_IN_BACKGROUND` + `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`, which together short-circuit Android's Background Activity Launch policy to `BAL_ALLOW_ALLOWLISTED_COMPONENT` for our UID. Without the association, watch-initiated `CMD_STOP` is BAL-blocked on Android 14+ once the FGS BAL window (~10 s) lapses. CDM pairing uses a classic-BT `BluetoothDeviceFilter` with `setAddress(pebbleMac)` (MAC from PebbleKit's `WatchIdentifier`) and `setSingleDevice(true)`. That exact combination triggers AOSP's bonded-device fast path (`CompanionDeviceDiscoveryService.checkBoundDevicesIfNeeded()`), which consults `BluetoothAdapter.getBondedDevices()` directly — no BLE-advertising scan, so the bonded-but-GAP-silent Pebble shows up immediately. `setDeviceProfile(DEVICE_PROFILE_WATCH)` is deliberately omitted; it overrides the fast path and forces a "scanning for watches" dialog that never finds the Pebble.
- **Foreground service while recording**: between Start and Stop the service is promoted to foreground (`foregroundServiceType="connectedDevice"`), matching OpenTracks's `TrackRecordingService` pattern. A low-importance "Recording — see your watch" notification on a dedicated channel is built and passed to `startForeground` (Android's FGS contract requires a Notification object). `POST_NOTIFICATIONS` is **not** requested — on API 33+ this leaves the notification hidden by default, which is the intended state: OpenTracks already posts its own ongoing recording notification, and rendering ours alongside it just clutters the shade. Users who want our tap-to-open-Home affordance can enable the Recording channel via system notification settings. Foreground promotion is initiated by DashboardActivity (which OpenTracks calls back into our app from its own foreground context). The FGS keeps the OS from reaping us mid-run; the CDM association above is what handles BAL.

### 5.2 Screens

#### 5.2.1 First-launch instructions

One screen, shown only if Public API check fails. No multi-step wizard.

- Text: "OpenPebbleRun needs OpenTracks with Public API enabled."
- Steps shown inline:
  1. Install OpenTracks (button → IzzyOnDroid / F-Droid link)
  2. In OpenTracks: Settings → Public API → enable both **Public API** and **Automatic data transfer** (the dashboard-callback gate; recording starts without it but `DashboardActivity` never fires)
  3. Pair Pebble in the official Pebble app
  4. After install, tap **Pair Pebble for background access** on the Home screen so the watch can stop runs while the companion is closed (one-time CDM system dialog).
- Button: "Open OpenTracks settings" (Intent to OpenTracks; falls back to launcher Intent)
- Button: "Done"

#### 5.2.2 Home

Steady-state. Shown after first-launch.

- Pebble: ✓/✗
- OpenTracks: ✓ (variant name) / ✗
- Background access: ✓ Paired / ✗ Not paired. When ✗, an outlined "Pair Pebble for background access" button below the status rows launches the CDM pairing system dialog.
- Text: "Use the button below to start a run, then look at your watch."
- Primary action: a **Start Run** button (becomes **Stop Run** while a run is active, switched live by a 1 Hz Compose tick reading `RunSession.active`). Disabled when OpenTracks isn't installed.
- No settings, no troubleshoot, no run history. (Use OpenTracks for history.)
- While a run is active, an ongoing "Recording — see your watch" notification on a low-importance channel exists (Android requires it for FGS) but is hidden by default — the `POST_NOTIFICATIONS` permission is not requested. Users who enable the channel via system notification settings get a tap-to-open affordance back to this Home screen.

**Start Run side effects.** Tapping the button triggers, in order: (1) launch the watchapp on the Pebble (`PebbleKit startAppOnTheWatch`), (2) fire OpenTracks's `publicapi.StartRecording` Intent. OpenTracks then calls our `DashboardActivity` back (via the `STATS_TARGET_PACKAGE`/`STATS_TARGET_CLASS` extras), and `DashboardActivity` (a) stashes the dashboard URIs in `RunSession`, (b) sends `RUN_STARTED` to the watch, and (c) fires OpenTracks's package launcher Intent (`OpenTracksApi.openApp`) to bring OpenTracks's recording UI to the foreground. Step (c) is the "one-tap start and put the phone away" affordance — it has to live in `DashboardActivity` rather than alongside (1) and (2) because OpenTracks's callback to us races a launcher-Intent fired earlier and would otherwise land `DashboardActivity` on top of OpenTracks.

**Track name.** The companion does not set `TRACK_NAME` on the StartRecording Intent. OpenTracks's own "Default track name" preference applies (Settings → Recording → Default track name; options: Date ISO 8601 / Date local / Number). This honors the user's configured choice without requiring SharedPreferences reads — which third-party apps can't do. `TRACK_CATEGORY` and `TRACK_ICON` are still set to `"running"` (OpenTracks has no equivalent default-category preference).

### 5.3 Computed metrics

Companion derives metrics from the OpenTracks Dashboard **Track** URI and pushes to watch. The TrackPoints URI is no longer consumed — pace is derived from Track-level deltas (see below). DashboardActivity still validates both URIs OpenTracks sends and forwards both in the FGS ClipData for forward-compat, but PebbleListenerService only observes Track.

| Key | Metric | Source | Format |
|---|---|---|---|
| 120 | Pace (current) | rolling 15 s window over `(Track.movingtime, Track.totaldistance)` deltas → sec/mi, capped at 3600 | uint16 sec/mi |
| 122 | Time | Track `movingtime` (ms) → seconds | uint32 sec |
| 123 | Distance | Track `totaldistance` (m) → hundredths of a mile | uint32 |

**Pace window.** `PaceWindow` (companion/`metrics/PaceWindow.kt`) buffers `(movingTimeSec, distMeters)` samples and computes pace as `Δdistance / Δmovingtime` across the oldest sample within the 15 s window and the newest. Pushed once per Track read (5 s poll + ContentObserver). Movingtime axis (not wall-clock) means the window naturally excludes paused periods. Window is reset on each fresh PROMOTE_FOREGROUND URI stash and on run stop. Pace is null (watch renders `--:--`) when the window holds < 2 samples or the runner is stationary on movingtime.

HR and cadence come from the watch and are displayed there directly. The companion does not read or forward HR.

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

`intent.data` is **not** populated; ignore it.

**Track URI columns used** (lowercase, read by name with `getColumnIndexOrThrow`):
- `movingtime` (long, ms)
- `totaldistance` (float, meters)

**TrackPoints URI columns used:**
- `speed` (float, m/s)
- `time` (long, epoch ms)

The dashboard projection (`DataProvider.DATA_PROJECTIONMAP_TRACKPOINTS`) exposes `_id, trackid, latitude, longitude, time, type, speed`. `sensor_heartrate` and `sensor_cadence` are not present and not read.

Column identifiers are lowercase Java String constants in `TracksColumns.java` / `TrackPointsColumns.java`. SQLite is case-insensitive in unquoted SQL but Android's `Cursor.getColumnIndexOrThrow` is case-sensitive on most providers — uppercase names throw silently.

Register `ContentObserver` on both URIs; recompute current pace on TrackPoints changes, recompute distance/time on Track changes.

## 7. Watch ↔ Companion AppMessage protocol

No version negotiation. Both sides ignore unknown keys.

### 7.1 Watch → Companion

| Key | Name | Type | Payload |
|---|---|---|---|
| 1 | `CMD_START` | uint8 | (none) |
| 2 | `CMD_STOP` | uint8 | (none) |

`CMD_START` is sent from the idle screen (§4.2.1) when the user presses
Select. The companion's `PebbleListenerService.handleStart` dispatches
`OpenTracks.publicapi.StartRecording` to the variant package. Success is
signaled implicitly via the normal Dashboard-callback path, which culminates
in `DashboardActivity` sending `RUN_STARTED`. No negative ack — the watch's
starting screen (§4.2.1a) times out after 15 s and offers retry. The
service NACKs when prerequisites aren't met (no OpenTracks installed, or
no CDM association), which surfaces at the PebbleKit layer for log
diagnosis but produces the same watch-visible UX as a silent drop.

### 7.2 Companion → Watch

| Key | Name | Type | Payload |
|---|---|---|---|
| 110 | `RUN_STARTED` | uint8 | (none) |
| 111 | `RUN_STOPPED` | uint8 | (none) |
| 120 | `PACE_CURRENT` | uint16 | sec/mi (capped 3600) |
| 122 | `TIME` | uint32 | seconds |
| 123 | `DISTANCE` | uint32 | hundredths of a mile |

`RUN_STARTED` is sent from `DashboardActivity.onCreate` after OpenTracks
calls us back with the Track / TrackPoints URIs, for both watch-initiated
(§4.2.1) and companion-initiated start. `RUN_STOPPED` is sent after every
`stopRecording` dispatch, whether the stop was initiated by the watch
(CMD_STOP) or by the companion's Stop Run button. They are the
application-level "start/stop completed" acks — the starting screen
(§4.2.1a) and stopping screen (§4.2.4) wait on them before transitioning,
and active-run (§4.2.2) uses `RUN_STOPPED` to leave its screen during a
companion-initiated stop.

### 7.3 Update cadence

| Direction | Message | Frequency |
|---|---|---|
| Companion → Watch | Metric updates (120, 122, 123) | On each Track ContentObserver change + 5 s backstop poll |

All three live metrics (pace, time, distance) update on the same beat — every Track read feeds [PaceWindow] and produces fresh time/distance from the same row, so a single `sendMetrics` call carries an internally consistent snapshot.

## 8. Failure modes

### 8.1 Bluetooth disconnect mid-run

- Watch: metrics dim at 50% after 30s without messages
- Companion: OpenTracks continues recording
- On reconnect: full brightness, no alert

### 8.2 Run start fails (watch-initiated)

- Watch's starting screen (§4.2.1a) times out at 15 s and swaps to
  "Couldn't start. Up = retry / Back = ok". Up re-sends `CMD_START` and
  restarts the timer; Back pops to idle.
- Common cause: no CDM association on the companion side, so the
  `startActivity` dispatch silently BAL_BLOCKs. `PebbleListenerService`
  NACKs the message with a `Log.w` pointing at the Home-screen "Pair
  Pebble for background access" button. No in-app log viewer.
- Other cause: AppMessage never reached the companion (Pebble app
  closed, BT disconnected). Same UX — retry is the watch's only local
  recovery surface.

### 8.2a Run start fails (companion-initiated)

- Companion's Start Run button: if OpenTracks isn't installed or the
  variant detection returns null, `OpenTracksApi.startRecording` logs a
  warning and the Home screen surfaces the result via existing UI hints.

### 8.3 OpenTracks crashes / phone GPS lost

- Metrics naturally freeze. No banner, no vibration. Companion logs the event.

## 9. Permissions

### Companion

- `BLUETOOTH_CONNECT` (runtime, API 31+) — for PebbleKit
- `FOREGROUND_SERVICE` (Android 9+; normal permission) — for run-state foreground service (§5.1)
- `FOREGROUND_SERVICE_CONNECTED_DEVICE` (API 34+; normal permission) — required to match the service's `foregroundServiceType="connectedDevice"` declaration
- `REQUEST_COMPANION_RUN_IN_BACKGROUND` (API 26+; normal; activated by CDM association) — BAL exemption for the listener service
- `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` (API 30+; normal; activated by CDM association) — allows `Service.startForeground()` from a background callback
- `<queries>` manifest block listing:
  - `de.dennisguse.opentracks` (and `.playstore`, `.debug`, `.nightly`)
  - Intent action `io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH` (required for PebbleKitAndroid2 picker on Android 11+)
  - Intent actions `de.dennisguse.opentracks.publicapi.StartRecording` / `…StopRecording` (used for `resolveActivity` probes)

### Not requested

- No location, storage, or internet permissions
- No `QUERY_ALL_PACKAGES`

## 10. Distribution

### 10.1 Watchapp

Pebble Appstore (`apps.repebble.com`). Submit `.pbw` via `dev-portal.rebble.io` or `pebble publish`. Use `package.json`.

### 10.2 Companion

**Primary: IzzyOnDroid** (`apt.izzysoft.de/fdroid`). Accepts developer-signed APKs directly. Submission via Codeberg issue at `codeberg.org/IzzyOnDroid/repo`. Requires FOSS license + public source repo + Fastlane metadata.

**Secondary: F-Droid official**. Submit metadata MR to `gitlab.com/fdroid/fdroiddata`. **Reproducible builds not required.**

PebbleKitAndroid2 publishes to **Maven Central** at coordinate `io.rebble.pebblekit2:client`. F-Droid official inclusion is not blocked on the dependency side. Reproducible builds for the companion app itself are evaluated separately.

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
- Public API enablement is not auto-verified.
- PebbleKitAndroid2 v1.1.0 is the pinned version; expect API drift across minor versions.
- Pebble Time 2 touchscreen, speaker, second mic, and RGB backlight are not enabled in firmware. Buttons-only UI.
- The FGS "Recording — see your watch" notification exists for Android's FGS contract but is hidden by default (we don't request `POST_NOTIFICATIONS`). OpenTracks's own ongoing recording notification covers the user-facing "a run is happening" affordance. Users who enable our Recording channel via system notification settings get a second, dismissible-only-on-stop notification.
- Runs are started from the companion app's Home screen; the watchapp has no on-watch start affordance.
- Watch-initiated stop requires a one-time CompanionDeviceManager pairing (system dialog) on first launch. Without it, stop is silently BAL-blocked on Android 14+ after ~10 s into a run. Home shows the pair status until done.

## 12. Testing

Manual only. No automated tests.

## 13. Versioning

Semantic versioning, both repos in lockstep. v1 release: `1.0.0`. No protocol version field — AppMessage ACKs and ignored-unknown-keys policy handle compatibility.

## 14. Remaining work

- Watchapp: cadence derivation on watch (step counter → rolling SPM).
- Companion: home screen polish; deep-link the first-launch "Open OpenTracks settings" button to the Public API settings page; surface a hint when the dashboard callback never fires.
- Manual testing pass on real PT2 + Android device.
- IzzyOnDroid + Pebble Appstore submissions.

End of specification.
