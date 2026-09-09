#!/bin/bash
set -euo pipefail
if [[ $# != 5 && $# != 6 ]]; then
    echo "usage: $0 JAVA21_HOME LEGACY_168.app BASELINE_169.app GLFW_CANDIDATE.app ORIGINAL_MCGL.jar [--live]" >&2
    exit 64
fi
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk_root=$1
legacy="$2/Contents/Resources/PortSupport/bin/lwjgl.jar"
baseline="$3/Contents/Resources"
candidate="$4/Contents/Resources"
original=$5
utility="$(dirname "$original")/lwjgl_util.jar"
asm="$candidate/PatchTools/asm-debug-all.jar"
modern="$candidate/PortSupport/bin/lwjgl.jar"
result_dir=$(mktemp -d /private/tmp/mcgl-glfw-check.XXXXXX)
echo "GLFW test outputs (retained): $result_dir"
mkdir -p "$result_dir/classes" "$result_dir/input" "$result_dir/render" "$result_dir/bootstrap" "$result_dir/window" "$result_dir/window-bootstrap"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$asm" -d "$result_dir/classes" \
    "$source_root/tools/PatchMCGLLwjgl3.java" "$source_root/tests/lwjgl3/LWJGL3PatchTest.java" \
    "$source_root/tests/lwjgl3/LWJGL3LinkageAudit.java" \
    "$source_root/tests/glfw/BuildWindowResizeFixture.java"
test_cp="$result_dir/classes:$asm"
"$jdk_root/bin/java" -cp "$test_cp" LWJGL3PatchTest "$original" "$utility"
for name in mcgl lwjgl_util; do
    input="$utility"; [[ "$name" != mcgl ]] || input="$original"
    "$jdk_root/bin/java" -cp "$test_cp" PatchMCGLLwjgl3 "$input" "$result_dir/$name.jar"
    "$jdk_root/bin/java" -cp "$test_cp:$modern" LWJGL3LinkageAudit "$result_dir/$name.jar"
done
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$modern" -d "$result_dir/input" "$source_root/tests/glfw/GLFWInputTest.java"
"$jdk_root/bin/java" -cp "$result_dir/input:$modern" org.lwjgl.opengl.GLFWInputTest
unzip -Z1 "$modern" > "$result_dir/library-entries.txt"
if grep -Eq '^org/lwjgl/opengl/(MacOSX|ContextGL|DisplayImplementation)' "$result_dir/library-entries.txt"; then
    echo 'Old window backend remains'; exit 1
fi
[[ ! -e "$candidate/PortSupport/bin/natives/libmcgl-window.dylib" ]] || exit 1
version=$("$jdk_root/bin/java" -cp "$modern" org.lwjgl.Version 2>/dev/null)
[[ "$version" == 3.4.3+4 ]] || exit 1
echo 'GLFW_STRUCTURE_PASS LWJGL metadata intact; old native window backend absent'
if [[ $# == 6 ]]; then
    [[ "$6" == --live ]] || exit 64
    "$jdk_root/bin/javac" --release 8 -cp "$legacy" -d "$result_dir/render" "$source_root/tests/lwjgl3/LWJGLRenderProbe.java"
    "$jdk_root/bin/javac" --release 8 -d "$result_dir/bootstrap" "$source_root/tests/lwjgl3/launcher/DirectLauncher.java"
    "$jdk_root/bin/jar" cf "$result_dir/render.jar" -C "$result_dir/render" .
    "$jdk_root/bin/jar" cf "$result_dir/bootstrap.jar" -C "$result_dir/bootstrap" .
    "$jdk_root/bin/java" -cp "$test_cp" PatchMCGLLwjgl3 "$result_dir/render.jar" "$result_dir/render-modern.jar"
    swiftc -module-cache-path "${MCGL_TEST_SWIFT_MODULE_CACHE:-$result_dir/modules}" "$source_root/tests/lwjgl3/RuntimeHarness.swift" -o "$result_dir/runtime-harness"
    for variant in baseline candidate; do
        resources="$baseline"; [[ "$variant" != candidate ]] || resources="$candidate"
        profile="$result_dir/$variant"
        mkdir -p "$profile/bin"
        ditto "$resources/PortSupport/bin/natives" "$profile/bin/natives"
        ditto "$resources/PortSupport/bin/lwjgl.jar" "$profile/bin/lwjgl.jar"
        ditto "$result_dir/render-modern.jar" "$profile/bin/render-probe.jar"
        ditto "$result_dir/bootstrap.jar" "$profile/mcgl-nativewindow-patch.jar"
        "$result_dir/runtime-harness" "$resources/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" \
            "$profile" "$result_dir/$variant.log"
        grep -q 'LWJGL_RENDER_PROBE_PASS' "$result_dir/$variant.log"
        if grep -Eq '\[LWJGL\] \[ERROR\]|GLFW error' "$result_dir/$variant.log"; then
            echo 'Native loader/window error in render probe'; exit 1
        fi
        grep -E '^RENDER_(FORMAT|SAMPLE|FIXTURE) ' "$result_dir/$variant.log" > "$result_dir/$variant-render.txt"
    done
    cmp "$result_dir/baseline-render.txt" "$result_dir/candidate-render.txt"
    echo 'GLFW_RENDER_EQUALITY_PASS: identical format and fixture pixels across two context lifetimes'
    "$jdk_root/bin/java" -cp "$candidate/PatchTools:$asm" PatchMCGLPerformance "$original" "$result_dir/window-client.jar"
    "$jdk_root/bin/java" -cp "$test_cp" BuildWindowResizeFixture "$result_dir/window-client.jar" "$result_dir/window" --require-hook
    "$jdk_root/bin/javac" --release 8 -cp "$modern:$result_dir/window" -d "$result_dir/window" "$source_root/tests/glfw/GLFWWindowProbe.java"
    "$jdk_root/bin/javac" --release 8 -d "$result_dir/window-bootstrap" "$source_root/tests/glfw/launcher/DirectLauncher.java"
    "$jdk_root/bin/jar" cf "$result_dir/window-test.jar" -C "$result_dir/window" .
    "$jdk_root/bin/jar" cf "$result_dir/window-bootstrap.jar" -C "$result_dir/window-bootstrap" .
    profile="$result_dir/window-profile"
    mkdir -p "$profile/bin"
    ditto "$candidate/PortSupport/bin/natives" "$profile/bin/natives"
    ditto "$modern" "$profile/bin/lwjgl.jar"
    ditto "$result_dir/window-test.jar" "$profile/bin/window-test.jar"
    ditto "$result_dir/window-bootstrap.jar" "$profile/mcgl-nativewindow-patch.jar"
    "$result_dir/runtime-harness" "$candidate/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" \
        "$profile" "$result_dir/window.log"
    grep -q 'GLFW_WINDOW_PASS' "$result_dir/window.log"
    if grep -Eq '\[LWJGL\] \[ERROR\]|GLFW error' "$result_dir/window.log"; then
        echo 'Native loader/window error in lifecycle probe'; exit 1
    fi
fi
echo 'GLFW_CHECK_PASS'
