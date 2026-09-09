#!/bin/bash
set -euo pipefail
# Transitional Cocoa/input-only module. No generated LWJGL 2 GL/AL bindings.
if [[ $# -ne 3 ]]; then
    echo "usage: $0 PREPARED_LWJGL2_SOURCE JAVA21_HOME NEW_OUTPUT_DIRECTORY" >&2
    exit 64
fi
source_root=$1
jdk_root=$2
output_root=$3
[[ ! -e "$output_root" ]] || { echo "Output already exists: $output_root" >&2; exit 65; }
mkdir -p "$output_root/objects"
native_root="$source_root/src/native"
for source in "$native_root"/macosx/*.m \
    "$native_root/common/common_tools.c" \
    "$native_root/common/org_lwjgl_opengl_AWTSurfaceLock.c"; do
    object_name=$(basename "${source%.*}")
    xcrun clang -x objective-c -arch arm64 -mmacosx-version-min=14.0 \
        -O2 -fPIC -I"$jdk_root/include" -I"$jdk_root/include/darwin" \
        -I"$native_root/common" -I"$native_root/common/opengl" -I"$native_root/macosx" \
        -c "$source" -o "$output_root/objects/$object_name.o"
done
xcrun clang -arch arm64 -mmacosx-version-min=14.0 -dynamiclib \
    -install_name @rpath/libmcgl-window.dylib \
    -framework Foundation -framework AppKit -framework Carbon \
    -framework OpenGL -framework QuartzCore -L"$jdk_root/lib" -ljawt \
    "$output_root"/objects/*.o -o "$output_root/libmcgl-window.dylib"
if nm -gU "$output_root/libmcgl-window.dylib" | \
    grep -E 'Java_org_lwjgl_(openal|opencl|opengl_(GL[0-9]|ARB|EXT|NV|APPLE))'; then
    echo 'ERROR: legacy GL/AL binding leaked into window module' >&2
    exit 1
fi
echo "WINDOW_NATIVE_PASS $output_root/libmcgl-window.dylib"
