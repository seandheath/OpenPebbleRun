# OpenPebbleRun — top-level Makefile. Delegates to companion/ and watchapp/.
# Targets that depend on absent tools (Pebble SDK, connected device) print a
# skip notice and exit 0 so that CI / dev shells without those tools still
# succeed for the targets they can satisfy.

.PHONY: all build test run clean lint fmt help \
        companion-build companion-test companion-run companion-clean companion-lint companion-fmt \
        watchapp-build watchapp-test watchapp-run watchapp-clean watchapp-lint watchapp-fmt

all: build

help:
	@echo "OpenPebbleRun build targets:"
	@echo "  build   - build both companion and watchapp"
	@echo "  test    - run tests for both"
	@echo "  run     - install/run on connected device (Android) and watch (Pebble)"
	@echo "  clean   - remove build artifacts"
	@echo "  lint    - run linters"
	@echo "  fmt     - apply formatters"
	@echo ""
	@echo "Per-component targets are prefixed companion- or watchapp-."

# === Aggregate targets ===

build: companion-build watchapp-build
test:  companion-test  watchapp-test
run:   companion-run   watchapp-run
clean: companion-clean watchapp-clean
lint:  companion-lint  watchapp-lint
fmt:   companion-fmt   watchapp-fmt

# === Companion delegation ===
# Static pattern rule: forces the recipe to run for each named phony target.

companion-build companion-test companion-run companion-clean companion-lint companion-fmt: companion-% :
	@if [ ! -f companion/Makefile ]; then \
	  echo "[skip] companion/Makefile not present yet ($*)"; \
	else \
	  $(MAKE) -C companion $*; \
	fi

# === Watchapp delegation ===
# `pebble` must be on PATH. If not, every target is a no-op skip.

watchapp-build watchapp-test watchapp-run watchapp-clean watchapp-lint watchapp-fmt: watchapp-% :
	@if ! command -v pebble >/dev/null 2>&1; then \
	  echo "[skip] pebble not on PATH — install Pebble SDK from https://help.rebble.io/sdk/ ($*)"; \
	elif [ ! -f watchapp/Makefile ]; then \
	  echo "[skip] watchapp/Makefile not present yet ($*)"; \
	else \
	  $(MAKE) -C watchapp $*; \
	fi
