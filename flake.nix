{
  description = "OpenPebbleRun — Pebble Time 2 run-tracking watchapp + Android companion for OpenTracks";

  # Self-advertise the pebble.nix cachix. The first `nix develop` will prompt
  # the user to trust it. Without the cache, `arm-embedded-toolchain-4.7`
  # tries to build from source — GMP 4.3.2 + modern host GCC fails.
  nixConfig = {
    extra-substituters = [ "https://pebble.cachix.org" ];
    extra-trusted-public-keys = [
      "pebble.cachix.org-1:1SYzkyMyCNYELT9CCtBmnq+S6/QfWNFq8ojQzeMmCp4="
    ];
  };

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";

    # pebble.nix supplies the NixOS-patched ARM embedded toolchain and the
    # `qemu-pebble` emulator binary. We bring in its overlay but DO NOT use
    # `pebbleEnv` — that bundles a `pebble-tool` version (5.0.5) that's older
    # than what modern SDK manifests require (>= 5.0.32). Instead we install
    # `pebble-tool` via uv (the official upstream method per
    # https://developer.repebble.com/sdk).
    #
    # Recommended one-time cache setup to skip building the toolchain locally:
    #     cachix use pebble
    pebble.url = "github:pebble-dev/pebble.nix";
  };

  outputs = { self, nixpkgs, flake-utils, pebble }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            # Android SDK + OpenJDK ship with non-free licenses.
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # Pull pebble.nix's binaries DIRECTLY from its package outputs, not via
        # overlay. Reason: applying the overlay rebuilds these derivations
        # against our own nixpkgs, producing different store-path hashes than
        # what pebble.nix's CI uploaded to pebble.cachix.org → forced source
        # build of arm-embedded-toolchain (which fails because its vendored
        # GMP 4.3.2 won't compile against modern host GCC). Pulling from
        # pebble.packages.${system}.* uses pebble.nix's own pinned nixpkgs, so
        # the hashes match the cache.
        pebblePkgs = pebble.packages.${system};

        # Android SDK components for the companion app.
        # Spec §5.1: minSdk 26, targetSdk 35 (runtime behavior version).
        # compileSdk is 36 because transitive deps (notably androidx.core
        # 1.17.0 via PebbleKitAndroid2 1.1.0) require Android 36 APIs to be
        # available at compile time. compileSdk and targetSdk are decoupled —
        # bumping compileSdk doesn't change runtime behavior.
        # 35.0.0 + 34.0.0 build-tools stay around because AGP toolchain
        # references them transitively and auto-install fails against the
        # read-only nix store.
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "36" "35" "34" ];
          buildToolsVersions = [ "36.0.0" "35.0.0" "34.0.0" ];
          includeNDK = false;
          cmdLineToolsVersion = "11.0";
          includeSystemImages = false;
        };
        androidSdk = androidComposition.androidsdk;
      in {
        devShells.default = pkgs.mkShell {
          name = "openpebblerun-dev";

          packages = (with pkgs; [
            # === Watchapp ===
            # `pebble-tool` itself is installed by the user via `uv tool install
            # pebble-tool` — see shellHook. We provide everything else it shells
            # out to: nodejs (for pypkjs), ARM compiler, qemu, pdc tools.
            uv                          # canonical pebble-tool installer
            nodejs                      # required by pebble-tool + pypkjs

            # === Companion (Android Kotlin) ===
            jdk17
            gradle
            androidSdk
            kotlin

            # === General ===
            git
            ripgrep
            gnumake
            curl
            # === ARM cross-compiler ===
            # pebble.nix's `arm-embedded-toolchain` is GCC 4.7.4 (2014), too
            # old for the current CoreDevices SDK 4.9.169 which pairs with
            # GCC 14.2.1 and uses warning flags like `-Werror=format-truncation`
            # that GCC 4.7 doesn't recognise. Use the modern toolchain from
            # nixpkgs (currently 15.2.rel1) — the Pebble OS app ABI is plain
            # ARMv7-M EABI; binary compat is fine across modern GCC versions.
            gcc-arm-embedded
          ]) ++ [
            # === Pebble-specific binaries (from pebble.cachix.org via pebble.nix CI) ===
            pebblePkgs.pebble-qemu      # emulator
            pebblePkgs.pdc_tool         # Pebble draw-command tool
            pebblePkgs.pdc-sequencer    # animation sequencing
            # pebble-toolchain-bin dropped: also GCC 4.7 era, superseded.
          ];

          # === Android env ===
          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk17}/lib/openjdk";

          # === Pebble env ===
          #
          # pebble-tool 5.0.35 reads PEBBLE_QEMU_PATH to locate qemu-pebble
          # (`pebble_tool/sdk/emulator.py:40`), falling back to whatever is on
          # PATH. We point it directly at the nix-patched binary.
          PEBBLE_QEMU_PATH = "${pebblePkgs.pebble-qemu}/bin/qemu-pebble";

          # pebble-tool prepends PEBBLE_EXTRA_PATH to its own PATH *after* the
          # ~/.pebble-sdk/SDKs/<v>/toolchain/arm-none-eabi/bin entry it adds
          # (`pebble_tool/sdk/__init__.py:74-78`), so anything we put here
          # shadows whatever `pebble sdk install` downloaded. That's exactly
          # what we want: the SDK install drops glibc-linked binaries that
          # NixOS can't execute; our nix-provided toolchain wins because it
          # sits earlier on PATH.
          PEBBLE_EXTRA_PATH = pkgs.lib.makeBinPath [
            pkgs.gcc-arm-embedded
            pebblePkgs.pdc_tool
            pebblePkgs.pdc-sequencer
          ];

          # pebble-tool's Python deps (libpebble2, freetype-py, pypkjs's JS
          # bridge, …) load native libraries at runtime via ctypes. NixOS
          # doesn't have a system-wide ldconfig pointing at these, so we
          # expose them via LD_LIBRARY_PATH.
          LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath (with pkgs; [
            freetype
            zlib
            glib
            pixman
            SDL2
          ]);

          # Pebble Developer Connection IP for `pebble install --phone`.
          # Override per-session: `PEBBLE_PHONE=10.0.0.42 nix develop`.
          PEBBLE_PHONE = "";

          shellHook = ''
            # uv tool installs land in ~/.local/bin by default; put it on PATH
            # so freshly-installed pebble-tool is visible.
            export PATH="$HOME/.local/bin:$PATH"

            # === Idempotent first-run bootstrap ===
            # Set OPENPEBBLERUN_SKIP_SETUP=1 to opt out (CI, debugging,
            # replicating bare upstream behavior).
            if [ -z "$OPENPEBBLERUN_SKIP_SETUP" ]; then
              if ! command -v pebble >/dev/null 2>&1; then
                echo "→ Bootstrapping pebble-tool v5.0.35+ via uv → ~/.local/bin"
                if uv tool install pebble-tool --python 3.13; then
                  # Re-evaluate PATH so the new shim is visible to the
                  # SDK check below.
                  hash -r 2>/dev/null || true
                else
                  echo "  ✗ uv tool install failed. Run manually:"
                  echo "      uv tool install pebble-tool --python 3.13"
                fi
              fi
              if command -v pebble >/dev/null 2>&1 \
                && [ ! -d "$HOME/.pebble-sdk/SDKs" \
                     -o -z "$(ls -A "$HOME/.pebble-sdk/SDKs" 2>/dev/null)" ]; then
                echo "→ Installing Pebble SDK (latest)"
                echo "  By proceeding you accept the Pebble TOS:"
                echo "    https://developer.rebble.io/developer.getpebble.com/legal/terms-of-use/"
                if ! pebble sdk install latest; then
                  echo "  ✗ pebble sdk install failed. Run manually:"
                  echo "      pebble sdk install latest"
                fi
              fi
            fi

            # === Status line ===
            echo "OpenPebbleRun dev shell"
            if command -v pebble >/dev/null 2>&1; then
              echo "  Pebble:    $(pebble --version 2>&1 | head -1)"
            else
              echo "  Pebble:    NOT INSTALLED (run \`make pebble-setup\`)"
            fi
            if [ -d "$HOME/.pebble-sdk/SDKs" ] \
               && [ -n "$(ls -A "$HOME/.pebble-sdk/SDKs" 2>/dev/null)" ]; then
              echo "  SDK:       ✓ $(ls "$HOME/.pebble-sdk/SDKs" | head -1)"
            else
              echo "  SDK:       ✗ (run \`make pebble-setup\`)"
            fi
            echo "  Android:   $ANDROID_HOME"
            echo "  JDK:       $JAVA_HOME"
            if [ -n "$PEBBLE_PHONE" ]; then
              echo "  Phone IP:  $PEBBLE_PHONE"
            else
              echo "  Phone IP:  (unset — \`PEBBLE_PHONE=<ip> nix develop\` or set per-cmd)"
            fi
            # If arm-embedded-toolchain just rebuilt from source, the user
            # missed the cachix. We can't detect that here reliably, so just
            # surface the README pointer once per shell.
            echo "  Cache:     pebble.cachix.org (NixOS: see README §Cachix)"
          '';
        };
      });
}
