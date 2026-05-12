{
  description = "OpenPebbleRun — Pebble Time 2 run-tracking watchapp + Android companion for OpenTracks";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          # Android SDK + OpenJDK come with non-free licenses.
          config.allowUnfree = true;
          config.android_sdk.accept_license = true;
        };

        # Android SDK components needed for the companion app (spec §5.1: minSdk 26, targetSdk 35).
        # androidenv assembles a usable SDK derivation from declared components.
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "35" "34" ];
          buildToolsVersions = [ "35.0.0" ];
          includeNDK = false;
          # cmdline-tools is required for sdkmanager/avdmanager.
          cmdLineToolsVersion = "11.0";
          # Platform-tools provides adb.
          includeSystemImages = false;
        };

        androidSdk = androidComposition.androidsdk;
      in {
        devShells.default = pkgs.mkShell {
          name = "openpebblerun-dev";

          buildInputs = with pkgs; [
            # === Companion (Android Kotlin) ===
            jdk17
            gradle
            androidSdk
            kotlin

            # === Watchapp (Pebble C) ===
            # Pebble SDK is not packaged in nixpkgs. Users must install it manually
            # from https://help.rebble.io/sdk/ and ensure `pebble` is on PATH.
            # Python 2.7 is required by the legacy Pebble SDK toolchain.
            # We provide python3 here for general scripting; the SDK ships its own venv.
            python3

            # === General ===
            git
            ripgrep
            gnumake
            curl
          ];

          # Tell Gradle where to find the Android SDK.
          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";

          # JDK for Gradle.
          JAVA_HOME = "${pkgs.jdk17}/lib/openjdk";

          shellHook = ''
            echo "OpenPebbleRun dev shell"
            echo "  Android SDK: $ANDROID_HOME"
            echo "  JDK:         $JAVA_HOME"
            if command -v pebble >/dev/null 2>&1; then
              echo "  Pebble SDK:  $(command -v pebble)"
            else
              echo "  Pebble SDK:  NOT on PATH — install from https://help.rebble.io/sdk/ for watchapp builds."
            fi
          '';
        };
      });
}
