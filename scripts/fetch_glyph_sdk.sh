#!/bin/bash
# Fetch the Glyph Matrix SDK from Nothing's official developer kit.
#
# The SDK is closed-source and its license prohibits redistribution, so it
# is NOT bundled in this repository — every clone pulls its own copy from
# the source of truth:
#   https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit
set -euo pipefail

SDK_VERSION="2.0"
SDK_URL="https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit/raw/main/glyph-matrix-sdk-${SDK_VERSION}.aar"
LIBS_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/libs"

if ls "$LIBS_DIR"/glyph-matrix-sdk-*.aar >/dev/null 2>&1; then
  echo "Glyph Matrix SDK already present in app/libs — nothing to do."
  exit 0
fi

mkdir -p "$LIBS_DIR"
echo "Fetching Glyph Matrix SDK ${SDK_VERSION} from Nothing's developer kit..."
curl -fsSL -o "$LIBS_DIR/glyph-matrix-sdk-${SDK_VERSION}.aar" "$SDK_URL"
echo "✅ $LIBS_DIR/glyph-matrix-sdk-${SDK_VERSION}.aar"
echo "Note: the SDK is licensed by Nothing Technology Limited under its own"
echo "terms (see LICENSE.md in their developer kit), not this repo's license."
