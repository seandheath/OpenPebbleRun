# Companion (Android) — delegates to gradle.
# All targets prefer the wrapper (./gradlew) if present; otherwise fall back to
# the system `gradle` provided by `nix develop`.

GRADLE ?= $(if $(wildcard ./gradlew),./gradlew,gradle)

# Debug-build applicationId; uninstalled before install to dodge the
# INSTALL_FAILED_UPDATE_INCOMPATIBLE error that fires whenever the APK on the
# device was signed by a different debug.keystore than the one building now
# (different dev machines / containers each have their own keystore).
PKG ?= run.openpebble.companion.debug

.PHONY: build test run install uninstall logs clean lint fmt wrapper

build:
	$(GRADLE) :app:assembleDebug

test:
	$(GRADLE) :app:testDebugUnitTest

# `|| true` keeps a fresh-device install (where the package isn't present) from
# failing on the uninstall step. The signature-mismatch error otherwise looks
# like a build failure; far cheaper to wipe-and-reinstall every time.
install:
	@echo "Uninstalling $(PKG) (if present) to avoid signing-key mismatch..."
	@adb uninstall $(PKG) || true
	$(GRADLE) :app:installDebug
	@echo "Installed: $(PKG)"
	@echo "  Launch via 'adb shell am start -n $(PKG)/run.openpebble.companion.MainActivity'"

uninstall:
	adb uninstall $(PKG)

# Tag-filtered logcat for live debugging. Mirrors the watchapp's `make logs`
# convenience. Adds ActivityTaskManager:I so OpenTracks ↔ DashboardActivity
# intent dispatch (incl. BAL_BLOCK results) stays visible alongside our app's
# own Log.d output.
logs:
	@adb logcat -v time \
	  DashboardActivity:V OpenTracksApi:V OpenTracksVariant:V \
	  PebbleListenerService:V PebbleMessenger:V MainActivity:V \
	  CdmManager:V \
	  ActivityTaskManager:I '*:S'

# `run` kept as an alias for backward-compatibility — semantically the same
# (gradle's installDebug doesn't actually launch the app).
run: install

clean:
	$(GRADLE) clean

lint:
	$(GRADLE) :app:lintDebug

fmt:
	@echo "No Kotlin formatter wired yet — consider ktlint or detekt"

# Generate the gradle wrapper (run once after checkout).
wrapper:
	gradle wrapper --gradle-version 8.9 --distribution-type bin
