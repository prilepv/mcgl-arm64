#!/bin/bash
# Stage 9 end checkpoint. Existing post-1.6.11 client is adapted in temporary storage only.
set -euo pipefail
[[ $# == 3 || $# == 4 ]] || { echo 'usage: JAVA21_HOME CANDIDATE.app POST_1611_CLIENT.jar [--live]' >&2; exit 64; }
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk_root=$1
resources="$2/Contents/Resources"
library="$resources/PortSupport/bin/lwjgl.jar"
asm="$resources/PatchTools/asm-debug-all.jar"
result_dir=$(mktemp -d /private/tmp/mcgl-chunk-check.XXXXXX)
echo "Chunk renderer test outputs (retained): $result_dir"
mkdir -p "$result_dir/classes" "$result_dir/bootstrap" "$result_dir/patch"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$library:$asm:$resources/PatchTools" -d "$result_dir/classes" \
    "$source_root/tests/render/ChunkContractsTest.java" "$source_root/tests/render/ChunkPatchTest.java" \
    "$source_root/tests/render/OriginalChunkEmitter.java" "$source_root/tests/render/ChunkRendererProbe.java"
test_cp="$result_dir/classes:$resources/PatchTools:$library:$asm"
"$jdk_root/bin/java" -cp "$test_cp" PatchMCGLRenderer "$3" "$result_dir/rendered-client.jar" "$resources/PatchTools/render-commands.txt"
"$jdk_root/bin/java" -cp "$test_cp" ChunkPatchTest "$result_dir/rendered-client.jar" "$result_dir/patch"
client="$result_dir/patch/chunk-client.jar"
"$jdk_root/bin/java" -Djava.awt.headless=true "-Xlog:library=info:file=$result_dir/cpu-libraries.log" \
    -cp "$test_cp:$client" local.mcgl.render.ChunkContractsTest "$client"
if grep -Ei 'Loaded library .*(lwjgl|glfw|OpenGL)' "$result_dir/cpu-libraries.log"; then
    echo 'Native library loaded by CPU chunk build'; exit 1
fi
"$jdk_root/bin/java" -cp "$test_cp" LWJGL3LinkageAudit "$client"
if [[ $# == 4 ]]; then
    [[ "$4" == --live ]] || exit 64
    "$jdk_root/bin/javac" --release 8 -d "$result_dir/bootstrap" "$source_root/tests/render/chunk-launcher/DirectLauncher.java"
    "$jdk_root/bin/jar" cf "$result_dir/chunk-probe.jar" -C "$result_dir/classes" local/mcgl/render/tests
    "$jdk_root/bin/jar" cf "$result_dir/bootstrap.jar" -C "$result_dir/bootstrap" .
    swiftc -module-cache-path "${MCGL_TEST_SWIFT_MODULE_CACHE:-$result_dir/modules}" "$source_root/tests/lwjgl3/RuntimeHarness.swift" -o "$result_dir/runtime-harness"
    profile="$result_dir/profile"
    mkdir -p "$profile/bin"
    ditto "$resources/PortSupport/bin/natives" "$profile/bin/natives"
    ditto "$library" "$profile/bin/lwjgl.jar"
    ditto "$client" "$profile/bin/mcgl.jar"
    ditto "$result_dir/chunk-probe.jar" "$profile/bin/chunk-probe.jar"
    ditto "$result_dir/bootstrap.jar" "$profile/mcgl-nativewindow-patch.jar"
    "$result_dir/runtime-harness" "$resources/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" \
        "$profile" "$result_dir/chunk-gpu.log"
    grep -q '^CHUNK_GPU_PASS ' "$result_dir/chunk-gpu.log"
    if grep -Eq '\[LWJGL\] \[ERROR\]|GLFW error' "$result_dir/chunk-gpu.log"; then
        echo 'Native error in chunk renderer'; exit 1
    fi
fi
echo 'CHUNK_CHECK_PASS'
