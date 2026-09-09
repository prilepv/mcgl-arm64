#!/bin/bash
# Account-free deterministic state checks plus real AppKit Spaces/Core 4.1.
set -euo pipefail
[[ $# == 3 || $# == 4 ]] || { echo "usage: $0 JAVA21_HOME CANDIDATE.app ORIGINAL_MCGL.jar [--live]" >&2; exit 64; }
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk_root=$1
resources="$2/Contents/Resources"
original=$3
asm="$resources/PatchTools/asm-debug-all.jar"
modern="$resources/PortSupport/bin/lwjgl.jar"
result_dir=$(mktemp -d /private/tmp/mcgl-native-fullscreen-check.XXXXXX)
echo "Native fullscreen outputs (retained): $result_dir"
mkdir -p "$result_dir/classes" "$result_dir/bootstrap" "$result_dir/tools"
xcrun clang -x objective-c -arch arm64 -mmacosx-version-min=14.0 \
    -I"$jdk_root/include" -I"$jdk_root/include/darwin" -framework Cocoa -framework OpenGL \
    "$source_root/tests/glfw/NativeFullscreenStateTest.m" -o "$result_dir/state-test"
"$result_dir/state-test"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$asm" -d "$result_dir/tools" \
    "$source_root/tests/glfw/BuildWindowResizeFixture.java"
"$jdk_root/bin/java" -cp "$resources/PatchTools:$asm" PatchMCGLPerformance "$original" "$result_dir/client.jar"
"$jdk_root/bin/java" -cp "$result_dir/tools:$asm" BuildWindowResizeFixture "$result_dir/client.jar" "$result_dir/classes" --require-hook
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$modern:$result_dir/classes" -d "$result_dir/classes" \
    "$source_root/tests/glfw/NativeFullscreenProbe.java"
"$jdk_root/bin/javac" --release 8 -d "$result_dir/bootstrap" \
    "$source_root/tests/glfw/native-fullscreen-launcher/DirectLauncher.java"
"$jdk_root/bin/jar" cf "$result_dir/window-test.jar" -C "$result_dir/classes" .
"$jdk_root/bin/jar" cf "$result_dir/bootstrap.jar" -C "$result_dir/bootstrap" .
profile="$result_dir/profile"
mkdir -p "$profile/bin"
ditto "$resources/PortSupport/bin/natives" "$profile/bin/natives"
ditto "$modern" "$profile/bin/lwjgl.jar"
ditto "$result_dir/window-test.jar" "$profile/bin/window-test.jar"
ditto "$result_dir/bootstrap.jar" "$profile/mcgl-nativewindow-patch.jar"
xcrun clang -x objective-c -arch arm64 -mmacosx-version-min=14.0 -dynamiclib \
    -I"$jdk_root/include" -I"$jdk_root/include/darwin" -framework Cocoa \
    "$source_root/tests/glfw/NativeFullscreenProbe.m" -o "$profile/bin/natives/libmcgl-fullscreen-test.dylib"
module_cache=${MCGL_TEST_SWIFT_MODULE_CACHE:-$result_dir/modules}
swiftc -module-cache-path "$module_cache" "$source_root/tests/lwjgl3/RuntimeHarness.swift" -o "$result_dir/runtime-harness"
if [[ $# == 4 ]]; then
    [[ "$4" == --live ]] || exit 64
    "$result_dir/runtime-harness" "$resources/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" \
        "$profile" "$result_dir/window.log"
    grep -q 'NATIVE_FULLSCREEN_GPU_PASS' "$result_dir/window.log"
    if grep -Eq '\[LWJGL\] \[ERROR\]|GLFW error|\[MCGL Fullscreen\] AppKit transition failed' "$result_dir/window.log"; then
        echo 'Native fullscreen/window error'; exit 1
    fi
fi
echo 'NATIVE_FULLSCREEN_CHECK_PASS'
