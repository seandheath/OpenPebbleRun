# OpenPebbleRun — top-level Makefile. Delegates to companion/ and watchapp/.
# Targets that depend on absent tools (Pebble SDK, connected device) print a
# skip notice and exit 0 so that CI / dev shells without those tools still
# succeed for the targets they can satisfy.

.PHONY: all build test run install clean lint fmt help \
        pebble-setup pebble-setup-clean \
        companion-build companion-test companion-run companion-install companion-clean companion-lint companion-fmt \
        watchapp-build watchapp-test watchapp-run watchapp-install watchapp-clean watchapp-lint watchapp-fmt

all: build

help:
	@echo "OpenPebbleRun build targets:"
	@echo "  build              build both companion APK and watchapp .pbw"
	@echo "  install            adb install companion + 'pebble install --phone' watchapp"
	@echo "                     (requires PEBBLE_PHONE=<ip> in env for the watch)"
	@echo "  test               run tests for both"
	@echo "  run                alias for install (watchapp launches on install)"
	@echo "  clean              remove build artifacts"
	@echo "  lint               run linters"
	@echo "  fmt                apply formatters"
	@echo ""
	@echo "Pebble SDK bootstrap (normally automatic in \`nix develop\`):"
	@echo "  pebble-setup       install pebble-tool via uv + 'pebble sdk install latest'"
	@echo "  pebble-setup-clean uninstall pebble-tool, wipe ~/.pebble-sdk"
	@echo ""
	@echo "Per-component targets are prefixed companion- or watchapp-."

# === Pebble SDK bootstrap ===
# The dev-shell shellHook in flake.nix runs the same commands automatically on
# first `nix develop`. These targets exist for explicit re-bootstrap (after a
# wipe, after a pebble-tool upgrade, or when OPENPEBBLERUN_SKIP_SETUP=1 is set).

pebble-setup:
	@command -v uv >/dev/null 2>&1 || { echo "uv not on PATH — enter \`nix develop\` first."; exit 1; }
	@uv tool install pebble-tool --python 3.13 2>/dev/null \
	  || uv tool upgrade pebble-tool \
	  || { echo "uv tool install/upgrade failed"; exit 1; }
	@if [ ! -d "$$HOME/.pebble-sdk/SDKs" ] || [ -z "$$(ls -A "$$HOME/.pebble-sdk/SDKs" 2>/dev/null)" ]; then \
	  echo "Installing Pebble SDK (latest)..."; \
	  pebble sdk install latest; \
	else \
	  echo "Pebble SDK already installed: $$(ls "$$HOME/.pebble-sdk/SDKs" | head -1)"; \
	fi

pebble-setup-clean:
	@rm -rf "$$HOME/.pebble-sdk"
	@uv tool uninstall pebble-tool 2>/dev/null || true
	@echo "Cleared. Re-enter \`nix develop\` to re-bootstrap, or run \`make pebble-setup\`."

# === Aggregate targets ===

build:   companion-build   watchapp-build
test:    companion-test    watchapp-test
install: companion-install watchapp-install
run:     install
clean:   companion-clean   watchapp-clean
lint:    companion-lint    watchapp-lint
fmt:     companion-fmt     watchapp-fmt

# === Companion delegation ===
# Static pattern rule: forces the recipe to run for each named phony target.

companion-build companion-test companion-run companion-install companion-clean companion-lint companion-fmt: companion-% :
	@if [ ! -f companion/Makefile ]; then \
	  echo "[skip] companion/Makefile not present yet ($*)"; \
	else \
	  $(MAKE) -C companion $*; \
	fi

# === Watchapp delegation ===
# `pebble` must be on PATH. If not, every target is a no-op skip.

watchapp-build watchapp-test watchapp-run watchapp-install watchapp-clean watchapp-lint watchapp-fmt: watchapp-% :
	@if ! command -v pebble >/dev/null 2>&1; then \
	  echo "[skip] pebble not on PATH — install Pebble SDK from https://help.rebble.io/sdk/ ($*)"; \
	elif [ ! -f watchapp/Makefile ]; then \
	  echo "[skip] watchapp/Makefile not present yet ($*)"; \
	else \
	  $(MAKE) -C watchapp $*; \
	fi
