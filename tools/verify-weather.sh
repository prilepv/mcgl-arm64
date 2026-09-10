#!/bin/bash
# Compare released/fixed packaged patchers using original bytecode; no account, window or server.
set -euo pipefail
if [[ $# != 4 ]]; then
    echo 'usage: JAVA21_HOME RELEASED-1.7.0.app FIXED.app POST_CHUNK_CLIENT.jar' >&2
    exit 64
fi
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk_root=$1
before="$2/Contents/Resources"
after="$3/Contents/Resources"
original=$4
result_dir=$(mktemp -d /private/tmp/mcgl-weather-check.XXXXXX)
echo "Weather test outputs (retained): $result_dir"
mkdir -p "$result_dir/classes"
asm="$after/PatchTools/asm-debug-all.jar"
library="$after/PortSupport/bin/lwjgl.jar"
manifest="$source_root/renderer/legacy-commands.txt"
"$jdk_root/bin/java" -cp "$before/PatchTools:$before/PatchTools/asm-debug-all.jar" PatchMCGLGame \
    "$original" "$result_dir/before.jar" "$manifest" --original-chunks
"$jdk_root/bin/java" -cp "$after/PatchTools:$asm" PatchMCGLGame \
    "$original" "$result_dir/after.jar" "$manifest" --original-chunks
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$after/PatchTools:$asm:$library" \
    -d "$result_dir/classes" "$source_root/tests/render/OriginalChunkEmitter.java" \
    "$source_root/tests/render/WeatherPatchTest.java"
"$jdk_root/bin/java" -Xverify:all -Djava.awt.headless=true \
    -cp "$result_dir/classes:$after/PatchTools:$asm:$library:$result_dir/after.jar" WeatherPatchTest \
    "$original" "$result_dir/before.jar" "$result_dir/after.jar" "$manifest" "$result_dir/negative"
echo 'WEATHER_CHECK_PASS packaged-patchers/original-client/CPU-only'
