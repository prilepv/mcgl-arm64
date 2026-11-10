#!/bin/bash
# GLFW 3.5.1, official source commit plus the checked-in NSGL update lock.
set -euo pipefail
[[ $# == 2 || ( $# == 3 && $3 == --metal-window ) ]] || { echo "usage: $0 DEPENDENCIES OUTPUT.dylib [--metal-window]" >&2; exit 64; }
source_root=$(cd "$(dirname "$0")/.." && pwd)
dependencies=$(cd "$1" && pwd)
output=$2
[[ ! -e "$output" ]] || { echo "Output already exists: $output" >&2; exit 65; }
build_root=$(mktemp -d /private/tmp/mcgl-glfw-native.XXXXXX)
trap 'rm -rf -- "$build_root"' EXIT
(cd "$dependencies" && shasum -a 256 -c "$source_root/third-party/glfw-source-sha256.txt")
tar -xzf "$dependencies/glfw-d9d6f0f1f967807ffade6598ea9a631ebaf37a56.tar.gz" \
    --strip-components=1 -C "$build_root"
graphics_framework=(-Wl,-dead_strip_dylibs)
if [[ ${3:-} == --metal-window ]]; then
    cp "$source_root/platform/native/macos/GlfwWithoutNSGL.m" "$build_root/src/nsgl_context.m"
else
    patch --batch --fuzz=0 -p1 -d "$build_root" < "$source_root/third-party/glfw-patches/0001-serialize-nsgl-update.patch"
    graphics_framework=(-framework OpenGL)
fi
patch --batch --fuzz=0 -p1 -d "$build_root" < "$source_root/third-party/glfw-patches/0002-cocoa-fullscreen-failure-notifications.patch"
# Source list and definitions match upstream src/CMakeLists.txt's shared Cocoa
# build. Xcode clang is already required for the launcher; no new build runtime.
objects=()
for unit in context.c init.c input.c monitor.c platform.c vulkan.c window.c \
    egl_context.c osmesa_context.c null_init.c null_monitor.c null_window.c \
    null_joystick.c macos_time.c posix_thread.c posix_module.c cocoa_init.m \
    cocoa_joystick.m cocoa_monitor.m cocoa_window.m nsgl_context.m; do
    object="$build_root/${unit%.*}.o"
    xcrun clang -arch arm64 -mmacosx-version-min=14.0 -O2 -fPIC -fvisibility=hidden \
        -D_GLFW_COCOA -D_GLFW_BUILD_DLL -I"$build_root/include" -I"$build_root/src" \
        -Wno-deprecated-declarations -c "$build_root/src/$unit" -o "$object"
    objects+=("$object")
done
xcrun clang -arch arm64 -mmacosx-version-min=14.0 -dynamiclib \
    -install_name @rpath/libglfw.dylib -compatibility_version 3.0 -current_version 3.5.1 \
    -framework Cocoa -framework IOKit -framework QuartzCore "${graphics_framework[@]}" \
    "${objects[@]}" -o "$output"
if [[ ${3:-} == --metal-window ]]; then
    echo 'GLFW_NATIVE_PASS 3.5.1 Cocoa window without NSGL'
else
    echo 'GLFW_NATIVE_PASS 3.5.1 d9d6f0f1f967807ffade6598ea9a631ebaf37a56 mcgl-nsgl-lock1 mcgl-native-fullscreen1'
fi
