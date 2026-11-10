#!/bin/bash
set -euo pipefail
[[ $# == 3 || $# == 4 ]] || {
    echo 'Usage: replay-metal-world.sh BASE.app WORLD_CAPTURE OUTPUT.png [SECONDS]' >&2; exit 64;
}
root=$(cd "$(dirname "$0")/.." && pwd -P)
resources=$(cd "$1/Contents/Resources" && pwd -P)
input="$(cd "$(dirname "$2")" && pwd -P)/$(basename "$2")"
image="$(cd "$(dirname "$3")" && pwd -P)/$(basename "$3")"
jdk="$resources/java21-arm64/Home"
work=$(mktemp -d /private/tmp/mcgl-metal-world.XXXXXX)
trap 'rm -rf -- "$work"' EXIT
profile="$work/profile"
runtime="$work/Resources/MCGL Direct Metal Runtime.app"
binary="$runtime/Contents/MacOS/MCGL ARM64 Runtime"
mkdir -p "$runtime/Contents/MacOS" "$work/Resources/java21-arm64" "$profile/bin" "$work/classes"
ln -s "$jdk" "$work/Resources/java21-arm64/Home"
cp "$root/arm64-runtime/Info.plist" "$runtime/Contents/Info.plist"
cp "$resources/PortSupport/bin/lwjgl.jar" "$profile/bin/lwjgl.jar"
/usr/bin/zip -qd "$profile/bin/lwjgl.jar" 'org/lwjgl/opengl/*' 'org/lwjgl/glfw/*'
lwjgl="$profile/bin/lwjgl.jar"

"$root/renderer/metal-preview/build-direct.sh" "$jdk" "$profile/direct-metal.dylib"
xcrun clang -x objective-c -arch arm64 -mmacosx-version-min=14.0 -DMCGL_DIRECT_METAL \
    -I"$jdk/include" -I"$jdk/include/darwin" -framework Cocoa \
    "$root/arm64-runtime/MCGLARM64Runtime.c" -o "$binary"
if otool -L "$binary" "$profile/direct-metal.dylib" | grep -Ei 'OpenGL.framework|libglfw|libmcgl-mainthread'; then
    echo 'Unexpected OpenGL binding in direct Metal binaries' >&2; exit 1
fi
"$jdk/bin/javac" --release 21 -cp "$lwjgl" -d "$work/classes" \
    "$root/renderer/src/local/mcgl/render/backend/"{MetalTerrainWindow,MetalWorldFrame,MetalWorldPlayback,MetalWorldReplay}.java \
    "$root/tests/render/metal-world-launcher/DirectLauncher.java"
"$jdk/bin/jar" cf "$profile/mcgl-nativewindow-patch.jar" -C "$work/classes" .
swiftc -module-cache-path "$work/modules" "$root/tests/lwjgl3/RuntimeHarness.swift" -o "$work/harness"
MCGL_VERIFY_DIRECT_METAL=1 MCGL_METAL_WORLD_INPUT="$input" MCGL_METAL_WORLD_IMAGE="$image" \
    MCGL_METAL_WORLD_SECONDS="${4:-6}" "$work/harness" "$binary" "$profile" "$work/runtime.log"
grep -q '^DIRECT_METAL_WORLD_PASS ' "$work/runtime.log"
if grep -E ' (org\.lwjgl\.(opengl|glfw)\.|local\.mcgl\.render\.backend\.(Core41Backend|CompatibilityBackend|MetalTerrainPreview) )' \
    "$profile/direct-metal-classes.log"; then
    echo 'Direct Metal replay loaded an old graphics backend' >&2; exit 1
fi
echo 'DIRECT_METAL_WORLD_GRAPHICS_AUDIT_PASS'
