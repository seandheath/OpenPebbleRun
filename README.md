# OpenPebbleRun

Run-tracking for Pebble Time 2 that delegates GPS to [OpenTracks](https://github.com/OpenTracksApp/OpenTracks) on Android.

Two components:

- **`watchapp/`** — Pebble C app for the `emery` platform. Shows heart rate, current pace, cadence, distance, and time. Sends start/stop commands.
- **`companion/`** — Android Kotlin app. Translates watch commands into OpenTracks Public API Intents, reads live data via the Dashboard API, and pushes derived metrics back to the watch.

OpenTracks records the GPX. OpenPebbleRun records nothing. Full spec: [`docs/specification.md`](docs/specification.md). Decision log: [`docs/log.md`](docs/log.md).

## Status

Pre-alpha. **v0.1.0 skeleton** — companion variant detection + Public API + computed metrics; watchapp pre-run screen with AppMessage send. End-to-end run start/stop, active-run UI, HR/cadence, and external HR auto-detect are not yet wired.

## Privacy

No network. No analytics. No tracking beyond what OpenTracks itself records.

## Build

```sh
nix develop          # enters dev shell (JDK, Android SDK, gradle, pebble tools if available)
make build           # delegates to companion/ and watchapp/
make test            # delegates to both
```

The Pebble SDK is not currently packaged in nixpkgs. If `pebble` is not on `PATH`, watchapp targets print a skip message and exit 0 — install the SDK manually from <https://help.rebble.io/sdk/> for watchapp work.

## License

Apache 2.0. See [`LICENSE`](LICENSE). Matches OpenTracks.

## Repositories

This is a monorepo. Spec §10.4 anticipates two published repos at distribution time (`pebble-opentracks-watchapp`, `pebble-opentracks-companion`); they will be carved out of this tree via `git filter-repo` when publishing.
