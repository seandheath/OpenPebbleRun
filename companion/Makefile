# Companion (Android) — delegates to gradle.
# All targets prefer the wrapper (./gradlew) if present; otherwise fall back to
# the system `gradle` provided by `nix develop`.

GRADLE ?= $(if $(wildcard ./gradlew),./gradlew,gradle)

.PHONY: build test run install clean lint fmt wrapper

build:
	$(GRADLE) :app:assembleDebug

test:
	$(GRADLE) :app:testDebugUnitTest

install:
	$(GRADLE) :app:installDebug
	@echo "Installed: run.openpebble.companion.debug"
	@echo "  Launch via 'adb shell am start -n run.openpebble.companion.debug/run.openpebble.companion.MainActivity'"

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
