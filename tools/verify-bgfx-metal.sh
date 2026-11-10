#!/bin/bash
set -euo pipefail
[[ $# == 4 || $# == 5 ]] || {
    echo 'Usage: verify-bgfx-metal.sh LAUNCHER.app ORIGINAL_LAUNCHER.jar PATCHED_MCGL.jar BGFX_DEPENDENCIES [WORLD_CAPTURE]' >&2
    exit 64
}
root=$(cd "$(dirname "$0")/.." && pwd -P)
resources=$(cd "$1/Contents/Resources" && pwd -P)
dependencies=$(cd "$4" && pwd -P)
jdk="$resources/java21-arm64/Home"
fixtures="$root/tests/render/metal-bgfx-launcher"
work=$(mktemp -d /private/tmp/mcgl-bgfx-check.XXXXXX)
echo "BGFX verification files: $work"

# LWJGL 3.4.3 from Maven Central. Match the shader binaries to the native
# library's bgfx revision, 780f7bf00a29573473b90dcfab9f92a7d8687702.
(
    cd "$dependencies"
    shasum -a 256 -c <<'CHECKSUMS'
dc6f1df4d8d4310f31b89cdb073c1bfcdb83fe81ec7ec66e80f57453bdc948e5  lwjgl-bgfx-3.4.3.jar
a80c812e564bdeda6e98745c1488e6a652dd911a2da921dcea1fa29ce8b89496  lwjgl-bgfx-3.4.3-natives-macos-arm64.jar
055a6a2b2b5f5eddb6c34bd07e53ce265f679d38ee14a9a4e5540b052c78a05c  vs_cubes.bin
ab93fe3459bb335703d1ccd32491f5d74a9ced369d5be86d72c96607da2f41e5  fs_cubes.bin
CHECKSUMS
)

profile="$work/profile"
runtime="$work/Resources/MCGL BGFX Test.app"
binary="$runtime/Contents/MacOS/MCGL ARM64 Runtime"
mkdir -p "$runtime/Contents/MacOS" "$work/Resources/java21-arm64" "$profile/bin/natives" "$work/classes"
ln -s "$jdk" "$work/Resources/java21-arm64/Home"
cp "$root/arm64-runtime/Info.plist" "$runtime/Contents/Info.plist"
cp "$2" "$profile/Minecraft.jar"
cp "$3" "$profile/bin/mcgl.jar"
cp "$dependencies/"{vs_cubes,fs_cubes}.bin "$profile"
if [[ $# == 5 ]]; then
    shaders="$root/renderer/bgfx/shaders"
    (
        cd "$dependencies"
        shasum -a 256 -c <<'CHECKSUMS'
50a6284bfefc9f7ec8ae39e835d599e72650b1fd290ad541d5784b869d7662b3  shaderc
5ad79a2ff5d1b1fd1f22d986f2b0b5dc49f89ffd5ce18e57966b6ab894094508  bgfx_shader.sh
CHECKSUMS
    )
    "$dependencies/shaderc" -f "$shaders/vs_terrain.sc" -o "$profile/vs_terrain.bin" \
        --type vertex --platform osx -p metal --varyingdef "$shaders/varying.def.sc" -i "$dependencies"
    "$dependencies/shaderc" -f "$shaders/fs_terrain.sc" -o "$profile/fs_terrain.bin" \
        --type fragment --platform osx -p metal --varyingdef "$shaders/varying.def.sc" -i "$dependencies"
    export MCGL_BGFX_WORLD_CAPTURE="$(cd "$(dirname "$5")" && pwd -P)/$(basename "$5")"
fi
cp "$resources/PortSupport/bin/natives/liblwjgl.dylib" "$profile/bin/natives"
unzip -p "$dependencies/lwjgl-bgfx-3.4.3-natives-macos-arm64.jar" macos/arm64/org/lwjgl/bgfx/libbgfx.dylib \
    > "$profile/bin/natives/libbgfx.dylib"
cp "$resources/PortSupport/bin/lwjgl.jar" "$profile/bin/lwjgl.jar"
/usr/bin/zip -qd "$profile/bin/lwjgl.jar" 'org/lwjgl/opengl/*' 'org/lwjgl/glfw/*'
unzip -q "$dependencies/lwjgl-bgfx-3.4.3.jar" 'org/lwjgl/bgfx/*' -d "$work/classes"

xcrun clang -arch arm64 -mmacosx-version-min=14.0 -fobjc-arc -dynamiclib \
    -I"$jdk/include" -I"$jdk/include/darwin" -framework Cocoa -framework QuartzCore \
    "$root/renderer/bgfx/BgfxWindow.m" -o "$profile/bgfx-window.dylib"
xcrun clang -x objective-c -arch arm64 -mmacosx-version-min=14.0 -DMCGL_DIRECT_METAL \
    -I"$jdk/include" -I"$jdk/include/darwin" -framework Cocoa \
    "$root/arm64-runtime/MCGLARM64Runtime.c" -o "$binary"
otool -L "$binary" "$profile/bgfx-window.dylib" "$profile/bin/natives/"*.dylib > "$work/native-links.txt"
if grep -Ei 'OpenGL.framework|libglfw|liblwjgl_opengl' "$work/native-links.txt"; then
    echo 'Unexpected legacy graphics binding' >&2
    exit 1
fi

"$jdk/bin/javac" --release 21 -cp "$profile/bin/lwjgl.jar:$profile/Minecraft.jar:$dependencies/lwjgl-bgfx-3.4.3.jar" \
    -d "$work/classes" "$root/tests/render/OriginalChunkEmitter.java" "$fixtures/DirectLauncher.java" \
    "$root/renderer/bgfx/src/local/mcgl/render/backend/"{BgfxTerrainReplay,BgfxTerrainRenderer}.java
"$jdk/bin/jar" cf "$profile/mcgl-nativewindow-patch.jar" -C "$work/classes" .
swiftc -module-cache-path "$work/modules" "$root/tests/lwjgl3/RuntimeHarness.swift" -o "$work/harness"
MCGL_VERIFY_DIRECT_METAL=1 "$work/harness" "$binary" "$profile" "$work/runtime.log"
grep -q '^BGFX_METAL_PASS ' "$work/runtime.log"
if grep -E ' (org\.lwjgl\.(opengl|glfw)\.|local\.mcgl\.render\.backend\.(Core41Backend|CompatibilityBackend|MetalGameTarget) )' \
    "$profile/direct-metal-classes.log"; then
    echo 'Direct bgfx test loaded a legacy graphics class' >&2
    exit 1
fi
echo 'BGFX_GRAPHICS_AUDIT_PASS'
