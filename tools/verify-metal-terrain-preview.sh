#!/bin/bash
set -euo pipefail
[[ $# == 2 || ( $# == 3 && $3 == --visible ) ]] || {
    echo 'Usage: verify-metal-terrain-preview.sh PREVIEW.app PATCHED_MCGL.jar [--visible]' >&2; exit 64;
}
root=$(cd "$(dirname "$0")/.." && pwd -P)
resources=$(cd "$1/Contents/Resources" && pwd -P)
client="$(cd "$(dirname "$2")" && pwd -P)/$(basename "$2")"
jdk="$resources/java21-arm64/Home"
lwjgl="$resources/PortSupport/bin/lwjgl.jar"
work=$(mktemp -d /private/tmp/mcgl-metal-verify.XXXXXX)
trap 'rm -rf -- "$work"' EXIT
xcrun clang -dynamiclib -arch arm64 -mmacosx-version-min=14.0 -Wno-deprecated-declarations \
    -I"$jdk/include" -I"$jdk/include/darwin" -framework OpenGL \
    "$root/tests/render/OffscreenCoreContext.c" -o "$work/opengl.dylib"
"$jdk/bin/javac" --release 21 -cp "$lwjgl" -d "$work" \
    "$root/tests/render/"{OffscreenCoreContext,OriginalChunkEmitter,MetalTerrainLiveProbe}.java
cd "$work"
run_probe() {
    local library=$1 visible=$2
    shift 2
    MCGL_METAL_TERRAIN_LIBRARY="$library" MCGL_METAL_TERRAIN_VISIBLE="$visible" \
        "$jdk/bin/java" -Djava.awt.headless=true -Dmcgl.test.offscreenLibrary="$work/opengl.dylib" \
        -Dorg.lwjgl.librarypath="$resources/PortSupport/bin/natives" \
        -Dmcgl.metal.terrain.validate=true -Dmcgl.metal.terrain.minVertices=1 \
        -Dmcgl.metal.terrain.interval=1 -Dmcgl.metal.terrain.warmup=0 \
        -cp "$work:$lwjgl:$client" local.mcgl.render.tests.MetalTerrainLiveProbe "$client" "$@"
}
metal="$resources/libmcgl-metal-terrain-preview.dylib"
if [[ $# == 3 ]]; then
    run_probe "" 0 "$work/reference.bin"
    run_probe "$metal" 1 "$work/visible.bin" | tee "$work/result"
    [[ $(awk '/^\[MCGL Metal terrain\] visibleFrames=/ { n++ } END { print n+0 }' "$work/result") == 4 ]]
    cmp "$work/reference.bin" "$work/visible.bin"
    echo METAL_VISIBLE_PARITY_PASS
else
    run_probe "$metal" 0 | tee "$work/result"
    [[ $(awk '/^\[MCGL Metal terrain\] parity / { n++ } END { print n+0 }' "$work/result") == 4 ]]
fi
