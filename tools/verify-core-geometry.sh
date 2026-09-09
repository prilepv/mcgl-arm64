#!/bin/bash
# Milestones 6–8 end checkpoint, isolated native profiles, no game/account or installed app changes.
set -euo pipefail
[[ $# == 2 || $# == 3 ]] || { echo 'usage: JAVA21_HOME CANDIDATE.app [--live]' >&2; exit 64; }
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk_root=$1
resources="$2/Contents/Resources"
library="$resources/PortSupport/bin/lwjgl.jar"
result_dir=$(mktemp -d /private/tmp/mcgl-core-geometry-check.XXXXXX)
echo "Core geometry test outputs (retained): $result_dir"
mkdir -p "$result_dir/classes" "$result_dir/bootstrap"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$library" -d "$result_dir/classes" \
    "$source_root/tests/render/CoreContractsTest.java" "$source_root/tests/render/CoreGeometryProbe.java" \
    "$source_root/tests/render/MeshArenaProbe.java" "$source_root/tests/render/MeshArenaRangesTest.java"
"$jdk_root/bin/java" -Djava.awt.headless=true "-Xlog:library=info:file=$result_dir/headless-libraries.log" \
    -cp "$result_dir/classes:$library" local.mcgl.render.CoreContractsTest
"$jdk_root/bin/java" -Djava.awt.headless=true -cp "$result_dir/classes:$library" local.mcgl.render.backend.MeshArenaRangesTest
if grep -Ei 'Loaded library .*(lwjgl|glfw|OpenGL)' "$result_dir/headless-libraries.log"; then
    echo 'Native library loaded by CPU-only contracts'; exit 1
fi
if [[ $# == 3 ]]; then
    [[ "$3" == --live ]] || exit 64
    "$jdk_root/bin/javac" --release 8 -d "$result_dir/bootstrap" "$source_root/tests/render/core-launcher/DirectLauncher.java"
    "$jdk_root/bin/jar" cf "$result_dir/core-geometry.jar" -C "$result_dir/classes" local/mcgl/render/tests
    "$jdk_root/bin/jar" cf "$result_dir/bootstrap.jar" -C "$result_dir/bootstrap" .
    swiftc -module-cache-path "${MCGL_TEST_SWIFT_MODULE_CACHE:-$result_dir/modules}" "$source_root/tests/lwjgl3/RuntimeHarness.swift" -o "$result_dir/runtime-harness"
    profile="$result_dir/profile"
    mkdir -p "$profile/bin"
    ditto "$resources/PortSupport/bin/natives" "$profile/bin/natives"
    ditto "$library" "$profile/bin/lwjgl.jar"
    ditto "$result_dir/core-geometry.jar" "$profile/bin/core-geometry.jar"
    ditto "$result_dir/bootstrap.jar" "$profile/mcgl-nativewindow-patch.jar"
    "$result_dir/runtime-harness" "$resources/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" \
        "$profile" "$result_dir/core-geometry.log"
    grep -q '^CORE_GEOMETRY_PASS ' "$result_dir/core-geometry.log"
    if grep -Eq '\[LWJGL\] \[ERROR\]|GLFW error' "$result_dir/core-geometry.log"; then
        echo 'Native error in Core geometry probe'; exit 1
    fi
fi
echo 'CORE_GEOMETRY_CHECK_PASS'
