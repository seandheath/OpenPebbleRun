# Watchapp (Pebble Time 2 / emery) — delegates to `pebble` CLI.
#
# All targets assume `pebble` is on PATH; the top-level Makefile already
# short-circuits with a skip notice when it isn't. Install the SDK from
# https://help.rebble.io/sdk/ for these to work.

.PHONY: build test run clean lint fmt install emulator logs

build:
	pebble build

# No automated tests for the watchapp (spec §12: manual only). Keep target so
# top-level `make test` doesn't fail.
test:
	@echo "Watchapp: manual testing only (spec §12). Use 'make run' to install on a watch."

# Install on a connected phone (which forwards to the paired Pebble).
run install:
	pebble install --phone $(PEBBLE_PHONE_IP)

emulator:
	pebble install --emulator emery

logs:
	pebble logs --phone $(PEBBLE_PHONE_IP)

clean:
	pebble clean

lint:
	@echo "No watchapp linter wired (Pebble C SDK ships no linter)."

fmt:
	@echo "No watchapp formatter wired (Pebble C SDK ships no formatter)."
