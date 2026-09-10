#!/bin/bash
# Compile this source snapshot against explicit, locally provided dependencies.
# No downloads, credentials, changes to the app or installed game profile.
set -euo pipefail

if [[ $# != 2 && $# != 4 ]]; then
    echo 'Usage: bash tools/verify-source.sh JDK_HOME RELEASE.app [ORIGINAL_MCGL.jar ORIGINAL_Minecraft.jar]' >&2
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
java_level=$("$jdk_root/bin/java" -XshowSettings:properties -version 2>&1 | awk '/java.specification.version =/{print $3}')
compiler_options=(-source 1.8 -target 1.8)
runtime_options=(-Djava.awt.headless=true)
if [[ "$java_level" == 21 ]]; then
    compiler_options=(--release 8)
    runtime_options+=('--add-opens=java.desktop/java.awt=ALL-UNNAMED'
        --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED
        --add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED)
elif [[ "$java_level" != 1.8 ]]; then
    echo 'This milestone verifies only JDK 8 baseline or JDK 21.' >&2
    exit 65
fi
result_dir=$(mktemp -d /private/tmp/mcgl-source-check.XXXXXX)
echo "Build/test outputs (retained): $result_dir"
mkdir -p "$result_dir/classes" "$result_dir/modules"
classes="$result_dir/classes"
overlay="$source_root/third-party/lwjgl2-overlay/src"

swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework Cocoa -framework CryptoKit \
    "$source_root/native-launcher/MCGLNativeLauncher.swift" \
    "$source_root/native-launcher/MCGLLauncherPreferences.swift" \
    "$source_root/native-launcher/MCGLAccounts.swift" \
    "$source_root/native-launcher/MCGLPasswordStore.swift" \
    "$source_root/native-launcher/MCGLAccountCard.swift" \
    "$source_root/native-launcher/MCGLAccountsDocumentView.swift" \
    "$source_root/native-launcher/MCGLInstaller.swift" \
    "$source_root/native-launcher/MCGLLauncherUpdater.swift" \
    "$source_root/native-launcher/MCGLChangelog.swift" -o "$result_dir/launcher"
swiftc -D MCGL_LAUNCHER_TEST -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework Cocoa -framework CryptoKit \
    "$source_root/native-launcher/MCGLNativeLauncher.swift" \
    "$source_root/native-launcher/MCGLLauncherPreferences.swift" \
    "$source_root/native-launcher/MCGLAccounts.swift" \
    "$source_root/native-launcher/MCGLPasswordStore.swift" \
    "$source_root/native-launcher/MCGLAccountCard.swift" \
    "$source_root/native-launcher/MCGLAccountsDocumentView.swift" \
    "$source_root/native-launcher/MCGLInstaller.swift" \
    "$source_root/native-launcher/MCGLLauncherUpdater.swift" \
    "$source_root/native-launcher/MCGLChangelog.swift" \
    "$source_root/tests/LauncherUITest.swift" -o "$result_dir/ui-test"
"$result_dir/ui-test" "$source_root/native-launcher/Assets"
swiftc -D MCGL_LAUNCHER_TEST -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework Cocoa -framework CryptoKit \
    "$source_root/native-launcher/MCGLNativeLauncher.swift" \
    "$source_root/native-launcher/MCGLLauncherPreferences.swift" \
    "$source_root/native-launcher/MCGLAccounts.swift" \
    "$source_root/native-launcher/MCGLPasswordStore.swift" \
    "$source_root/native-launcher/MCGLAccountCard.swift" \
    "$source_root/native-launcher/MCGLAccountsDocumentView.swift" \
    "$source_root/native-launcher/MCGLInstaller.swift" \
    "$source_root/native-launcher/MCGLLauncherUpdater.swift" \
    "$source_root/native-launcher/MCGLChangelog.swift" \
    "$source_root/tests/AccountsLayoutTest.swift" -o "$result_dir/accounts-layout-test"
"$result_dir/accounts-layout-test" "$source_root/native-launcher/Assets"
swiftc -parse-as-library -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework Cocoa \
    "$source_root/tests/ImageAssetTest.swift" -o "$result_dir/image-test"
"$result_dir/image-test" "$source_root/native-launcher/Assets/app-icon-symbol.png"
"$result_dir/image-test" "$source_root/native-launcher/Assets/app-icon.png" --white-tile
"$result_dir/image-test" "$source_root/docs/forum-icon-1.6.6-macos.png" --white-tile
swiftc -parse-as-library -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework Cocoa \
    "$source_root/tools/ComposeAppIcon.swift" -o "$result_dir/compose-icon"
for icon_size in 16 32 64 128 220 256 512 1024; do
    "$result_dir/compose-icon" "$source_root/native-launcher/Assets/app-icon-symbol.png" \
        "$result_dir/icon-$icon_size.png" "$icon_size"
    "$result_dir/image-test" "$result_dir/icon-$icon_size.png" --white-tile
done
cmp "$result_dir/icon-1024.png" "$source_root/native-launcher/Assets/app-icon.png"
cmp "$result_dir/icon-220.png" "$source_root/docs/forum-icon-1.6.6-macos.png"
swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework CryptoKit \
    "$source_root/native-launcher/MCGLLauncherUpdater.swift" \
    "$source_root/tests/LauncherUpdaterTest.swift" -o "$result_dir/updater-test"
"$result_dir/updater-test"
swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework CryptoKit \
    "$source_root/native-launcher/MCGLLauncherUpdater.swift" \
    "$source_root/native-launcher/MCGLLauncherPreferences.swift" \
    "$source_root/native-launcher/MCGLChangelog.swift" \
    "$source_root/tests/ChangelogTest.swift" -o "$result_dir/changelog-test"
"$result_dir/changelog-test"
swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework CryptoKit \
    "$source_root/native-launcher/MCGLLauncherUpdater.swift" \
    "$source_root/tests/LauncherUpgradeTest.swift" -o "$result_dir/upgrade-test"
"$result_dir/upgrade-test"
swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework CryptoKit \
    "$source_root/native-launcher/MCGLLauncherUpdater.swift" \
    "$source_root/tests/LauncherAccountsUpgradeTest.swift" -o "$result_dir/accounts-upgrade-test"
"$result_dir/accounts-upgrade-test"
swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework CryptoKit \
    "$source_root/native-launcher/MCGLLauncherUpdater.swift" \
    "$source_root/tests/Launcher170UpgradeTest.swift" -o "$result_dir/release-170-upgrade-test"
"$result_dir/release-170-upgrade-test"
swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework CryptoKit \
    "$source_root/native-launcher/MCGLLauncherUpdater.swift" \
    "$source_root/tests/Launcher171UpgradeTest.swift" -o "$result_dir/release-171-upgrade-test"
"$result_dir/release-171-upgrade-test"
swiftc -parse-as-library -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" \
    "$source_root/tools/BuildICNS.swift" -o "$result_dir/build-icns"
swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" \
    "$source_root/native-launcher/MCGLLauncherPreferences.swift" \
    "$source_root/tests/LauncherPreferencesTest.swift" -o "$result_dir/preferences-test"
"$result_dir/preferences-test"
swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" \
    "$source_root/native-launcher/MCGLLauncherPreferences.swift" \
    "$source_root/native-launcher/MCGLAccounts.swift" \
    "$source_root/tests/AccountsTest.swift" -o "$result_dir/accounts-test"
"$result_dir/accounts-test"
swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result_dir/modules" -framework CryptoKit \
    "$source_root/native-launcher/MCGLLauncherPreferences.swift" \
    "$source_root/native-launcher/MCGLAccounts.swift" \
    "$source_root/native-launcher/MCGLPasswordStore.swift" \
    "$source_root/tests/PasswordStoreTest.swift" -o "$result_dir/password-store-test"
"$result_dir/password-store-test"
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

"$jdk_root/bin/javac" -encoding UTF-8 "${compiler_options[@]}" -cp "$lwjgl" -d "$classes" \
    "$overlay/java/org/lwjgl/MacOSXSysImplementation.java" \
    "$overlay/java/org/lwjgl/opengl/Display.java" \
    "$overlay/java/org/lwjgl/opengl/MacOSXDisplay.java" \
    "$overlay/java/org/lwjgl/opengl/MacOSXNativeMouse.java" \
    "$overlay/java/org/lwjgl/opengl/MCGLFrameLimiter.java" \
    "$overlay/java/org/lwjgl/opengl/MCGLFrameProfiler.java" \
    "$overlay/generated/org/lwjgl/opengl/GL11.java"
test_cp="$classes:$asm:$lwjgl"
"$jdk_root/bin/javac" -encoding UTF-8 "${compiler_options[@]}" -cp "$test_cp" -d "$classes" \
    "$source_root/native-window-patch/ClassBytePatch.java" \
    "$source_root/awt-patch/src/local/mcgl/CocoaWindowBridge.java" \
    "$source_root"/tools/PatchMCGL*.java \
    "$source_root/tools/RenderCommandSpec.java" "$source_root/tools/GenerateRenderBridge.java" \
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
    "$jdk_root/bin/javac" -encoding UTF-8 "${compiler_options[@]}" -cp "$test_cp:$4" -d "$classes" \
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
    lwjgl_util="$(dirname "$3")/lwjgl_util.jar"
    [[ -f "$lwjgl_util" ]] || { echo 'Original lwjgl_util.jar must accompany original mcgl.jar for compatibility checks.' >&2; exit 66; }
    compatibility_classes="$result_dir/compatibility-classes"
    mkdir -p "$compatibility_classes"
    # The release JAR seals org.lwjgl: do not mix loose overlay classes with it.
    compatibility_cp="$compatibility_classes:$lwjgl:$lwjgl_util"
    "$jdk_root/bin/javac" -encoding UTF-8 "${compiler_options[@]}" -cp "$compatibility_cp" \
        -d "$compatibility_classes" "$source_root/tests/java21/JavaRuntimeCompatibilityTest.java"
    for collector in UseG1GC UseParallelGC; do
        "$jdk_root/bin/java" "${runtime_options[@]}" -XX:+"$collector" \
            -Dorg.lwjgl.librarypath="$resources/PortSupport/bin/natives" \
            -cp "$compatibility_cp" JavaRuntimeCompatibilityTest
    done
else
    echo 'SKIPPED: DirectLauncher compilation and original-client bytecode tests (no original JARs supplied).'
fi
echo 'SOURCE_CHECK_PASS (compilation and headless tests; not a full rebuild or GPU/window test)'
