#!/bin/bash
set -euo pipefail
if [[ $# != 4 && $# != 5 ]]; then
    echo "usage: $0 JAVA21_HOME LEGACY_1.6.8.app LWJGL3_CANDIDATE.app ORIGINAL_MCGL.jar [--live]" >&2
    exit 64
fi
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk_root=$1
baseline="$2/Contents/Resources"
candidate="$3/Contents/Resources"
original=$4
utility="$(dirname "$original")/lwjgl_util.jar"
asm="$candidate/PatchTools/asm-debug-all.jar"
legacy="$baseline/PortSupport/bin/lwjgl.jar"
modern="$candidate/PortSupport/bin/lwjgl.jar"
result_dir=$(mktemp -d /private/tmp/mcgl-lwjgl3-check.XXXXXX)
echo "LWJGL3 test outputs (retained): $result_dir"
mkdir -p "$result_dir/classes" "$result_dir/render" "$result_dir/bootstrap"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$asm" -d "$result_dir/classes" \
    "$source_root/tools/PatchMCGLLwjgl3.java" "$source_root/tests/lwjgl3/LWJGL3PatchTest.java" \
    "$source_root/tests/lwjgl3/LWJGL3LinkageAudit.java"
test_cp="$result_dir/classes:$asm"
"$jdk_root/bin/java" -cp "$test_cp" LWJGL3PatchTest "$original" "$utility"
for name in mcgl lwjgl_util; do
    input="$utility"; [[ "$name" != mcgl ]] || input="$original"
    "$jdk_root/bin/java" -cp "$test_cp" PatchMCGLLwjgl3 "$input" "$result_dir/$name.jar"
    "$jdk_root/bin/java" -cp "$test_cp:$modern" LWJGL3LinkageAudit "$result_dir/$name.jar"
done
version=$("$jdk_root/bin/java" -cp "$modern" org.lwjgl.Version 2>/dev/null)
[[ "$version" == 3.4.3+4 ]] || { echo "Incorrect LWJGL metadata: $version" >&2; exit 1; }
echo "LWJGL3_METADATA_PASS $version"
nm -gU "$candidate/PortSupport/bin/natives/libmcgl-window.dylib" > "$result_dir/window-symbols.txt"
if grep -E 'Java_org_lwjgl_(openal|opencl|opengl_(GL[0-9]|ARB|EXT|NV|APPLE))' "$result_dir/window-symbols.txt"; then
    echo 'Legacy binding symbols remain' >&2; exit 1
fi
echo 'LWJGL3_NATIVE_SEPARATION_PASS'
if [[ $# == 5 ]]; then
    [[ "$5" == --live ]] || exit 64
    "$jdk_root/bin/javac" --release 8 -cp "$legacy" -d "$result_dir/render" "$source_root/tests/lwjgl3/LWJGLRenderProbe.java"
    "$jdk_root/bin/javac" --release 8 -d "$result_dir/bootstrap" "$source_root/tests/lwjgl3/launcher/DirectLauncher.java"
    "$jdk_root/bin/jar" cf "$result_dir/render.jar" -C "$result_dir/render" .
    "$jdk_root/bin/jar" cf "$result_dir/bootstrap.jar" -C "$result_dir/bootstrap" .
    "$jdk_root/bin/java" -cp "$test_cp" PatchMCGLLwjgl3 "$result_dir/render.jar" "$result_dir/render-modern.jar"
    swiftc -module-cache-path "$result_dir/modules" "$source_root/tests/lwjgl3/RuntimeHarness.swift" -o "$result_dir/runtime-harness"
    for variant in baseline candidate; do
        resources="$baseline"; render="$result_dir/render.jar"
        if [[ "$variant" == candidate ]]; then resources="$candidate"; render="$result_dir/render-modern.jar"; fi
        profile="$result_dir/$variant"
        mkdir -p "$profile/bin"
        ditto "$resources/PortSupport/bin/natives" "$profile/bin/natives"
        ditto "$resources/PortSupport/bin/lwjgl.jar" "$profile/bin/lwjgl.jar"
        ditto "$render" "$profile/bin/render-probe.jar"
        ditto "$result_dir/bootstrap.jar" "$profile/mcgl-nativewindow-patch.jar"
        "$result_dir/runtime-harness" \
            "$resources/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" \
            "$profile" "$result_dir/$variant.log"
        grep -q 'LWJGL_RENDER_PROBE_PASS' "$result_dir/$variant.log"
        grep '^RENDER_FIXTURE ' "$result_dir/$variant.log" > "$result_dir/$variant-render.txt"
    done
    cmp "$result_dir/baseline-render.txt" "$result_dir/candidate-render.txt"
    echo 'LWJGL3_RENDER_EQUALITY_PASS: identical fixed-function fixture pixels, two context lifetimes'
fi
echo 'LWJGL3_CHECK_PASS'
