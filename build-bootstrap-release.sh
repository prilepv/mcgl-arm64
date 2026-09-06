#!/bin/zsh
set -euo pipefail

script_dir=${0:A:h}
workspace_dir=${script_dir:h:h}
version=1.6.6
dependency_root=${MCGL_BUILD_INPUTS:-$script_dir}
output_root=${MCGL_RELEASE_OUTPUT_ROOT:-$workspace_dir/dist}
release_name="Minecraft-Galaxy-ARM64-Bootstrap-${version}"
release_dir="$output_root/$release_name"
app_dir="$release_dir/Minecraft Galaxy ARM64.app"
contents_dir="$app_dir/Contents"
resources_dir="$contents_dir/Resources"
java_source="$dependency_root/zulu8-arm64/Contents/Home"
bootstrap_build="$dependency_root/bootstrap-build"
runtime_app="$resources_dir/MCGL ARM64 Runtime.app"
runtime_executable="$runtime_app/Contents/MacOS/MCGL ARM64 Runtime"
icon_source="$script_dir/native-launcher/Assets/app-icon.png"
background_source="$script_dir/native-launcher/Assets/launcher-background.png"

icon_work=$(mktemp -d /private/tmp/mcgl-icon.XXXXXX)
lwjgl_patch_classes=""
cleanup() {
    rm -rf -- "$icon_work"
    if [[ -n "$lwjgl_patch_classes" ]]; then
        rm -rf -- "$lwjgl_patch_classes"
    fi
}
trap cleanup EXIT

if [[ -e "$release_dir" ]]; then
    print -u2 "Release directory already exists: $release_dir"
    exit 1
fi

mkdir -p "$contents_dir/MacOS" "$resources_dir/java8-arm64/Home/bin" \
    "$resources_dir/java8-arm64/Home/lib" "$runtime_app/Contents/MacOS" \
    "$runtime_app/Contents/Resources" "$output_root"

swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path /private/tmp/mcgl-swift-module-cache \
    -framework Cocoa -framework CryptoKit \
    "$script_dir/native-launcher/MCGLNativeLauncher.swift" \
    "$script_dir/native-launcher/MCGLLauncherPreferences.swift" \
    "$script_dir/native-launcher/MCGLInstaller.swift" \
    "$script_dir/native-launcher/MCGLLauncherUpdater.swift" \
    -o "$contents_dir/MacOS/MCGL ARM64 Launcher"

ditto "$script_dir/native-launcher/Info.plist" "$contents_dir/Info.plist"
ditto "$script_dir/arm64-runtime/Info.plist" "$runtime_app/Contents/Info.plist"
iconset="$icon_work/app.iconset"
mkdir -p "$iconset"
sips -z 16 16 "$icon_source" --out "$iconset/icon_16x16.png" >/dev/null
sips -z 32 32 "$icon_source" --out "$iconset/icon_16x16@2x.png" >/dev/null
sips -z 32 32 "$icon_source" --out "$iconset/icon_32x32.png" >/dev/null
sips -z 64 64 "$icon_source" --out "$iconset/icon_32x32@2x.png" >/dev/null
sips -z 128 128 "$icon_source" --out "$iconset/icon_128x128.png" >/dev/null
sips -z 256 256 "$icon_source" --out "$iconset/icon_128x128@2x.png" >/dev/null
sips -z 256 256 "$icon_source" --out "$iconset/icon_256x256.png" >/dev/null
sips -z 512 512 "$icon_source" --out "$iconset/icon_256x256@2x.png" >/dev/null
sips -z 512 512 "$icon_source" --out "$iconset/icon_512x512.png" >/dev/null
sips -z 1024 1024 "$icon_source" --out "$iconset/icon_512x512@2x.png" >/dev/null
swiftc -parse-as-library -swift-version 5 -module-cache-path "$icon_work/modules" \
    "$script_dir/tools/BuildICNS.swift" \
    -o "$icon_work/build-icns"
"$icon_work/build-icns" "$iconset" "$icon_work/app.icns"
ditto "$icon_work/app.icns" "$runtime_app/Contents/Resources/app.icns"
ditto "$icon_work/app.icns" "$resources_dir/app.icns"
ditto "$background_source" "$resources_dir/launcher-background.png"

xcrun clang -x objective-c -arch arm64 -mmacosx-version-min=14.0 \
    -I"$java_source/include" -I"$java_source/include/darwin" \
    -framework Cocoa -framework OpenGL \
    "$script_dir/arm64-runtime/MCGLARM64Runtime.c" \
    -o "$runtime_executable"

# The portable runtime accepts four arguments after argv[0].  An invalid log
# FIFO must therefore reach open_owned_fifo and return 65, not fail argc with 64.
if "$runtime_executable" test-login /private/tmp/mcgl-build-password.invalid \
    /private/tmp/mcgl-build-log.invalid /private/tmp >/dev/null 2>&1; then
    runtime_smoke_status=0
else
    runtime_smoke_status=$?
fi
if [[ $runtime_smoke_status -ne 65 ]]; then
    print -u2 "Portable runtime argument smoke test failed: $runtime_smoke_status"
    exit 1
