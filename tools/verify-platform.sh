#!/bin/bash
# Platform contract/ABI audit plus optional native failure/ownership tests.
set -euo pipefail
if [[ $# != 3 && $# != 4 ]]; then
    echo "usage: $0 JAVA21_HOME BASELINE_1610.app CANDIDATE_1611.app [--live]" >&2
    exit 64
fi
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk_root=$1
baseline="$2/Contents/Resources/PortSupport/bin/lwjgl.jar"
candidate="$3/Contents/Resources"
modern="$candidate/PortSupport/bin/lwjgl.jar"
asm="$candidate/PatchTools/asm-debug-all.jar"
result_dir=$(mktemp -d /private/tmp/mcgl-platform-check.XXXXXX)
echo "Platform test outputs (retained): $result_dir"
mkdir -p "$result_dir/classes" "$result_dir/bootstrap"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$modern:$asm" -d "$result_dir/classes" \
    "$source_root"/tests/platform/*.java
test_cp="$result_dir/classes:$modern:$asm"
"$jdk_root/bin/java" -cp "$test_cp" PlatformBoundaryAudit "$baseline" "$modern"
"$jdk_root/bin/java" -Djava.awt.headless=true -cp "$test_cp" local.mcgl.platform.PlatformContractTest
"$jdk_root/bin/java" -Djava.awt.headless=true -cp "$test_cp" org.lwjgl.opengl.InputBackendContractTest
if [[ $# == 4 ]]; then
    [[ "$4" == --live ]] || exit 64
    "$jdk_root/bin/javac" --release 8 -d "$result_dir/bootstrap" "$source_root/tests/platform/launcher/DirectLauncher.java"
    "$jdk_root/bin/jar" cf "$result_dir/platform-test.jar" -C "$result_dir/classes" local/mcgl/platform
    "$jdk_root/bin/jar" cf "$result_dir/bootstrap.jar" -C "$result_dir/bootstrap" .
    swiftc -module-cache-path "${MCGL_TEST_SWIFT_MODULE_CACHE:-$result_dir/modules}" "$source_root/tests/lwjgl3/RuntimeHarness.swift" -o "$result_dir/runtime-harness"
    profile="$result_dir/profile"
    mkdir -p "$profile/bin"
    ditto "$candidate/PortSupport/bin/natives" "$profile/bin/natives"
    ditto "$modern" "$profile/bin/lwjgl.jar"
    ditto "$result_dir/platform-test.jar" "$profile/bin/platform-test.jar"
    ditto "$result_dir/bootstrap.jar" "$profile/mcgl-nativewindow-patch.jar"
    "$result_dir/runtime-harness" "$candidate/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" \
        "$profile" "$result_dir/window.log"
    grep -q 'PLATFORM_WINDOW_PASS' "$result_dir/window.log"
    if grep -Eq '\[LWJGL\] \[ERROR\]|GLFW error' "$result_dir/window.log"; then
        echo 'Native loader/window error in platform lifecycle probe'; exit 1
    fi
fi
echo 'PLATFORM_CHECK_PASS'
