#!/bin/bash
# Stage 10 integration check against a staged post-stage-9 original client, without account/server access.
set -euo pipefail
[[ $# -ge 4 && $# -le 6 ]] || { echo 'usage: JAVA21_HOME FOUNDATION.app POST_CHUNK_CLIENT.jar ORIGINAL_SHADER_DIRECTORY [--live] [--original-chunks]' >&2; exit 64; }
live=0
original=0
for option in "${@:5}"; do
    case "$option" in --live) live=1 ;; --original-chunks) original=1 ;; *) echo "Unknown option: $option" >&2; exit 64 ;; esac
done
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk_root=$1
resources="$2/Contents/Resources"
library="$resources/PortSupport/bin/lwjgl.jar"
asm="$resources/PatchTools/asm-debug-all.jar"
result_dir=$(mktemp -d /private/tmp/mcgl-game-renderer-check.XXXXXX)
echo "Game renderer test outputs (retained): $result_dir"
mkdir -p "$result_dir/classes" "$result_dir/bootstrap" "$result_dir/tools"
bash "$source_root/tools/build-render-support.sh" "$jdk_root" "$library" "$asm" "$result_dir/renderer"
ditto "$result_dir/renderer/classes" "$result_dir/classes"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$result_dir/classes:$library" -d "$result_dir/classes" \
    "$source_root/lwjgl3-compat/src/org/lwjgl/opengl/ContextCapabilities.java" \
    "$source_root/tests/render/GameInputsTest.java" "$source_root/tests/render/ChunkContractsTest.java" \
    "$source_root/tests/render/GameModelBatchTest.java" "$source_root/tests/render/GameModelBatchProbe.java" \
    "$source_root/tests/render/GameTerrainMaterialsProbe.java" \
    "$source_root/tests/render/GameTextureArraysProbe.java" "$source_root/tests/render/GameTextureArrayBudgetProbe.java" \
    "$source_root/tests/render/GameChunkTexturesTest.java" \
    "$source_root/tests/render/GameChunkTexturesProbe.java" \
    "$source_root/tests/render/OriginalChunkCacheProbe.java" \
    "$source_root/tests/render/OriginalChunkEmitter.java" "$source_root/tests/render/GameTextProbe.java" "$source_root/tests/render/GameTerrainBatchProbe.java" "$source_root/tests/render/GameTerrainRecoveryProbe.java" "$source_root/tests/render/GameTerrainFormatTest.java" "$source_root/tests/render/GameRendererProbe.java"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$asm" -d "$result_dir/tools" \
    "$source_root/tools/RenderCommandSpec.java" "$source_root/tools/PatchMCGLGame.java" "$source_root/tests/render/GameAdapterTest.java" "$source_root/tests/render/OriginalChunkAdapterTest.java" "$source_root/tests/render/FontAdapterTest.java"
"$jdk_root/bin/java" -Djava.awt.headless=true -cp "$result_dir/classes:$library" local.mcgl.render.GameInputsTest "$4"
"$jdk_root/bin/java" -Djava.awt.headless=true -cp "$result_dir/classes:$library" local.mcgl.render.backend.GameChunkTexturesTest
"$jdk_root/bin/java" -Djava.awt.headless=true -cp "$result_dir/classes:$library" local.mcgl.render.backend.GameTerrainFormatTest
"$jdk_root/bin/java" -Djava.awt.headless=true -cp "$result_dir/classes:$library" local.mcgl.render.backend.GameModelBatchTest
if [[ $original == 1 ]]; then
    "$jdk_root/bin/java" -cp "$result_dir/tools:$asm" PatchMCGLGame "$3" "$result_dir/game-client.jar" "$source_root/renderer/legacy-commands.txt" --original-chunks
    "$jdk_root/bin/java" -cp "$result_dir/tools:$asm" OriginalChunkAdapterTest "$3" "$result_dir/game-client.jar" "$source_root/renderer/legacy-commands.txt" "$result_dir/negative"
