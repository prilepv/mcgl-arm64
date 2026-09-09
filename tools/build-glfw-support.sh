#!/bin/bash
set -euo pipefail
if [[ $# != 5 ]]; then
    echo "usage: $0 PREPARED_LWJGL2_SOURCE JAVA21_HOME APP_RESOURCES DEPENDENCIES ORIGINAL_LAUNCHER.jar" >&2
    exit 64
fi
source_root=$(cd "$(dirname "$0")/.." && pwd)
legacy_source=$1
jdk_root=$2
resources=$3
dependencies=$(cd "$4" && pwd)
original_launcher=$5
build_root=$(mktemp -d /private/tmp/mcgl-glfw-build.XXXXXX)
echo "GLFW build outputs (retained): $build_root"
(
    cd "$dependencies"
    shasum -a 256 -c "$source_root/third-party/lwjgl3-sha256.txt"
    shasum -a 256 -c "$source_root/third-party/glfw-sha256.txt"
)
patch_tools="$resources/PatchTools"
asm="$patch_tools/asm-debug-all.jar"
port="$resources/PortSupport"
mkdir -p "$build_root/classes" "$build_root/compat" "$build_root/bootstrap"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$asm" -d "$patch_tools" \
    "$source_root/tools/PatchMCGLLwjgl3.java" "$source_root/tests/lwjgl3/LWJGL3LinkageAudit.java" \
    "$source_root/tools/RenderCommandSpec.java" "$source_root/tools/PatchMCGLRenderer.java" \
    "$source_root/tools/PatchMCGLChunks.java" "$source_root/tools/PatchMCGLGame.java"
ditto "$source_root/renderer/legacy-commands.txt" "$patch_tools/render-commands.txt"
"$jdk_root/bin/javac" --release 8 -d "$build_root/classes" \
    "$source_root/tools/MergeLwjgl3Jar.java" "$source_root/tools/SelectGlfwScaffold.java"
"$jdk_root/bin/java" -cp "$patch_tools:$asm" PatchMCGLLwjgl3 \
    "$port/bin/lwjgl.jar" "$build_root/window.jar" "$legacy_source"
"$jdk_root/bin/java" -cp "$build_root/classes" SelectGlfwScaffold "$build_root/window.jar" "$build_root/scaffold.jar"
bindings="$dependencies/lwjgl-3.4.3.jar:$dependencies/lwjgl-opengl-3.4.3.jar:$dependencies/lwjgl-openal-3.4.3.jar:$dependencies/lwjgl-glfw-3.4.3.jar"
bash "$source_root/tools/build-render-support.sh" "$jdk_root" "$bindings" "$asm" "$build_root/renderer"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$bindings:$build_root/scaffold.jar:$build_root/renderer/renderer.jar" \
    -d "$build_root/compat" "$source_root"/lwjgl3-compat/src/org/lwjgl/*.java \
    "$source_root"/lwjgl3-compat/src/org/lwjgl/opengl/*.java \
    "$source_root"/lwjgl3-compat/src/org/lwjgl/openal/*.java \
    "$source_root"/platform/src/local/mcgl/platform/*.java \
    "$source_root"/platform/src/local/mcgl/platform/macos/*.java \
    "$source_root"/platform/src/local/mcgl/platform/glfw/*.java \
    "$source_root"/glfw-compat/src/org/lwjgl/*.java \
    "$source_root"/glfw-compat/src/org/lwjgl/opengl/*.java \
    "$source_root/third-party/lwjgl2-overlay/src/java/org/lwjgl/opengl/MCGLFrameLimiter.java" \
    "$source_root/third-party/lwjgl2-overlay/src/java/org/lwjgl/opengl/MCGLFrameProfiler.java"
"$jdk_root/bin/jar" cf "$build_root/compat.jar" -C "$build_root/compat" .
"$jdk_root/bin/java" -Dmcgl.window.backend=GLFW -cp "$build_root/classes" MergeLwjgl3Jar "$build_root/lwjgl.jar" \
    "$dependencies/lwjgl-3.4.3.jar" "$dependencies/lwjgl-opengl-3.4.3.jar" \
    "$dependencies/lwjgl-openal-3.4.3.jar" "$dependencies/lwjgl-glfw-3.4.3.jar" \
    "$build_root/scaffold.jar" "$build_root/compat.jar" "$build_root/renderer/renderer.jar"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$original_launcher" \
    -d "$build_root/bootstrap" "$source_root/awt-patch/src/local/mcgl/DirectLauncher.java"
"$jdk_root/bin/jar" cf "$build_root/bootstrap.jar" -C "$build_root/bootstrap" .
xcrun clang -x objective-c -arch arm64 -mmacosx-version-min=14.0 -dynamiclib \
    -I"$jdk_root/include" -I"$jdk_root/include/darwin" -framework Cocoa -framework OpenGL \
    -install_name @rpath/libmcgl-mainthread.dylib "$source_root/platform/native/macos/MainThread.m" \
    -o "$build_root/libmcgl-mainthread.dylib"
bash "$source_root/tools/build-glfw-native.sh" "$dependencies" "$build_root/libglfw.dylib"
for module in lwjgl lwjgl-opengl; do
    unzip -q "$dependencies/$module-3.4.3-natives-macos-arm64.jar" -d "$build_root/$module-native"
done
ditto "$build_root/lwjgl.jar" "$port/bin/lwjgl.jar"
ditto "$build_root/bootstrap.jar" "$port/mcgl-nativewindow-patch.jar"
ditto "$build_root/libmcgl-mainthread.dylib" "$port/bin/natives/libmcgl-mainthread.dylib"
ditto "$build_root/lwjgl-native/macos/arm64/org/lwjgl/liblwjgl.dylib" "$port/bin/natives/liblwjgl.dylib"
ditto "$build_root/lwjgl-opengl-native/macos/arm64/org/lwjgl/opengl/liblwjgl_opengl.dylib" "$port/bin/natives/liblwjgl_opengl.dylib"
ditto "$build_root/libglfw.dylib" "$port/bin/natives/libglfw.dylib"
# Only obsolete files in this fresh build's PortSupport are removed, never installed profiles.
for obsolete in libmcgl-window.dylib libmcglcocoa.dylib; do
    if [[ -f "$port/bin/natives/$obsolete" ]]; then rm "$port/bin/natives/$obsolete"; fi
done
if [[ -f "$port/bin/lib/libmcglcocoa.dylib" ]]; then rm "$port/bin/lib/libmcglcocoa.dylib"; fi
mkdir -p "$resources/Licenses"
ditto "$source_root/third-party/LWJGL3-LICENSE.txt" "$resources/Licenses/LWJGL3-LICENSE.txt"
ditto "$source_root/third-party/LWJGL-LICENSE.txt" "$resources/Licenses/LWJGL2-COMPAT-LICENSE.txt"
ditto "$source_root/third-party/GLFW-LICENSE.txt" "$resources/Licenses/GLFW-LICENSE.txt"
ditto "$source_root/third-party/GLFW-BUILD.md" "$resources/Licenses/GLFW-BUILD.md"
"$jdk_root/bin/java" -cp "$port/bin/lwjgl.jar" org.lwjgl.Version
echo 'GLFW_SUPPORT_PASS: GLFW platform, connected Core game, effects and chunk renderer'
