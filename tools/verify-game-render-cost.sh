#!/bin/bash
# Account-free renderer A/B diagnostic using the actual candidate, optionally overlaid with newly built renderer classes.
set -euo pipefail
[[ $# == 2 || $# == 3 ]] || { echo 'usage: JAVA21_HOME CANDIDATE.app [RENDERER_CLASSES]' >&2; exit 64; }
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk_root=$1
resources="$2/Contents/Resources"
library="$resources/PortSupport/bin/lwjgl.jar"
result_dir=$(mktemp -d /private/tmp/mcgl-game-render-cost.XXXXXX)
echo "Game render cost diagnostic outputs (retained): $result_dir"
mkdir -p "$result_dir/classes" "$result_dir/bootstrap" "$result_dir/profile/bin"
if [[ $# == 3 ]]; then ditto "$3" "$result_dir/classes"; fi
"$jdk_root/bin/javac" --release 21 -encoding UTF-8 -cp "$result_dir/classes:$library" -d "$result_dir/classes" "$source_root/tests/render/cost/GameRenderCostProbe.java"
"$jdk_root/bin/javac" --release 8 -d "$result_dir/bootstrap" "$source_root/tests/render/game-cost-launcher/DirectLauncher.java"
"$jdk_root/bin/jar" cf "$result_dir/profile/bin/game-cost.jar" -C "$result_dir/classes" .
"$jdk_root/bin/jar" cf "$result_dir/profile/mcgl-nativewindow-patch.jar" -C "$result_dir/bootstrap" .
ditto "$library" "$result_dir/profile/bin/lwjgl.jar"
ditto "$resources/PortSupport/bin/natives" "$result_dir/profile/bin/natives"
swiftc -module-cache-path "${MCGL_TEST_SWIFT_MODULE_CACHE:-$result_dir/modules}" "$source_root/tests/lwjgl3/RuntimeHarness.swift" -o "$result_dir/runtime-harness"
"$result_dir/runtime-harness" "$resources/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" \
    "$result_dir/profile" "$result_dir/game-render-cost.log"
grep -q '^GAME_RENDER_COST_PASS ' "$result_dir/game-render-cost.log"
if grep -Eq '\[LWJGL\] \[ERROR\]|GLFW error|UNSUPPORTED \(log once\)' "$result_dir/game-render-cost.log"; then
    echo 'Native error in render cost diagnostic'; exit 1
fi
"$jdk_root/bin/jfr" summary "$result_dir/profile/render-cost.jfr"
echo 'GAME_RENDER_COST_CHECK_PASS'
