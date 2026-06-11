#!/bin/bash
#
# push_mixes.sh
# Pushes the locally-downloaded mixes (./mixes) onto the connected
# emulator/device under /sdcard/Music/NebulaBreeze and triggers a media
# scan so the FLAC Player picks them up on its next library scan.
#
# Requirements: adb (Android platform-tools), a running emulator or device.
#
# Usage:
#   ./scripts/push_mixes.sh            # push everything in ./mixes
#   ./scripts/push_mixes.sh ./other    # push from a different local dir

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SRC_DIR="${1:-${REPO_ROOT}/mixes}"
DEST_DIR="/sdcard/Music/NebulaBreeze"

# --- Preflight ---
if ! command -v adb >/dev/null 2>&1; then
    echo "Error: adb not found. Install Android platform-tools."
    exit 1
fi

if [ ! -d "${SRC_DIR}" ]; then
    echo "Error: source dir '${SRC_DIR}' not found. Run download_nebula_breeze.sh first."
    exit 1
fi

if ! adb get-state >/dev/null 2>&1; then
    echo "Error: no device/emulator detected. Start one (e.g. make install-sim) and retry."
    exit 1
fi

echo "Ensuring ${DEST_DIR} exists on device..."
adb shell mkdir -p "${DEST_DIR}"

# Collect .m4a mixes only (the format MediaStore indexes reliably).
shopt -s nullglob nocaseglob
FILES=("${SRC_DIR}"/*.m4a)
shopt -u nullglob nocaseglob

if [ ${#FILES[@]} -eq 0 ]; then
    echo "No .m4a files found in ${SRC_DIR}. Run download_nebula_breeze.sh first."
    exit 1
fi

echo "Pushing ${#FILES[@]} file(s)..."
for f in "${FILES[@]}"; do
    base="$(basename "$f")"
    echo "  -> ${base}"
    adb push "$f" "${DEST_DIR}/${base}" >/dev/null
    # Trigger a real MediaStore scan that extracts metadata. The legacy
    # MEDIA_SCANNER_SCAN_FILE broadcast is a no-op on API 29+, so use the
    # MediaProvider scan_file content method instead (returns the new row URI).
    adb shell "content call --uri content://media --method scan_file --arg '${DEST_DIR}/${base}'" >/dev/null 2>&1 || true
done

echo
echo "Pushed to ${DEST_DIR} and scanned ${#FILES[@]} file(s) into MediaStore."
echo "Open the app (it rescans on launch) — new mixes appear under LIB // MIXES."
