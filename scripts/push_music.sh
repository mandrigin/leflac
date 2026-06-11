#!/bin/bash

# Check if an argument is provided
if [ -z "$1" ]; then
  echo "Usage: $0 <path_to_folder>"
  exit 1
fi

FOLDER_PATH="$1"

# Check if the folder exists
if [ ! -d "$FOLDER_PATH" ]; then
  echo "Error: Directory '$FOLDER_PATH' does not exist."
  exit 1
fi

# Check if adb sees a device
adb get-state 1>/dev/null 2>&1
if [ $? -ne 0 ]; then
  echo "Error: No Android device connected or adb is not running."
  exit 1
fi

# Extract the base folder name
FOLDER_NAME=$(basename "$FOLDER_PATH")

echo "Pushing '$FOLDER_NAME' to /sdcard/Music/ on the device..."

# Push the folder to the Music directory
adb push "$FOLDER_PATH" "/sdcard/Music/"

if [ $? -eq 0 ]; then
  echo "✅ Successfully pushed to device."
  echo "Note: The FLAC Player will pick up these files the next time you open it (or it runs scanLibrary)."
else
  echo "❌ Failed to push files to the device."
  exit 1
fi
