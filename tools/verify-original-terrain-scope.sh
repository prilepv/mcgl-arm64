#!/bin/bash
# Read-only packaged 1.7.1 comparison plus execution of the new wrapper's actual
# success/failure paths. No account, server, installation or graphical window.
set -euo pipefail
[[ $# == 3 ]] || { echo 'usage: JAVA21_HOME RELEASED-1.7.1.app POST_CHUNK_CLIENT.jar' >&2; exit 64; }
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk_root=$1
resources="$2/Contents/Resources"
original=$3
result_dir=$(mktemp -d /private/tmp/mcgl-original-terrain-scope.XXXXXX)
echo "Original terrain scope outputs (retained): $result_dir"
asm="$resources/PatchTools/asm-debug-all.jar"
library="$resources/PortSupport/bin/lwjgl.jar"
mkdir -p "$result_dir/tools"
bash "$source_root/tools/build-render-support.sh" "$jdk_root" "$library" "$asm" "$result_dir/renderer"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$asm:$result_dir/renderer/classes" -d "$result_dir/tools" \
    "$source_root/tools/RenderCommandSpec.java" "$source_root/tools/PatchMCGLGame.java" \
    "$source_root/tests/render/OriginalTerrainScopePatchTest.java"
"$jdk_root/bin/java" -cp "$resources/PatchTools:$asm" PatchMCGLGame "$original" "$result_dir/released.jar" \
    "$source_root/renderer/legacy-commands.txt" --original-chunks
"$jdk_root/bin/java" -cp "$result_dir/tools:$asm" PatchMCGLGame "$original" "$result_dir/candidate.jar" \
    "$source_root/renderer/legacy-commands.txt" --original-chunks
"$jdk_root/bin/java" -Xverify:all -cp "$result_dir/tools:$result_dir/renderer/classes:$asm:$library" \
    OriginalTerrainScopePatchTest "$result_dir/released.jar" "$result_dir/candidate.jar"
echo 'ORIGINAL_TERRAIN_SCOPE_CHECK_PASS'
