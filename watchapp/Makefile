# Watchapp (Pebble Time 2 / emery) — delegates to `pebble` CLI.
#
# All targets assume `pebble` is on PATH; the top-level Makefile already
# short-circuits with a skip notice when it isn't. Install the SDK from
# https://help.rebble.io/sdk/ for these to work.
#
# CC / AR pinned to arm-none-eabi-* because the SDK's bundled `waflib`
# autodetects compilers via `find_program(['gcc', 'cc'])`, which picks the
# host gcc on a clean PATH. Setting these env vars steers the autodetect to
# the ARM cross-compiler (which lives on PEBBLE_EXTRA_PATH from the flake).

PEBBLE_CC ?= arm-none-eabi-gcc
PEBBLE_AR ?= arm-none-eabi-ar

.PHONY: build test run clean lint fmt install emulator logs

build:
	CC=$(PEBBLE_CC) AR=$(PEBBLE_AR) pebble build

# No automated tests for the watchapp (spec §12: manual only). Keep target so
# top-level `make test` doesn't fail.
test:
	@echo "Watchapp: manual testing only (spec §12). Use 'make run' to install on a watch."

# Install on a connected phone (which forwards to the paired Pebble).
# PEBBLE_PHONE is exported by the dev shell's flake.nix; can be overridden
# inline (e.g., `PEBBLE_PHONE=10.0.0.42 make install`). PEBBLE_PHONE_IP is
# accepted as a fallback alias.
PHONE := $(if $(PEBBLE_PHONE),$(PEBBLE_PHONE),$(PEBBLE_PHONE_IP))

run install:
	@if [ -z "$(PHONE)" ]; then \
	  echo "PEBBLE_PHONE is unset. Set it before running:"; \
	  echo "  PEBBLE_PHONE=<ip> make install"; \
	  echo "or export it in the shell:"; \
	  echo "  export PEBBLE_PHONE=<ip>"; \
	  exit 1; \
	fi
	pebble install --phone $(PHONE)

emulator:
	pebble install --emulator emery

logs:
	@if [ -z "$(PHONE)" ]; then \
	  echo "PEBBLE_PHONE is unset."; exit 1; \
	fi
	pebble logs --phone $(PHONE)

clean:
	pebble clean

lint:
	@echo "No watchapp linter wired (Pebble C SDK ships no linter)."

fmt:
	@echo "No watchapp formatter wired (Pebble C SDK ships no formatter)."
