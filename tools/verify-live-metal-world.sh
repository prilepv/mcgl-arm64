#!/bin/bash
set -euo pipefail
[[ $# == 3 || ( $# == 4 && ( $4 == --scene || $4 == --bgfx ) ) ]] || {
    echo 'Usage: verify-live-metal-world.sh PREVIEW.app ORIGINAL_LAUNCHER.jar PATCHED_MCGL.jar [--scene|--bgfx]' >&2; exit 64;
}
root=$(cd "$(dirname "$0")/.." && pwd -P)
resources=$(cd "$1/Contents/Resources" && pwd -P)
jdk="$resources/java21-arm64/Home"
runtime="$resources/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime"
metal="$resources/libmcgl-metal-world-direct.dylib"
work=$(mktemp -d /private/tmp/mcgl-metal-live-check.XXXXXX)
trap 'rm -rf -- "$work"' EXIT
profile="$work/profile"
mkdir -p "$profile/bin" "$work/launcher" "$work/tests"
cp "$2" "$profile/Minecraft.jar"
cp "$3" "$profile/bin/mcgl.jar"
cp "$resources/PortSupport/bin/lwjgl.jar" "$profile/bin/lwjgl.jar"
ln -s "$resources/PortSupport/bin/natives" "$profile/bin/natives"
if otool -L "$metal" | grep -Ei 'OpenGL.framework|libglfw|libmcgl-mainthread'; then
    echo 'Unexpected OpenGL binding in the live Metal window' >&2; exit 1
fi
xcrun clang -dynamiclib -arch arm64 -mmacosx-version-min=14.0 -Wno-deprecated-declarations \
    -I"$jdk/include" -I"$jdk/include/darwin" -framework OpenGL \
    "$root/tests/render/OffscreenCoreContext.c" -o "$profile/offscreen.dylib"
"$jdk/bin/javac" --release 21 -cp "$profile/bin/lwjgl.jar" -d "$work/tests" \
    "$root/tests/render/"{OffscreenCoreContext,OriginalChunkEmitter,MetalTerrainLiveProbe}.java
"$jdk/bin/javac" --release 21 -cp "$profile/Minecraft.jar" -d "$work/launcher" \
    "$root/tests/render/metal-live-launcher/DirectLauncher.java"
if [[ ${4:-} == --bgfx ]]; then
    # The bundled renderer lives in the parent loader. Package-private LWJGL
    # test helpers must be defined in the same loader as that renderer.
    ditto "$work/tests" "$work/launcher"
else
    "$jdk/bin/jar" uf "$profile/bin/lwjgl.jar" -C "$work/tests" .
fi
"$jdk/bin/jar" cf "$profile/mcgl-nativewindow-patch.jar" -C "$work/launcher" .
swiftc -module-cache-path "$work/modules" "$root/tests/lwjgl3/RuntimeHarness.swift" -o "$work/harness"
scene=0
minimum_draws=8
if [[ ${4:-} == --scene ]]; then
    scene=1
    minimum_draws=12
fi
if [[ ${4:-} == --bgfx ]]; then
    bgfx="$resources/Bgfx/bgfx-window.dylib"
    [[ -r "$bgfx" ]]
    printf 'Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-17\n' > "$profile/.arm64-port-version"
    MCGL_BGFX_LIVE_LIBRARY="$bgfx" "$work/harness" "$runtime" "$profile" "$work/runtime.log"
    grep -q '^BGFX_FIXTURE_OPENGL_TERRAIN_DISCARDED$' "$work/runtime.log"
    grep -q 'reasons=\[primary color cleared after terrain\]' "$work/runtime.log"
    grep -q '^\[MCGL bgfx live\] backend=Metal source=game terrain' "$work/runtime.log"
    grep -q '^\[MCGL bgfx live\] submitted=1 .*draws=8 meshUploads=8 textureUploads=3 pageUploads=1$' "$work/runtime.log"
    awk '
        /^\[MCGL bgfx live\] closed / {
            split($5, submitted, "="); split($6, transforms, "=")
            split($8, meshes, "="); split($9, textures, "="); split($10, pages, "=")
            pass = submitted[2] >= 10 && transforms[2] >= 4 && meshes[2] == 8 && textures[2] == 4 && pages[2] == 1
        }
        END { exit !pass }
    ' "$work/runtime.log"
    echo 'BGFX_LIVE_TERRAIN_PASS'
    exit 0
fi
MCGL_METAL_FULL_SCENE="$scene" MCGL_METAL_LIVE_LIBRARY="$metal" "$work/harness" "$runtime" "$profile" "$work/runtime.log"
grep -q '^\[MCGL Metal live\] restored evicted terrain geometry' "$work/runtime.log"
grep -q '^\[MCGL Metal live\] held=1 skipped=8 reasons=\[effect/raster state\]' "$work/runtime.log"
grep -q '^\[MCGL Metal live\] held=2 skipped=0 reasons=\[no primary clear\]' "$work/runtime.log"
grep -q '^\[MCGL Metal live\] held=3 skipped=0 reasons=\[no opaque terrain\]' "$work/runtime.log"
if [[ $scene == 1 ]]; then
    grep -q 'submitted=1 .*scene=2 text=1 .*menu=true' "$work/runtime.log"
    grep -q 'scene=4 text=1 depthResets=1 sceneSkipped=0 stencil=2' "$work/runtime.log"
fi
awk -v minimum_draws="$minimum_draws" '
    /^\[MCGL Metal live\] closed / {
        split($5, submitted, "="); split($6, presented, "="); split($7, transforms, "=")
        split($8, minimum, "=")
        pass = submitted[2] >= 3 && presented[2] >= 2 && transforms[2] >= 3 && minimum[2] == minimum_draws
    }
    END { exit !pass }
' "$work/runtime.log"
echo 'METAL_LIVE_WORLD_PASS'
