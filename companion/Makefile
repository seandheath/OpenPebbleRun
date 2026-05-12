# Companion (Android) — delegates to gradle.
# All targets prefer the wrapper (./gradlew) if present; otherwise fall back to
# the system `gradle` provided by `nix develop`.

GRADLE ?= $(if $(wildcard ./gradlew),./gradlew,gradle)

.PHONY: build test run clean lint fmt wrapper

build:
	$(GRADLE) :app:assembleDebug

test:
	$(GRADLE) :app:testDebugUnitTest

run:
	$(GRADLE) :app:installDebug
	@echo "Installed. Launch from device or run:"
	@echo "  adb shell am start -n run.openpebble.companion.debug/run.openpebble.companion.MainActivity"

clean:
	$(GRADLE) clean

lint:
	$(GRADLE) :app:lintDebug

fmt:
	@echo "No Kotlin formatter wired yet — consider ktlint or detekt"

# Generate the gradle wrapper (run once after checkout).
wrapper:
	gradle wrapper --gradle-version 8.9 --distribution-type bin
