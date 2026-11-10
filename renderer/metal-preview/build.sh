#!/bin/bash
set -euo pipefail
[[ $# == 2 ]] || { echo 'Usage: build.sh JAVA21_HOME OUTPUT_DYLIB' >&2; exit 64; }
source_dir=$(cd "$(dirname "$0")" && pwd -P)
jdk=$(cd "$1" && pwd -P)
work=$(mktemp -d /private/tmp/mcgl-metal-preview.XXXXXX)
trap 'rm -rf -- "$work"' EXIT
(cd "$source_dir" && xxd -i terrain.metal > "$work/terrain_shader.h")
xcrun clang -dynamiclib -arch arm64 -mmacosx-version-min=14.0 -fobjc-arc -O2 -Wall -Wextra -Wno-deprecated-declarations \
    -I"$jdk/include" -I"$jdk/include/darwin" -I"$work" -framework Foundation -framework Metal \
    -framework OpenGL -framework IOSurface \
    "$source_dir/MetalTerrainPreview.m" -o "$2"
