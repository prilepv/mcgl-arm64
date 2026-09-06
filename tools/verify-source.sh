#!/bin/bash
# Compile this source snapshot against explicit, locally provided dependencies.
# No downloads, credentials, changes to the app or installed game profile.
set -euo pipefail

if [[ $# != 2 && $# != 4 ]]; then
    echo 'Usage: bash tools/verify-source.sh JDK8_HOME RELEASE.app [ORIGINAL_MCGL.jar ORIGINAL_Minecraft.jar]' >&2
    exit 64
fi
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk_root=$(cd "$1" && pwd)
app_root=$(cd "$2" && pwd)
resources="$app_root/Contents/Resources"
asm="$resources/PatchTools/asm-debug-all.jar"
lwjgl="$resources/PortSupport/bin/lwjgl.jar"
for required in "$jdk_root/bin/javac" "$jdk_root/bin/java" "$jdk_root/include/jni.h" "$asm" "$lwjgl"; do
    [[ -f "$required" ]] || { echo "Missing required input: $required" >&2; exit 66; }
done
"$jdk_root/bin/java" -version
result_dir=$(mktemp -d /private/tmp/mcgl-source-check.XXXXXX)
echo "Build/test outputs (retained): $result_dir"
mkdir -p "$result_dir/classes" "$result_dir/modules"
classes="$result_dir/classes"
overlay="$source_root/third-party/lwjgl2-overlay/src"

swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework Cocoa -framework CryptoKit \
    "$source_root/native-launcher/MCGLNativeLauncher.swift" \
    "$source_root/native-launcher/MCGLLauncherPreferences.swift" \
    "$source_root/native-launcher/MCGLInstaller.swift" \
    "$source_root/native-launcher/MCGLLauncherUpdater.swift" -o "$result_dir/launcher"
swiftc -D MCGL_LAUNCHER_TEST -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework Cocoa -framework CryptoKit \
    "$source_root/native-launcher/MCGLNativeLauncher.swift" \
    "$source_root/native-launcher/MCGLLauncherPreferences.swift" \
    "$source_root/native-launcher/MCGLInstaller.swift" \
    "$source_root/native-launcher/MCGLLauncherUpdater.swift" \
    "$source_root/tests/LauncherUITest.swift" -o "$result_dir/ui-test"
"$result_dir/ui-test"
swiftc -parse-as-library -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework Cocoa \
    "$source_root/tests/ImageAssetTest.swift" -o "$result_dir/image-test"
"$result_dir/image-test" "$source_root/native-launcher/Assets/app-icon.png"
swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework CryptoKit \
    "$source_root/native-launcher/MCGLLauncherUpdater.swift" \
    "$source_root/tests/LauncherUpdaterTest.swift" -o "$result_dir/updater-test"
"$result_dir/updater-test"
swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework CryptoKit \
    "$source_root/native-launcher/MCGLLauncherUpdater.swift" \
    "$source_root/tests/LauncherUpgradeTest.swift" -o "$result_dir/upgrade-test"
"$result_dir/upgrade-test"
swiftc -parse-as-library -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" \
    "$source_root/tools/BuildICNS.swift" -o "$result_dir/build-icns"
swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" \
    "$source_root/native-launcher/MCGLLauncherPreferences.swift" \
    "$source_root/tests/LauncherPreferencesTest.swift" -o "$result_dir/preferences-test"
"$result_dir/preferences-test"
swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework CryptoKit \
    "$source_root/native-launcher/MCGLInstaller.swift" \
    "$source_root/tests/InstallerMigrationTest.swift" -o "$result_dir/installer-migration-test"
"$result_dir/installer-migration-test"
xcrun clang -x objective-c -arch arm64 -mmacosx-version-min=14.0 \
    -I"$jdk_root/include" -I"$jdk_root/include/darwin" -framework Cocoa -framework OpenGL \
    "$source_root/arm64-runtime/MCGLARM64Runtime.c" -o "$result_dir/runtime"
xcrun clang -dynamiclib -arch arm64 -mmacosx-version-min=14.0 \
    -I"$jdk_root/include" -I"$jdk_root/include/darwin" -framework Cocoa \
    "$source_root/native-window-patch/CocoaWindowBridge.m" -o "$result_dir/libmcglcocoa.dylib"
xcrun clang -dynamiclib -arch arm64 -mmacosx-version-min=14.0 \
    -I"$jdk_root/include" -I"$jdk_root/include/darwin" -framework IOKit -framework CoreFoundation \
    "$source_root/native-replacements/src/nixspecific_arm64.c" -o "$result_dir/libnixspecific64.dylib"
xcrun clang -dynamiclib -arch arm64 -mmacosx-version-min=14.0 \
    -I"$jdk_root/include" -I"$jdk_root/include/darwin" \
    "$source_root/native-replacements/src/valuelib_arm64.c" -o "$result_dir/libvaluelib64.dylib"

"$jdk_root/bin/javac" -encoding UTF-8 -source 1.8 -target 1.8 -cp "$lwjgl" -d "$classes" \
    "$overlay/java/org/lwjgl/MacOSXSysImplementation.java" \
    "$overlay/java/org/lwjgl/opengl/Display.java" \
    "$overlay/java/org/lwjgl/opengl/MacOSXDisplay.java" \
    "$overlay/java/org/lwjgl/opengl/MacOSXNativeMouse.java" \
    "$overlay/java/org/lwjgl/opengl/MCGLFrameLimiter.java" \
    "$overlay/java/org/lwjgl/opengl/MCGLFrameProfiler.java" \
    "$overlay/generated/org/lwjgl/opengl/GL11.java"
test_cp="$classes:$asm:$lwjgl"
"$jdk_root/bin/javac" -encoding UTF-8 -source 1.8 -target 1.8 -cp "$test_cp" -d "$classes" \
    "$source_root/native-window-patch/ClassBytePatch.java" \
    "$source_root/awt-patch/src/local/mcgl/CocoaWindowBridge.java" \
    "$source_root"/tools/PatchMCGL*.java \
    "$source_root"/performance-patch/src/local/mcgl/perf/*.java \
    "$source_root"/tests/*.java
run_test() { "$jdk_root/bin/java" -Djava.awt.headless=true -cp "$test_cp" "$@"; }
run_test PerformanceTest
run_test MeshIndexTest
run_test org.lwjgl.opengl.FrameLimiterTest
for profile in false true; do
    run_test -Dmcgl.graphics.profile="$profile" RenderDiagnosticsTest
    run_test -Dmcgl.graphics.profile="$profile" org.lwjgl.opengl.FrameProfilerTest
    for vbo in false true; do
        run_test -Dmcgl.graphics.profile="$profile" -Dmcgl.chunk.vbo="$vbo" VboDiagnosticsTest
    done
done
for vbo in false true; do
    for bindings in false true; do
        run_test -Dmcgl.chunk.vbo="$vbo" -Dmcgl.chunk.textureBindings="$bindings" TextureBindingRecordTest
    done
done

if [[ $# == 4 ]]; then
    [[ -f "$3" && -f "$4" ]] || { echo 'Original client JAR input missing.' >&2; exit 66; }
    "$jdk_root/bin/javac" -encoding UTF-8 -source 1.8 -target 1.8 -cp "$test_cp:$4" -d "$classes" \
        "$source_root/awt-patch/src/local/mcgl/DirectLauncher.java"
    run_test QuadSortTest "$3"
    run_test ChunkVboPatchTest "$3"
    run_test TransparencyPatchTest "$3"
    run_test LightmapPatchTest "$3"
    run_test AnimationPipelineTest "$3"
    for profile in false true; do
        run_test -Dmcgl.graphics.profile="$profile" ChunkTimingTest "$3"
    done
    run_test PatchMCGLPerformance "$3" "$result_dir/patched-mcgl.jar"
    run_test LightmapPipelineTest "$3" "$result_dir/patched-mcgl.jar"
    run_test -Dmcgl.lightmap.cache=false LightmapPipelineTest "$3" "$result_dir/patched-mcgl.jar"
else
    echo 'SKIPPED: DirectLauncher compilation and original-client bytecode tests (no original JARs supplied).'
fi
echo 'SOURCE_CHECK_PASS (compilation and headless tests; not a full rebuild or GPU/window test)'
