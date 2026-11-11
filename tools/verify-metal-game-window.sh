#!/bin/bash
set -euo pipefail
[[ $# == 1 || $# == 2 || ( $# == 3 && ( $3 == --bgfx || $3 == --independent ) ) ]] || {
    echo 'Usage: verify-metal-game-window.sh LAUNCHER.app [FPS_LIMIT [--bgfx|--independent]]' >&2; exit 64;
}
root=$(cd "$(dirname "$0")/.." && pwd -P)
resources=$(cd "$1/Contents/Resources" && pwd -P)
unexpected_link=$(find "$resources/PortSupport" -type l -print -quit)
if [[ -n "$unexpected_link" ]]; then
    printf 'ARM64 components contain a symbolic link: %s\n' "$unexpected_link" >&2
    exit 1
fi
jdk="$resources/java21-arm64/Home"
work=$(mktemp -d /private/tmp/mcgl-metal-game-check.XXXXXX)
trap 'rm -rf -- "$work"' EXIT
profile="$work/profile"
mkdir -p "$profile/bin" "$work/classes"
ln -s "$resources/PortSupport/bin/natives" "$profile/bin/natives"
printf 'Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-17\n' > "$profile/.arm64-port-version"
"$jdk/bin/javac" --release 21 -cp "$resources/PortSupport/bin/lwjgl.jar" -d "$work/classes" \
    "$root/tests/render/metal-game-launcher/"{DirectLauncher,MetalTextureUploadProbe,MetalSceneModelProbe,BgfxTurnStress}.java
"$jdk/bin/jar" cf "$profile/mcgl-nativewindow-patch.jar" -C "$work/classes" .
swiftc -module-cache-path "$work/modules" "$root/tests/lwjgl3/RuntimeHarness.swift" -o "$work/harness"
if [[ ${3:-} == --bgfx || ${3:-} == --independent ]]; then
    export MCGL_BGFX_LIVE_LIBRARY="$resources/Bgfx/bgfx-window.dylib"
fi
if [[ ${3:-} == --independent ]]; then
    export MCGL_METAL_INDEPENDENT=1
    export JAVA_TOOL_OPTIONS="-Xlog:class+init=info:file=$work/classes.log"
fi
MCGL_FPS_LIMIT="${2:-120}" MCGL_RENDERER=metal MCGL_METAL_FULL_SCENE=1 \
    MCGL_METAL_LIVE_LIBRARY="$resources/libmcgl-metal-world-direct.dylib" \
    "$work/harness" "$resources/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" "$profile" "$work/runtime.log"
grep -q '^METAL_GAME_WINDOW_PASS' "$work/runtime.log"
if [[ ${3:-} == --bgfx || ${3:-} == --independent ]]; then
    grep -q 'backend=Metal source=game scene window=attached' "$work/runtime.log"
    grep -Eq 'menu=true .*text=[1-9]' "$work/runtime.log"
    grep -Eq 'menu=false .*text=[1-9].*depthResets=[1-9].*stencil=[1-9]' "$work/runtime.log"
    grep -Eq 'menu=false .*size=480x320' "$work/runtime.log"
    grep -Eq 'menu=false .*sceneSkipped=0 regions=[1-9]' "$work/runtime.log"
    grep -Fq 'reasons=[primary color cleared after terrain' "$work/runtime.log"
    grep -Eq '^\[MCGL bgfx live\] closed .*menus=[1-9][0-9]* worlds=[1-9][0-9]*' "$work/runtime.log"
    if grep -Eq 'fallback|unavailable|OpenGL 2.1 preflight' "$work/runtime.log"; then exit 1; fi
    if [[ ${3:-} == --independent ]]; then
        grep -q '^METAL_SCENE_MODEL_RECOVERY_PASS' "$work/runtime.log"
        if rg -q 'held=.*mesh unavailable' "$work/runtime.log"; then exit 1; fi
        grep -q 'framebuffer=metal resized=480x320' "$work/runtime.log"
        grep -q 'game window has no OpenGL context' "$work/runtime.log"
        grep -q 'using bundled renderer for independent bgfx Metal' "$work/runtime.log"
        grep -Eq 'renderTargets=1 retainedUploads=2 menu=false .*worldTarget=true' "$work/runtime.log"
        grep -Eq 'closed .*renderTargets=1 retainedUploads=2' "$work/runtime.log"
        test -s "$work/classes.log"
        if grep -Eq "Initializing 'org/lwjgl/opengl/(GL[0-9]*C?|CGL|GLX|WGL)'" "$work/classes.log"; then
            echo 'OpenGL native bindings initialized during independent bgfx test' >&2
            exit 1
        fi
        MCGL_TEST_BGFX_TURN="$work" MCGL_RENDERER=metal MCGL_METAL_FULL_SCENE=1 \
            MCGL_METAL_LIVE_LIBRARY="$resources/libmcgl-metal-world-direct.dylib" \
            "$work/harness" "$resources/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" "$profile" "$work/turn.log"
        grep -q '^BGFX_TURN_STRESS_PASS' "$work/turn.log"
        echo 'BGFX_INDEPENDENT_GAME_PRESENTATION_PASS'
    else
        echo 'BGFX_GAME_PRESENTATION_PASS'
    fi
    exit 0
fi
grep -q 'attached CAMetalLayer to the game window' "$work/runtime.log"
grep -Eq 'uploadLevels=2 readbackLevels=0' "$work/runtime.log"
grep -Eq 'uploadLevels=2 readbackLevels=1' "$work/runtime.log"
grep -Eq '^\[MCGL Metal live\] closed .*presented=[1-9][0-9]' "$work/runtime.log"
if grep -q 'OpenGL 2.1 preflight' "$work/runtime.log"; then exit 1; fi
echo 'METAL_GAME_PRESENTATION_PASS'
