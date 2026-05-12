{
  description = "OpenPebbleRun — Pebble Time 2 run-tracking watchapp + Android companion for OpenTracks";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";

    # Pebble SDK + ARM toolchain + emulator. See https://github.com/pebble-dev/pebble.nix
    # Recommended cachix setup (skip rebuilding the toolchain locally):
    #     cachix use pebble
    pebble.url = "github:pebble-dev/pebble.nix";
  };

  outputs = { self, nixpkgs, flake-utils, pebble }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          # Android SDK + OpenJDK come with non-free licenses.
          config.allowUnfree = true;
          config.android_sdk.accept_license = true;
        };

        # Android SDK components for the companion app (spec §5.1: minSdk 26,
        # targetSdk 35). 35.0.0 build-tools is what AGP 8.7 + compileSdk=35
        # actually uses; 34.0.0 is included because the AGP toolchain still
        # references it from somewhere and tries to auto-install if missing,
        # which fails against the read-only nix store.
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "35" "34" ];
          buildToolsVersions = [ "35.0.0" "34.0.0" ];
          includeNDK = false;
          cmdLineToolsVersion = "11.0";
          includeSystemImages = false;
        };

        androidSdk = androidComposition.androidsdk;
      in {
        # Compose pebbleEnv (pebble-tool, ARM toolchain, qemu, nodejs) with the
        # Android tooling needed for the companion. `pebbleEnv` forwards any
        # extra attrs straight through to mkShell, so env vars and shellHook
        # pass through cleanly.
        #
        # `withCoreDevices = true` swaps in the CoreDevices pebble-tool fork.
        # Spec §4.1 targets the `emery` platform (Pebble Time 2) which only the
        # CoreDevices toolchain supports.
        devShells.default = pebble.pebbleEnv.${system} {
          withCoreDevices = true;

          # Pebble Developer Connection IP. Override at the shell:
          #     PEBBLE_PHONE=192.168.1.42 nix develop
          # or set per-user in direnv.
          devServerIP = "";

          # Additional packages for the companion build + general dev.
          packages = with pkgs; [
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

          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk17}/lib/openjdk";

          shellHook = ''
            echo "OpenPebbleRun dev shell"
            echo "  Pebble:      $(command -v pebble || echo MISSING)"
            echo "  Android SDK: $ANDROID_HOME"
            echo "  JDK:         $JAVA_HOME"
            if [ -n "$PEBBLE_PHONE" ]; then
              echo "  PEBBLE_PHONE=$PEBBLE_PHONE"
            else
              echo "  PEBBLE_PHONE not set — \`pebble install --phone <ip>\` needs an IP."
            fi
          '';
        };
      });
}
