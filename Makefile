# LE FLAC (LF-1) Makefile

PACKAGE_NAME = app.nogarbo.leflac
MAIN_ACTIVITY = .MainActivity

GRADLE_CMD := ./gradlew

.PHONY: all clean build install-phone install-sim run prerequisites

all: build

# Auto-install prerequisites if missing
prerequisites:
	@if [ ! -f "$(GRADLE_CMD)" ]; then \
		echo "Gradle wrapper not found. Bootstrapping..."; \
		./scripts/bootstrap_gradle.sh; \
	fi
	@./scripts/fetch_glyph_sdk.sh

clean: prerequisites
	$(GRADLE_CMD) clean

build: prerequisites
	$(GRADLE_CMD) assembleDebug

install-phone: build
	@echo "Installing to connected device..."
	adb install -r app/build/outputs/apk/debug/app-debug.apk

install-sim: build
	@echo "Installing to emulator..."
	@msg="Starting emulator if not running..."; \
	if ! adb devices | grep -q "emulator"; then \
		echo "$$msg"; \
		./scripts/start_emulator.sh & \
		echo "Waiting for emulator to boot..."; \
		adb wait-for-device; \
	fi
	adb install -r app/build/outputs/apk/debug/app-debug.apk

run: install-phone
	@echo "Launching app..."
	adb shell am start -n $(PACKAGE_NAME)/$(MAIN_ACTIVITY)

run-sim: install-sim
	@echo "Launching app on emulator..."
	adb shell am start -n $(PACKAGE_NAME)/$(MAIN_ACTIVITY)
