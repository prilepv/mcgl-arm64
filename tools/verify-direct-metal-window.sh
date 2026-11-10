#!/bin/bash
set -euo pipefail
[[ $# == 3 ]] || {
    echo 'Usage: verify-direct-metal-window.sh BASE.app ORIGINAL_LAUNCHER.jar PATCHED_MCGL.jar' >&2; exit 64;
}
root=$(cd "$(dirname "$0")/.." && pwd -P)
resources=$(cd "$1/Contents/Resources" && pwd -P)
jdk="$resources/java21-arm64/Home"
work=$(mktemp -d /private/tmp/mcgl-metal-window-check.XXXXXX)
echo "Direct Metal verification files: $work"
profile="$work/profile"
runtime="$work/Resources/MCGL Direct Metal Runtime.app"
binary="$runtime/Contents/MacOS/MCGL ARM64 Runtime"
mkdir -p "$runtime/Contents/MacOS" "$work/Resources/java21-arm64" "$profile/bin" "$work/classes"
ln -s "$jdk" "$work/Resources/java21-arm64/Home"
cp "$root/arm64-runtime/Info.plist" "$runtime/Contents/Info.plist"
cp "$2" "$profile/Minecraft.jar"
cp "$3" "$profile/bin/mcgl.jar"
cp "$resources/PortSupport/bin/lwjgl.jar" "$profile/bin/lwjgl.jar"
/usr/bin/zip -qd "$profile/bin/lwjgl.jar" 'org/lwjgl/opengl/*' 'org/lwjgl/glfw/*'
lwjgl="$profile/bin/lwjgl.jar"

"$root/renderer/metal-preview/build-direct.sh" "$jdk" "$profile/direct-metal.dylib"
xcrun clang -x objective-c -arch arm64 -mmacosx-version-min=14.0 -DMCGL_DIRECT_METAL \
    -I"$jdk/include" -I"$jdk/include/darwin" -framework Cocoa \
    "$root/arm64-runtime/MCGLARM64Runtime.c" -o "$binary"
if otool -L "$binary" "$profile/direct-metal.dylib" | grep -Ei 'OpenGL.framework|libglfw|libmcgl-mainthread'; then
    echo 'Unexpected OpenGL/window binding in direct Metal binaries' >&2
    exit 1
fi
"$jdk/bin/javac" --release 21 -cp "$lwjgl:$profile/Minecraft.jar" -d "$work/classes" \
    "$root/renderer/src/local/mcgl/render/backend/"{MetalTerrainWindow,MetalWorldFrame}.java \
    "$root/tests/render/OriginalChunkEmitter.java" \
    "$root/tests/render/metal-direct-launcher/DirectLauncher.java"
"$jdk/bin/jar" cf "$profile/mcgl-nativewindow-patch.jar" -C "$work/classes" .
swiftc -module-cache-path "$work/modules" "$root/tests/lwjgl3/RuntimeHarness.swift" -o "$work/harness"
MCGL_VERIFY_DIRECT_METAL=1 "$work/harness" "$binary" "$profile" "$work/runtime.log"
grep -q '^DIRECT_METAL_WINDOW_PASS ' "$work/runtime.log"
[[ -s "$profile/direct-metal-classes.log" ]]
if grep -E ' (org\.lwjgl\.(opengl|glfw)\.|local\.mcgl\.render\.backend\.(Core41Backend|CompatibilityBackend|MetalTerrainPreview) )' \
    "$profile/direct-metal-classes.log"; then
    echo 'Direct Metal runtime loaded an old graphics backend' >&2
    exit 1
fi
echo 'DIRECT_METAL_GRAPHICS_AUDIT_PASS'