fi
ditto "$bootstrap_build/PortSupport" "$resources_dir/PortSupport"
ditto "$bootstrap_build/PatchTools" "$resources_dir/PatchTools"

# Build the native window fix and the matching Java-side resize protocol.
"$java_source/bin/java" -cp "$dependency_root/apache-ant-1.10.15/lib/ant-launcher.jar" \
    org.apache.tools.ant.launch.Launcher \
    -f "$dependency_root/lwjgl2-modern/platform_build/macosx_ant/build.xml" \
    -Djavavmroot="$java_source" -Dsdkroot="$(xcrun --show-sdk-path)" \
    -Djdk_lib="$java_source/jre/lib" > "$release_dir/native-build.log" 2>&1
ditto "$dependency_root/lwjgl2-modern/bin/lwjgl/liblwjgl.dylib" \
    "$resources_dir/PortSupport/bin/natives/liblwjgl.dylib"

lwjgl_patch_classes=$(mktemp -d /private/tmp/mcgl-lwjgl-classes.XXXXXX)
"$java_source/bin/javac" -encoding UTF-8 -source 1.8 -target 1.8 \
    -classpath "$resources_dir/PortSupport/bin/lwjgl.jar" \
    -d "$lwjgl_patch_classes" \
    "$script_dir/third-party/lwjgl2-overlay/src/java/org/lwjgl/opengl/Display.java" \
    "$script_dir/third-party/lwjgl2-overlay/src/java/org/lwjgl/opengl/MCGLFrameLimiter.java" \
    "$script_dir/third-party/lwjgl2-overlay/src/java/org/lwjgl/opengl/MCGLFrameProfiler.java" \
    "$script_dir/third-party/lwjgl2-overlay/src/java/org/lwjgl/opengl/MacOSXDisplay.java" \
    "$script_dir/third-party/lwjgl2-overlay/src/java/org/lwjgl/opengl/MacOSXNativeMouse.java" \
    "$script_dir/third-party/lwjgl2-overlay/src/generated/org/lwjgl/opengl/GL11.java"
"$java_source/bin/jar" uf "$resources_dir/PortSupport/bin/lwjgl.jar" \
    -C "$lwjgl_patch_classes" org/lwjgl/opengl

"$java_source/bin/javac" -source 1.8 -target 1.8 \
    -d "$resources_dir/PatchTools" \
    "$script_dir/native-window-patch/ClassBytePatch.java"
"$java_source/bin/javac" -source 1.8 -target 1.8 \
    -cp "$resources_dir/PatchTools/asm-debug-all.jar:$resources_dir/PortSupport/bin/lwjgl.jar" \
    -d "$resources_dir/PatchTools" \
    "$script_dir/tools/PatchMCGLFullscreen.java" \
    "$script_dir/tools/PatchMCGLPerformance.java" \
    "$script_dir/tools/PatchMCGLLightmap.java" \
    "$script_dir/tools/PatchMCGLChunkVbo.java" \
    "$script_dir/tools/PatchMCGLQuadSort.java" \
    "$script_dir/tools/PatchMCGLTransparency.java" \
    "$script_dir/performance-patch/src/local/mcgl/perf/ChunkVbo.java" \
    "$script_dir/performance-patch/src/local/mcgl/perf/QuadSort.java" \
    "$script_dir/performance-patch/src/local/mcgl/perf/ParticleList.java" \
    "$script_dir/performance-patch/src/local/mcgl/perf/AnimationCache.java" \
    "$script_dir/performance-patch/src/local/mcgl/perf/LightmapCache.java" \
    "$script_dir/performance-patch/src/local/mcgl/perf/RenderDiagnostics.java"

# Minimal Zulu Java 8 runtime plus the two tools needed by the installer.
ditto "$java_source/jre" "$resources_dir/java8-arm64/Home/jre"
ditto "$java_source/bin/java" "$resources_dir/java8-arm64/Home/bin/java"
ditto "$java_source/bin/jar" "$resources_dir/java8-arm64/Home/bin/jar"
ditto "$java_source/lib/tools.jar" "$resources_dir/java8-arm64/Home/lib/tools.jar"
for notice in LICENSE ASSEMBLY_EXCEPTION THIRD_PARTY_README DISCLAIMER readme.txt; do
    if [[ -f "$java_source/$notice" ]]; then
        ditto "$java_source/$notice" "$resources_dir/java8-arm64/Home/$notice"
    fi
done

ditto "$script_dir/bootstrap-release/README.md" "$release_dir/README.md"
ditto "$script_dir/bootstrap-release/THIRD-PARTY-NOTICES.md" \
    "$release_dir/THIRD-PARTY-NOTICES.md"

codesign --force --deep --sign - "$app_dir"
codesign --verify --deep --strict "$app_dir"
bash "$script_dir/tools/audit-portable-release.sh" "$app_dir"

(
    cd "$output_root"
    ditto -c -k --sequesterRsrc --keepParent "$release_name" "$release_name.zip"
    shasum -a 256 "$release_name.zip" > "$release_name.zip.sha256"
)

print "$release_dir"
