#!/bin/bash
set -euo pipefail
[[ $# == 2 ]] || { echo 'Usage: build-direct.sh JAVA21_HOME OUTPUT_DYLIB' >&2; exit 64; }
source_dir=$(cd "$(dirname "$0")" && pwd -P)
jdk=$(cd "$1" && pwd -P)
work=$(mktemp -d /private/tmp/mcgl-metal-direct-build.XXXXXX)
trap 'rm -rf -- "$work"' EXIT
(cd "$source_dir" && xxd -i terrain.metal > "$work/terrain_shader.h")
xcrun clang -dynamiclib -arch arm64 -mmacosx-version-min=14.0 -fobjc-arc -O2 -Wall -Wextra \
    -I"$jdk/include" -I"$jdk/include/darwin" -I"$work" -framework Cocoa -framework QuartzCore -framework Metal \
    "$source_dir/MetalTerrainWindow.m" -o "$2"
