#!/bin/bash
set -euo pipefail
if [[ $# != 4 ]]; then
    echo "usage: $0 PREPARED_LWJGL2_SOURCE JAVA21_HOME APP_RESOURCES LWJGL3_DEPENDENCIES" >&2
    exit 64
fi
source_root=$(cd "$(dirname "$0")/.." && pwd)
legacy_source=$1
jdk_root=$2
resources=$3
dependencies=$(cd "$4" && pwd)
build_root=$(mktemp -d /private/tmp/mcgl-lwjgl3-build.XXXXXX)
echo "LWJGL3 build outputs (retained): $build_root"
(
    cd "$dependencies"
    shasum -a 256 -c "$source_root/third-party/lwjgl3-sha256.txt"
)
patch_tools="$resources/PatchTools"
asm="$patch_tools/asm-debug-all.jar"
port_bin="$resources/PortSupport/bin"
mkdir -p "$build_root/classes" "$build_root/compat"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$asm" -d "$patch_tools" \
    "$source_root/tools/PatchMCGLLwjgl3.java" \
    "$source_root/tests/lwjgl3/LWJGL3LinkageAudit.java"
"$jdk_root/bin/javac" --release 8 -d "$build_root/classes" "$source_root/tools/MergeLwjgl3Jar.java"
"$jdk_root/bin/java" -cp "$patch_tools:$asm" PatchMCGLLwjgl3 \
    "$port_bin/lwjgl.jar" "$build_root/window.jar" "$legacy_source"
bindings="$dependencies/lwjgl-3.4.3.jar:$dependencies/lwjgl-opengl-3.4.3.jar:$dependencies/lwjgl-openal-3.4.3.jar"
bash "$source_root/tools/build-render-support.sh" "$jdk_root" "$bindings" "$asm" "$build_root/renderer"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$bindings:$build_root/window.jar:$build_root/renderer/renderer.jar" \
    -d "$build_root/compat" "$source_root"/lwjgl3-compat/src/org/lwjgl/*.java \
    "$source_root"/lwjgl3-compat/src/org/lwjgl/opengl/*.java \
    "$source_root"/lwjgl3-compat/src/org/lwjgl/openal/*.java \
    "$source_root/platform/src/local/mcgl/platform/NativeLibraries.java"
"$jdk_root/bin/jar" cf "$build_root/compat.jar" -C "$build_root/compat" .
"$jdk_root/bin/java" -cp "$build_root/classes" MergeLwjgl3Jar "$build_root/lwjgl.jar" \
    "$dependencies/lwjgl-3.4.3.jar" "$dependencies/lwjgl-opengl-3.4.3.jar" \
    "$dependencies/lwjgl-openal-3.4.3.jar" "$build_root/window.jar" "$build_root/compat.jar" "$build_root/renderer/renderer.jar"
bash "$source_root/tools/build-lwjgl3-window-native.sh" "$legacy_source" "$jdk_root" "$build_root/native"
unzip -q "$dependencies/lwjgl-3.4.3-natives-macos-arm64.jar" -d "$build_root/core-native"
unzip -q "$dependencies/lwjgl-opengl-3.4.3-natives-macos-arm64.jar" -d "$build_root/gl-native"
ditto "$build_root/lwjgl.jar" "$port_bin/lwjgl.jar"
ditto "$build_root/native/libmcgl-window.dylib" "$port_bin/natives/libmcgl-window.dylib"
ditto "$build_root/core-native/macos/arm64/org/lwjgl/liblwjgl.dylib" "$port_bin/natives/liblwjgl.dylib"
ditto "$build_root/gl-native/macos/arm64/org/lwjgl/opengl/liblwjgl_opengl.dylib" "$port_bin/natives/liblwjgl_opengl.dylib"
mkdir -p "$resources/Licenses"
ditto "$source_root/third-party/LWJGL3-LICENSE.txt" "$resources/Licenses/LWJGL3-LICENSE.txt"
ditto "$source_root/third-party/LWJGL-LICENSE.txt" "$resources/Licenses/LWJGL2-WINDOW-LICENSE.txt"
"$jdk_root/bin/java" -cp "$port_bin/lwjgl.jar" org.lwjgl.Version
echo 'LWJGL3_SUPPORT_PASS: Java bindings 3.4.3; legacy Cocoa window/input; OpenAL Soft unchanged'
