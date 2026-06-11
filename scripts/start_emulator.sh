#!/bin/bash

# PATH setup
SDK_DIR="${HOME}/Library/Android/sdk"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"

EMULATOR_BIN="${SDK_DIR}/emulator/emulator"
AVDMANAGER_BIN="${SDK_DIR}/cmdline-tools/latest/bin/avdmanager"
SDKMANAGER_BIN="${SDK_DIR}/cmdline-tools/latest/bin/sdkmanager"

# Check if emulator binary exists
if [ ! -f "$EMULATOR_BIN" ]; then
    echo "Error: Emulator binary not found at $EMULATOR_BIN"
    exit 1
fi

# Detect Host Architecture
ARCH=$(uname -m)
AVD_NAME=""

if [ "$ARCH" == "arm64" ]; then
    echo "Detected ARM64 host. Looking for compatible AVDs..."
    AVD_NAME=$("$EMULATOR_BIN" -list-avds | grep -i "arm64" | head -n 1)
fi

# Fallback to any AVD if specific one not found
if [ -z "$AVD_NAME" ]; then
    AVD_NAME=$("$EMULATOR_BIN" -list-avds | head -n 1)
fi

# If no AVD found, try to create one (Simulating CLI setup)
if [ -z "$AVD_NAME" ]; then
    echo "No AVDs found. Attempting to create 'NothingSim'..."
    
    # Select System Image based on Arch
    if [ "$ARCH" == "arm64" ]; then
        PKG="system-images;android-30;google_apis;arm64-v8a"
    else
        PKG="system-images;android-30;google_apis;x86_64"
    fi
    
    if [ ! -d "${SDK_DIR}/system-images/android-30" ]; then
         echo "Downloading System Image ($PKG)... (Accepting licenses)"
         yes | "$SDKMANAGER_BIN" --licenses > /dev/null
         "$SDKMANAGER_BIN" "$PKG"
    fi
    
    # 2. Create AVD
    echo "Creating AVD..."
    echo "no" | "$AVDMANAGER_BIN" create avd -n NothingSim -k "$PKG" --force
    AVD_NAME="NothingSim"
fi

if [ -z "$AVD_NAME" ]; then
    echo "Failed to find or create an AVD."
    exit 1
fi

echo "Starting emulator: $AVD_NAME"
# Start emulator in background with GPU acceleration
"$EMULATOR_BIN" -avd "$AVD_NAME" -netdelay none -netspeed full -gpu swiftshader_indirect -no-boot-anim > /dev/null 2>&1 &
echo "Emulator launching in background..."
