#!/bin/bash
# Bit-exact comparison and allocation diagnostic against an explicit accepted renderer.
set -euo pipefail
[[ $# == 2 ]] || { echo 'usage: JAVA21_HOME ACCEPTED_RENDERER.jar' >&2; exit 64; }
root=$(cd "$(dirname "$0")/.." && pwd)
jdk=$1
accepted=$2
out=$(mktemp -d /private/tmp/mcgl-matrices-check.XXXXXX)
echo "Matrix test outputs (retained): $out"
mkdir -p "$out/classes"
"$jdk/bin/javac" --release 8 -encoding UTF-8 -d "$out/classes" \
    "$root/renderer/src/local/mcgl/render/GameMatrices.java" "$root/tests/render/GameMatricesRegressionTest.java"
"$jdk/bin/java" -cp "$out/classes" local.mcgl.render.tests.GameMatricesRegressionTest "$accepted"
"$jdk/bin/javac" --release 21 -encoding UTF-8 -cp "$out/classes" -d "$out/classes" \
    "$root/tests/render/cost/GameMatricesCostProbe.java"
"$jdk/bin/java" -cp "$out/classes" local.mcgl.render.tests.GameMatricesCostProbe "$accepted"
echo 'MATRIX_CHECK_PASS (exact transforms and synthetic allocation/CPU cost; not game FPS)'
