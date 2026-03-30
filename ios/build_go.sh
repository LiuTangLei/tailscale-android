#!/bin/bash
# Build Libtailscale.xcframework for iOS using gomobile bind.
#
# Prerequisites:
#   - Go toolchain (matching go.mod's go directive)
#   - gomobile: go install golang.org/x/mobile/cmd/gomobile@latest
#   - gobind:   go install golang.org/x/mobile/cmd/gobind@latest
#
# Usage:
#   cd ios/ && ./build_go.sh
#
# Output:
#   ios/Libtailscale.xcframework  — import this in Xcode

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

OUTPUT="Libtailscale.xcframework"

# Parse arguments
# Usage: ./build_go.sh [--sim | --device | --all]
#   --device  : Build for real device only (ios/arm64) — default
#   --sim     : Build for simulator only (iossimulator/arm64)
#   --all     : Build for both device and simulator
TARGET_FLAG="ios/arm64"
case "${1:-}" in
    --sim)
        TARGET_FLAG="iossimulator/arm64"
        ;;
    --all)
        TARGET_FLAG="ios/arm64,iossimulator/arm64"
        ;;
    --device|"")
        TARGET_FLAG="ios/arm64"
        ;;
    *)
        echo "Usage: $0 [--device | --sim | --all]"
        exit 1
        ;;
esac

# Ensure gomobile and gobind are available
if ! command -v gomobile &>/dev/null; then
    echo "gomobile not found. Installing..."
    go install golang.org/x/mobile/cmd/gomobile@latest
    go install golang.org/x/mobile/cmd/gobind@latest
fi

# Initialize gomobile (downloads NDK tools for Android; for iOS it sets up Xcode paths)
gomobile init

# Clean previous build
rm -rf "$OUTPUT"

echo "Building $OUTPUT (target: $TARGET_FLAG) from ./libtailscale ..."

gomobile bind \
    -target "$TARGET_FLAG" \
    -o "$OUTPUT" \
    -iosversion 15.0 \
    -ldflags="-s -w" \
    ./libtailscale

echo ""
echo "Success: $OUTPUT"
ls -lh "$OUTPUT"
echo ""
echo "Next: add $OUTPUT to the Xcode project's PacketTunnel target (Frameworks, Libraries, and Embedded Content)."
