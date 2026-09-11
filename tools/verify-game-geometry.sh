#!/bin/bash
# Exact CPU comparison and cost sample against a separately loaded packaged renderer.
set -euo pipefail
[[ $# == 2 ]] || { echo 'usage: JAVA21_HOME BASELINE_LWJGL.jar' >&2; exit 64; }
source_root=$(cd "$(dirname "$0")/.." && pwd)
result_dir=$(mktemp -d /private/tmp/mcgl-game-geometry.XXXXXX)
echo "Game geometry test outputs (retained): $result_dir"
"$1/bin/javac" --release 8 -encoding UTF-8 -cp "$2" -d "$result_dir/classes" \
    "$source_root/renderer/src/local/mcgl/render/GameGeometry.java" \
    "$source_root/tests/render/GameGeometryEquivalenceTest.java" \
    "$source_root/tests/render/cost/GameGeometryCostProbe.java"
"$1/bin/java" -Xmx512m -XX:MaxDirectMemorySize=1024m -Djava.awt.headless=true -cp "$result_dir/classes:$2" local.mcgl.render.GameGeometryEquivalenceTest "$2"
"$1/bin/java" -Xmx512m -XX:MaxDirectMemorySize=1024m -Djava.awt.headless=true -cp "$result_dir/classes:$2" local.mcgl.render.tests.GameGeometryCostProbe "$2"
echo 'GAME_GEOMETRY_CHECK_PASS'
