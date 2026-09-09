#!/bin/bash
# In-progress stage 10 component check, isolated from installed profiles and account data.
set -euo pipefail
[[ $# == 3 || $# == 4 ]] || { echo 'usage: JAVA21_HOME FOUNDATION.app ORIGINAL_SHADER_DIRECTORY [--live]' >&2; exit 64; }
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk_root=$1
resources="$2/Contents/Resources"
library="$resources/PortSupport/bin/lwjgl.jar"
result_dir=$(mktemp -d /private/tmp/mcgl-game-inputs-check.XXXXXX)
echo "Game input test outputs (retained): $result_dir"
mkdir -p "$result_dir/classes" "$result_dir/bootstrap"
bash "$source_root/tools/build-render-support.sh" "$jdk_root" "$library" "$resources/PatchTools/asm-debug-all.jar" "$result_dir/renderer"
ditto "$result_dir/renderer/classes" "$result_dir/classes"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$result_dir/classes:$library" -d "$result_dir/classes" \
    "$source_root/lwjgl3-compat/src/org/lwjgl/opengl/ContextCapabilities.java" \
    "$source_root/tests/render/GameInputsTest.java" "$source_root/tests/render/GameInputsProbe.java"
ditto "$source_root/renderer/resources" "$result_dir/classes"
"$jdk_root/bin/java" -Djava.awt.headless=true -cp "$result_dir/classes:$library" local.mcgl.render.GameInputsTest "$3"
if [[ $# == 4 ]]; then
    [[ "$4" == --live ]] || exit 64
    "$jdk_root/bin/javac" --release 8 -d "$result_dir/bootstrap" "$source_root/tests/render/game-inputs-launcher/DirectLauncher.java"
    "$jdk_root/bin/jar" cf "$result_dir/game-inputs.jar" -C "$result_dir/classes" .
    "$jdk_root/bin/jar" cf "$result_dir/bootstrap.jar" -C "$result_dir/bootstrap" .
    swiftc -module-cache-path "${MCGL_TEST_SWIFT_MODULE_CACHE:-$result_dir/modules}" "$source_root/tests/lwjgl3/RuntimeHarness.swift" -o "$result_dir/runtime-harness"
    profile="$result_dir/profile"
    mkdir -p "$profile/bin"
    ditto "$resources/PortSupport/bin/natives" "$profile/bin/natives"
    ditto "$library" "$profile/bin/lwjgl.jar"
    ditto "$3" "$profile/bin/shader"
    ditto "$result_dir/game-inputs.jar" "$profile/bin/game-inputs.jar"
    ditto "$result_dir/bootstrap.jar" "$profile/mcgl-nativewindow-patch.jar"
    "$result_dir/runtime-harness" "$resources/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" \
        "$profile" "$result_dir/game-inputs-gpu.log"
    grep -q '^GAME_INPUTS_GPU_PASS ' "$result_dir/game-inputs-gpu.log"
    if grep -Eq '\[LWJGL\] \[ERROR\]|GLFW error' "$result_dir/game-inputs-gpu.log"; then
        echo 'Native error in game input test'; exit 1
    fi
fi
echo 'GAME_INPUTS_CHECK_PASS'
