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
- HR writeback to GPX from watch HRM (Public API doesn't accept it). HR from a BLE strap paired directly to OpenTracks does land in GPX — see §4.3.
- Other Pebble platforms, iOS, languages other than English
- Run state persistence/recovery across companion restarts
- In-app diagnostics, troubleshoot screens, settings UI

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

Three screens. Linear transitions.

#### 4.2.1 Pre-run

Shown on launch.

- App title
- Prompt: "Press Select to start"
- Select: send `CMD_START`, transition to active run (with "Starting..." text shown until `RUN_STARTED` received, 15s timeout → error text "Couldn't start. Open companion app on phone." Any button returns to pre-run.)

#### 4.2.2 Active run

Layout (200×228):

```
┌────────────────────────┐
│        HEART RATE      │  large
│         ### bpm        │
│                        │
│  PACE         CADENCE  │  medium
│  M:SS /mi     ### spm  │
│                        │
│  DIST         TIME     │  small
│  0.00 mi      MM:SS    │
└────────────────────────┘
```

HR, pace, and cadence are visually prominent. Distance and time secondary.

Buttons:
- **Back**: open stop-confirm screen
- **Select / Up / Down**: no-op

If AppMessages stop arriving from companion >30s: dim metrics 50% to indicate stale. No vibration. Resume full brightness when next message arrives.

#### 4.2.3 Stop confirm

Shown when Back pressed.

- Text: "Stop run? Select=Yes Back=No"
- **Select**: send `CMD_STOP`, single short vibration on ack, exit to pre-run
- **Back**: return to active run

### 4.3 Sensors

**Heart rate (dual source, auto-detected):**

Two possible sources:
- **Watch HRM** (default) — Pebble's built-in optical sensor
- **External strap via OpenTracks** — any BLE HR strap paired directly to OpenTracks; HR appears in TrackPoints `SENSOR_HEARTRATE` column and is forwarded by companion to watch

Companion auto-detects which is in use. No user setting.

Watch behavior:
- On app launch: `health_service_set_heart_rate_sample_period(1)` (1 Hz). Subscribe to `HealthEventHeartRateUpdate`. Display from internal HRM.
- On receipt of `HR_SOURCE_EXTERNAL` (key 113) from companion: call `health_service_set_heart_rate_sample_period(0)` to disable HRM. Display HR from incoming `HR_EXTERNAL` (key 124) messages instead.
- On receipt of `HR_SOURCE_INTERNAL` (key 114): re-enable HRM, resume internal display.
- **On any exit path: `health_service_set_heart_rate_sample_period(0)`**. Required to stop battery drain.

Companion behavior (HR source detection):
- Watch incoming TrackPoints' `SENSOR_HEARTRATE` column.
- If 3 consecutive TrackPoints have non-null, non-zero `SENSOR_HEARTRATE`: send `HR_SOURCE_EXTERNAL` to watch. Begin forwarding each new value as `HR_EXTERNAL`.
- If currently external and 30 seconds pass with no non-null `SENSOR_HEARTRATE`: send `HR_SOURCE_INTERNAL` to watch. Stop forwarding.
- Default at run start: internal (watch HRM).

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
- **Background**: PebbleKitAndroid2 bound service only. No foreground service. No `POST_NOTIFICATIONS`.

### 5.2 Screens

#### 5.2.1 First-launch instructions

One screen, shown only if Public API check fails. No multi-step wizard.

- Text: "OpenPebbleRun needs OpenTracks with Public API enabled."
- Steps shown inline:
  1. Install OpenTracks (button → IzzyOnDroid / F-Droid link)
  2. In OpenTracks: Settings → Public API → Enable
  3. Pair Pebble in the official Pebble app
- Button: "Open OpenTracks settings" (Intent to OpenTracks; falls back to launcher Intent)
- Button: "Done"

#### 5.2.2 Home

Steady-state. Shown after first-launch.

- Pebble: ✓/✗
- OpenTracks: ✓ (variant name) / ✗
- Text: "Start runs from your watch."
- No settings, no troubleshoot, no run history. (Use OpenTracks for history.)

### 5.3 Computed metrics

Companion derives metrics from OpenTracks Dashboard URIs and pushes to watch.

| Key | Metric | Source | Format |
|---|---|---|---|
| 120 | Pace (current) | 15s rolling mean of TrackPoints `speed` (m/s) → sec/mi, capped at 3600 | uint16 sec/mi |
| 122 | Time | Track `MOVINGTIME` (ms) → seconds | uint32 sec |
| 123 | Distance | Track `TOTALDISTANCE` (m) → hundredths of a mile | uint32 |

Update on each Dashboard `ContentObserver` notification.

HR and cadence normally come from the watch and are displayed there directly. **Exception**: when companion detects external HR via OpenTracks `SENSOR_HEARTRATE` (BLE strap paired to OpenTracks), companion forwards HR to watch as `HR_EXTERNAL` (key 124) and the watch disables its internal HRM. See §4.3 for the detection state machine. In this case HR is recorded in the OpenTracks GPX automatically (because OpenTracks itself receives it from the strap).

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

After `StartRecording`, OpenTracks invokes companion's Dashboard activity with content URIs (Tracks + TrackPoints) and `FLAG_GRANT_READ_URI_PERMISSION`.

**Track URI columns used** (read by name with `getColumnIndexOrThrow`):
- `MOVINGTIME` (long, ms)
- `TOTALDISTANCE` (float, meters)

**TrackPoints URI columns used:**
- `speed` (float, m/s)
- `time` (long, epoch ms)
- `SENSOR_HEARTRATE` (float, bpm) — may be null if no strap paired

Tolerate missing columns — OpenTracks's sensor schema evolves between versions (refactored in v4.26.0). Register `ContentObserver` on both URIs; recompute current pace on TrackPoints changes, recompute distance/time on Track changes.

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
| 113 | `HR_SOURCE_EXTERNAL` | uint8 | (none) — switch to forwarded HR |
| 114 | `HR_SOURCE_INTERNAL` | uint8 | (none) — switch back to watch HRM |
| 120 | `PACE_CURRENT` | uint16 | sec/mi (capped 3600) |
| 122 | `TIME` | uint32 | seconds |
| 123 | `DISTANCE` | uint32 | hundredths of a mile |
| 124 | `HR_EXTERNAL` | uint16 | bpm (only sent when external source active) |

### 7.3 Update cadence

| Direction | Message | Frequency |
|---|---|---|
| Companion → Watch | Metric updates (120, 122, 123) | On each ContentObserver change |
| Companion → Watch | `HR_EXTERNAL` (124) | On each TrackPoint with non-null `SENSOR_HEARTRATE` |
| Companion → Watch | `HR_SOURCE_*` (113/114) | On source change only |

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
- `<queries>` manifest block listing:
  - `de.dennisguse.opentracks` (and `.playstore`, `.debug`, `.nightly`)
  - Intent action `io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH` (required for PebbleKitAndroid2 picker on Android 11+)

### Not requested

- No location, storage, notifications, foreground service, or internet permissions
- No `QUERY_ALL_PACKAGES`

## 10. Distribution

### 10.1 Watchapp

Pebble Appstore (`apps.repebble.com`). Submit `.pbw` via `dev-portal.rebble.io` or `pebble publish`. Use `package.json` (not deprecated `appinfo.json`).

### 10.2 Companion

**Primary: IzzyOnDroid** (`apt.izzysoft.de/fdroid`). Accepts developer-signed APKs directly. Submission via Codeberg issue at `codeberg.org/IzzyOnDroid/repo`. Requires FOSS license + public source repo + Fastlane metadata.

**Secondary: F-Droid official**. Submit metadata MR to `gitlab.com/fdroid/fdroiddata`. **Reproducible builds not required.**

**Risk**: PebbleKitAndroid2 is distributed via JitPack. JitPack is not on F-Droid's trusted Maven list. May need to vendor the library or wait for Maven Central publication for F-Droid official inclusion. IzzyOnDroid has no such restriction.

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
- HR from watch HRM is displayed only, not in GPX. HR from a BLE strap paired to OpenTracks is recorded in GPX and auto-detected by the companion (watch HRM disables itself when external HR arrives).
- Public API enablement is not auto-verified.
- PebbleKitAndroid2 is alpha (v0.1.0). Pin the version. Expect breakage as it evolves.
- Pebble Time 2 touchscreen, speaker, second mic, and RGB backlight are not enabled in firmware as of May 2026. Buttons-only UI.

## 12. Testing

Manual only. No automated tests.

## 13. Versioning

Semantic versioning, both repos in lockstep. v1 release: `1.0.0`. No protocol version field — AppMessage ACKs and ignored-unknown-keys policy handle compatibility.

## 14. Implementation order

1. Companion: Android project skeleton, OpenTracks variant detection
2. Companion: OpenTracks Public API (StartRecording, receive Dashboard URIs, log TrackPoint stream)
3. Companion: derived metrics (current pace, distance, time)
4. Watchapp: emery project skeleton, pre-run screen, AppMessage send/receive
5. Watchapp + Companion: end-to-end run start/stop with stub metrics on watch
6. Watchapp: active-run screen layout (5 metrics)
7. Watchapp: HR sampling + cadence derivation (local display only)
8. Companion + Watchapp: external HR detection and source switching
9. Watchapp: stop-confirm screen
10. Companion: home screen polish, first-launch instructions
11. Manual testing on real PT2 + Android device (test both HR sources)
12. IzzyOnDroid submission, Pebble Appstore submission

End of specification.