else
    "$jdk_root/bin/java" -cp "$result_dir/tools:$asm" PatchMCGLGame "$3" "$result_dir/game-client.jar" "$source_root/renderer/legacy-commands.txt"
    "$jdk_root/bin/java" -cp "$result_dir/tools:$asm" GameAdapterTest "$3" "$result_dir/game-client.jar" "$source_root/renderer/legacy-commands.txt" "$result_dir/negative"
fi
"$jdk_root/bin/java" -cp "$result_dir/tools:$asm" FontAdapterTest "$3" "$result_dir/game-client.jar" "$source_root/renderer/legacy-commands.txt" "$result_dir/font-negative"
"$jdk_root/bin/java" -Djava.awt.headless=true -cp "$result_dir/classes:$library:$result_dir/game-client.jar" \
    local.mcgl.render.ChunkContractsTest "$result_dir/game-client.jar"
if [[ $live == 1 ]]; then
    "$jdk_root/bin/javac" --release 8 -d "$result_dir/bootstrap" "$source_root/tests/render/game-launcher/DirectLauncher.java"
    "$jdk_root/bin/jar" cf "$result_dir/game-renderer.jar" -C "$result_dir/classes" .
    "$jdk_root/bin/jar" cf "$result_dir/bootstrap.jar" -C "$result_dir/bootstrap" .
    swiftc -module-cache-path "${MCGL_TEST_SWIFT_MODULE_CACHE:-$result_dir/modules}" "$source_root/tests/lwjgl3/RuntimeHarness.swift" -o "$result_dir/runtime-harness"
    profile="$result_dir/profile"
    mkdir -p "$profile/bin/media/graph"
    ditto "$resources/PortSupport/bin/natives" "$profile/bin/natives"
    ditto "$library" "$profile/bin/lwjgl.jar"
    ditto "$4" "$profile/bin/media/graph/shader"
    if [[ -f "$4/../../gui/keypositions" ]]; then
        mkdir -p "$profile/bin/media/gui"
        ditto "$4/../../gui/keypositions" "$profile/bin/media/gui/keypositions"
    fi
    ditto "$result_dir/game-client.jar" "$profile/bin/mcgl.jar"
    ditto "$result_dir/game-renderer.jar" "$profile/bin/game-renderer.jar"
    ditto "$result_dir/bootstrap.jar" "$profile/mcgl-nativewindow-patch.jar"
    "$result_dir/runtime-harness" "$resources/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" \
        "$profile" "$result_dir/game-renderer-gpu.log"
    grep -q '^GAME_RENDERER_GPU_PASS ' "$result_dir/game-renderer-gpu.log"
    if grep -Eq '\[LWJGL\] \[ERROR\]|GLFW error|UNSUPPORTED \(log once\)' "$result_dir/game-renderer-gpu.log"; then
        echo 'Native error in game renderer test'; exit 1
    fi
    # A separate context exercises native incomplete-texture fallback deliberately;
    # its expected driver warning is not part of the clean original-client suite.
    mkdir -p "$result_dir/arrays-bootstrap"
    "$jdk_root/bin/javac" --release 8 -d "$result_dir/arrays-bootstrap" "$source_root/tests/render/texture-array-launcher/DirectLauncher.java"
    "$jdk_root/bin/jar" cf "$result_dir/arrays-bootstrap.jar" -C "$result_dir/arrays-bootstrap" .
    ditto "$result_dir/arrays-bootstrap.jar" "$profile/mcgl-nativewindow-patch.jar"
    "$result_dir/runtime-harness" "$resources/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" \
        "$profile" "$result_dir/texture-arrays-gpu.log"
    grep -q '^GAME_TEXTURE_ARRAYS_PASS ' "$result_dir/texture-arrays-gpu.log"
    grep -q '^ARRAY_TEXTURE_BUDGET_PASS ' "$result_dir/texture-arrays-gpu.log"
fi
echo 'GAME_RENDERER_CHECK_PASS'
