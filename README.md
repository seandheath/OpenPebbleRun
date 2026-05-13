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
nix develop          # bootstraps pebble-tool + Pebble SDK on first entry
make build           # delegates to companion/ and watchapp/
```

First `nix develop` is slow: it installs `pebble-tool` (via `uv` into `~/.local/bin`) and runs `pebble sdk install latest` (which downloads the SDK to `~/.pebble-sdk`). Subsequent entries detect both as present and are silent. The shellHook prints a one-line TOS disclosure before the SDK install so the consent isn't silent.

Set `OPENPEBBLERUN_SKIP_SETUP=1 nix develop` to bypass the auto-install — useful for CI or to replicate bare upstream behavior.

### Cachix

The flake advertises `pebble.cachix.org` via `nixConfig`. Without the substituter, nix tries to build `arm-embedded-toolchain-4.7` from source — it vendors GMP 4.3.2, which won't compile against modern host GCC.

**On NixOS** you must be in `nix.settings.trusted-users` for `nixConfig`-supplied substituters to apply (security-relevant — see [`docs/log.md`](docs/log.md)):

```nix
# /etc/nixos/configuration.nix
nix.settings.trusted-users = [ "root" "your-username" ];
```

`nixos-rebuild switch`, then `nix develop` should prompt to accept the cache once. Decline-then-rerun caches that choice in `~/.local/share/nix/trusted-settings.json`; delete that file to re-prompt.

**On non-NixOS**, `cachix use pebble` once works (it writes `~/.config/nix/nix.conf`).

### Per-component build

```sh
cd companion && ./gradlew :app:assembleDebug
cd watchapp  && pebble build
```

For watchapp installs, set the Pebble Developer Connection IP (one-shot or via shell entry):

```sh
PEBBLE_PHONE=192.168.1.42 nix develop
cd watchapp && pebble install --phone "$PEBBLE_PHONE"
```

### Manual bootstrap

If the auto-install was skipped or failed, run `make pebble-setup` (idempotent). To start from scratch: `make pebble-setup-clean` wipes `~/.pebble-sdk` and uninstalls pebble-tool.

## License

Apache 2.0. See [`LICENSE`](LICENSE). Matches OpenTracks.

## Repositories

This is a monorepo. Spec §10.4 anticipates two published repos at distribution time (`pebble-opentracks-watchapp`, `pebble-opentracks-companion`); they will be carved out of this tree via `git filter-repo` when publishing.
