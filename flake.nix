{
  description = "OpenPebbleRun — Pebble Time 2 run-tracking watchapp + Android companion for OpenTracks";

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
            # pebble.nix's older derivations still reference Python 2.7.
            permittedInsecurePackages = [
              "python-2.7.18.8"
              "python-2.7.18.8-env"
              "python-2.7.18.12"
            ];
          };
          overlays = [ pebble.overlays.default ];
        };

        # Android SDK components for the companion app (spec §5.1: minSdk 26,
        # targetSdk 35). 35.0.0 is what AGP 8.7 + compileSdk = 35 actually uses;
        # 34.0.0 is present because the AGP toolchain still references it from
        # somewhere and tries to auto-install if missing, which fails against
        # the read-only nix store.
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "35" "34" ];
          buildToolsVersions = [ "35.0.0" "34.0.0" ];
          includeNDK = false;
          cmdLineToolsVersion = "11.0";
          includeSystemImages = false;
        };
        androidSdk = androidComposition.androidsdk;
      in {
        devShells.default = pkgs.mkShell {
          name = "openpebblerun-dev";

          packages = with pkgs; [
            # === Watchapp ===
            # `pebble-tool` itself is installed by the user via `uv tool install
            # pebble-tool` — see shellHook. We provide everything else it shells
            # out to: ARM compiler, qemu, JS tooling, and the misc binaries
            # pebble-tool can call (pdc tools, etc.).
            uv                          # canonical pebble-tool installer
            nodejs                      # required by pebble-tool + pypkjs
            arm-embedded-toolchain      # from pebble.nix overlay
            pebble-qemu                 # from pebble.nix overlay
            pebble-toolchain-bin        # from pebble.nix overlay
            pdc_tool
            pdc-sequencer

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
          PEBBLE_QEMU_PATH = "${pkgs.pebble-qemu}/bin/qemu-pebble";

          # pebble-tool prepends PEBBLE_EXTRA_PATH to its own PATH *after* the
          # ~/.pebble-sdk/SDKs/<v>/toolchain/arm-none-eabi/bin entry it adds
          # (`pebble_tool/sdk/__init__.py:74-78`), so anything we put here
          # shadows whatever `pebble sdk install` downloaded. That's exactly
          # what we want: the SDK install drops glibc-linked binaries that
          # NixOS can't execute; our nix-provided toolchain wins because it
          # sits earlier on PATH.
          PEBBLE_EXTRA_PATH = pkgs.lib.makeBinPath (with pkgs; [
            arm-embedded-toolchain
            pebble-toolchain-bin
            pdc_tool
            pdc-sequencer
          ]);

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
            # so the user picks up `pebble` after `uv tool install pebble-tool`.
            export PATH="$HOME/.local/bin:$PATH"

            echo "OpenPebbleRun dev shell"
            echo "  Android SDK: $ANDROID_HOME"
            echo "  JDK:         $JAVA_HOME"
            if command -v pebble >/dev/null 2>&1; then
              echo "  Pebble:      $(pebble --version 2>&1 | head -1)"
            else
              echo ""
              echo "  pebble-tool not installed. One-time setup (per machine):"
              echo "      uv tool install pebble-tool --python 3.13"
              echo "      pebble sdk install latest"
              echo ""
              echo "  ARM toolchain + qemu are already on PEBBLE_EXTRA_PATH from"
              echo "  this shell, so the broken binaries that \`pebble sdk install\`"
              echo "  downloads to ~/.pebble-sdk are ignored at build time."
            fi
            if [ -n "$PEBBLE_PHONE" ]; then
              echo "  PEBBLE_PHONE=$PEBBLE_PHONE"
            else
              echo "  PEBBLE_PHONE not set — \`pebble install --phone <ip>\` needs an IP."
            fi
          '';
        };
      });
}
