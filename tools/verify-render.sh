#!/bin/bash
# Renderer boundary/equivalence checks. Live GPU suites are deliberately sequential.
set -euo pipefail
if [[ $# != 6 && $# != 7 ]]; then
    echo 'usage: JAVA21_HOME LEGACY_168.app BASELINE_1611.app CANDIDATE_1612.app BASELINE_CLIENT.jar BASELINE_UTIL.jar [--live]' >&2
    exit 64
fi
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk_root=$1
legacy="$2/Contents/Resources/PortSupport/bin/lwjgl.jar"
baseline="$3/Contents/Resources"
candidate="$4/Contents/Resources"
modern="$candidate/PortSupport/bin/lwjgl.jar"
asm="$candidate/PatchTools/asm-debug-all.jar"
manifest="$candidate/PatchTools/render-commands.txt"
result_dir=$(mktemp -d /private/tmp/mcgl-render-check.XXXXXX)
echo "Renderer test outputs (retained): $result_dir"
mkdir -p "$result_dir/classes" "$result_dir/bridge-fixture" "$result_dir/render" "$result_dir/bootstrap" "$result_dir/lifecycle-bootstrap"
cmp "$source_root/renderer/legacy-commands.txt" "$manifest"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$modern:$asm:$candidate/PatchTools" -d "$result_dir/classes" \
    "$source_root"/tests/render/*.java "$source_root/tools/RenderCommandSpec.java"
"$jdk_root/bin/javac" --release 8 -cp "$modern" -d "$result_dir/bridge-fixture" \
    "$source_root/tests/render/bridge-fixture/RenderSystem.java"
# Test the packaged patcher and manifest rather than an unshipped source-only transform.
test_cp="$result_dir/classes:$candidate/PatchTools:$modern:$asm"
"$jdk_root/bin/java" -Djava.awt.headless=true "-Xlog:library=info:file=$result_dir/headless-libraries.log" \
    -cp "$test_cp" local.mcgl.render.RenderDeviceTest
if grep -Ei 'Loaded library .*(lwjgl|glfw|OpenGL)' "$result_dir/headless-libraries.log"; then
    echo 'Native renderer initialized during contract-only checks'; exit 1
fi
"$jdk_root/bin/java" -Djava.awt.headless=true -cp "$test_cp" BridgeDispatchTest "$manifest" "$modern" "$result_dir/bridge-fixture"
"$jdk_root/bin/java" -cp "$test_cp" RenderPatchTest "$manifest" "$5" "$6" "$result_dir/patch"
"$jdk_root/bin/java" -cp "$test_cp" RenderBoundaryAudit "$baseline/PortSupport/bin/lwjgl.jar" "$modern" "$manifest" \
    "$result_dir/patch/adapted-1.jar" "$result_dir/patch/adapted-2.jar"
"$jdk_root/bin/java" -cp "$test_cp" LWJGL3LinkageAudit "$result_dir/patch/adapted-1.jar" "$result_dir/patch/adapted-2.jar"
if [[ $# == 7 ]]; then
    [[ "$7" == --live ]] || exit 64
    "$jdk_root/bin/javac" --release 8 -cp "$legacy" -d "$result_dir/render" "$source_root/tests/lwjgl3/LWJGLRenderProbe.java"
    "$jdk_root/bin/javac" --release 8 -d "$result_dir/bootstrap" "$source_root/tests/lwjgl3/launcher/DirectLauncher.java"
    "$jdk_root/bin/jar" cf "$result_dir/render.jar" -C "$result_dir/render" .
    "$jdk_root/bin/jar" cf "$result_dir/bootstrap.jar" -C "$result_dir/bootstrap" .
    "$jdk_root/bin/java" -cp "$test_cp" PatchMCGLLwjgl3 "$result_dir/render.jar" "$result_dir/render-modern.jar"
    "$jdk_root/bin/java" -cp "$test_cp" PatchMCGLRenderer "$result_dir/render-modern.jar" "$result_dir/render-routed.jar" "$manifest"
    swiftc -module-cache-path "${MCGL_TEST_SWIFT_MODULE_CACHE:-$result_dir/modules}" "$source_root/tests/lwjgl3/RuntimeHarness.swift" -o "$result_dir/runtime-harness"
    for variant in baseline candidate; do
        resources="$baseline"; probe="$result_dir/render-modern.jar"
        if [[ "$variant" == candidate ]]; then resources="$candidate"; probe="$result_dir/render-routed.jar"; fi
        profile="$result_dir/$variant"
        mkdir -p "$profile/bin"
        ditto "$resources/PortSupport/bin/natives" "$profile/bin/natives"
        ditto "$resources/PortSupport/bin/lwjgl.jar" "$profile/bin/lwjgl.jar"
        ditto "$probe" "$profile/bin/render-probe.jar"
        ditto "$result_dir/bootstrap.jar" "$profile/mcgl-nativewindow-patch.jar"
        "$result_dir/runtime-harness" "$resources/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" "$profile" "$result_dir/$variant.log"
        grep -q 'LWJGL_RENDER_PROBE_PASS' "$result_dir/$variant.log"
        if grep -Eq '\[LWJGL\] \[ERROR\]|GLFW error' "$result_dir/$variant.log"; then echo 'Native error in renderer comparison'; exit 1; fi
        grep -E '^RENDER_(FORMAT|SAMPLE|FIXTURE) ' "$result_dir/$variant.log" > "$result_dir/$variant-pixels.txt"
    done
    cmp "$result_dir/baseline-pixels.txt" "$result_dir/candidate-pixels.txt"
    echo 'RENDER_GPU_EQUALITY_PASS: fully routed commands match 1.6.11 format/pixels over two context lifetimes'
    "$jdk_root/bin/javac" --release 8 -d "$result_dir/lifecycle-bootstrap" "$source_root/tests/render/launcher/DirectLauncher.java"
    "$jdk_root/bin/jar" cf "$result_dir/render-lifecycle.jar" -C "$result_dir/classes" local/mcgl/render/tests
    "$jdk_root/bin/jar" cf "$result_dir/lifecycle-bootstrap.jar" -C "$result_dir/lifecycle-bootstrap" .
    profile="$result_dir/lifecycle"
    mkdir -p "$profile/bin"
    ditto "$candidate/PortSupport/bin/natives" "$profile/bin/natives"
    ditto "$modern" "$profile/bin/lwjgl.jar"
    ditto "$result_dir/render-lifecycle.jar" "$profile/bin/render-lifecycle.jar"
    ditto "$result_dir/lifecycle-bootstrap.jar" "$profile/mcgl-nativewindow-patch.jar"
    "$result_dir/runtime-harness" "$candidate/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" "$profile" "$result_dir/lifecycle.log"
    grep -q 'RENDER_LIFECYCLE_PASS' "$result_dir/lifecycle.log"
    if grep -Eq '\[LWJGL\] \[ERROR\]|GLFW error' "$result_dir/lifecycle.log"; then echo 'Native error in renderer lifecycle probe'; exit 1; fi
fi
echo 'RENDER_CHECK_PASS'
