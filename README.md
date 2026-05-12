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
nix develop          # JDK 17, Android SDK, gradle + pebble-tool/qemu/ARM toolchain
make build           # delegates to companion/ and watchapp/
make test            # delegates to both
```

The Pebble SDK comes from [pebble.nix](https://github.com/pebble-dev/pebble.nix). To skip rebuilding the toolchain locally, opt in to the project's binary cache once:

```sh
cachix use pebble    # non-NixOS
# on NixOS: add `pebble.cachix.org` to nix.settings.substituters
```

For watchapp installs, set the Pebble Developer Connection IP before entering the shell or per-invocation:

```sh
PEBBLE_PHONE=192.168.1.42 nix develop
# then inside the shell:
cd watchapp && pebble install --phone "$PEBBLE_PHONE"
```

## License

Apache 2.0. See [`LICENSE`](LICENSE). Matches OpenTracks.

## Repositories

This is a monorepo. Spec §10.4 anticipates two published repos at distribution time (`pebble-opentracks-watchapp`, `pebble-opentracks-companion`); they will be carved out of this tree via `git filter-repo` when publishing.
