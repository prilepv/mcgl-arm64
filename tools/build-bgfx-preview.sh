#!/bin/bash
set -euo pipefail
[[ $# == 4 && "$2" == /* && ! -e "$2" && ! -L "$2" ]] || {
    echo 'Usage: build-bgfx-preview.sh BASE.app NEW-OUTPUT-DIRECTORY BGFX_DEPENDENCIES GLFW_DEPENDENCIES' >&2
    exit 64
}
root=$(cd "$(dirname "$0")/.." && pwd -P)
base=$(cd "$1" && pwd -P)
output="$2"
deps=$(cd "$3" && pwd -P)
resources="$base/Contents/Resources"
unexpected_link=$(find "$resources/PortSupport" -type l -print -quit)
if [[ -n "$unexpected_link" ]]; then
    printf 'ARM64 components contain a symbolic link: %s\n' "$unexpected_link" >&2
    exit 1
fi
jdk="$resources/java21-arm64/Home"
lwjgl="$resources/PortSupport/bin/lwjgl.jar"
bindings="$deps/lwjgl-bgfx-3.4.3.jar"
work=$(mktemp -d /private/tmp/mcgl-bgfx-build.XXXXXX)
trap 'rm -rf -- "$work"' EXIT
(
    cd "$deps"
    shasum -a 256 -c <<'CHECKSUMS'
dc6f1df4d8d4310f31b89cdb073c1bfcdb83fe81ec7ec66e80f57453bdc948e5  lwjgl-bgfx-3.4.3.jar
a80c812e564bdeda6e98745c1488e6a652dd911a2da921dcea1fa29ce8b89496  lwjgl-bgfx-3.4.3-natives-macos-arm64.jar
50a6284bfefc9f7ec8ae39e835d599e72650b1fd290ad541d5784b869d7662b3  shaderc
5ad79a2ff5d1b1fd1f22d986f2b0b5dc49f89ffd5ce18e57966b6ab894094508  bgfx_shader.sh
CHECKSUMS
)
bash "$root/tools/build-render-support.sh" "$jdk" "$lwjgl" "$resources/PatchTools/asm-debug-all.jar" "$work/render"
classes="$work/render/classes"
"$jdk/bin/javac" --release 8 -encoding UTF-8 -cp "$classes:$lwjgl:$bindings" -d "$classes" \
    "$root/renderer/bgfx/src/local/mcgl/render/backend/"*.java
unzip -q "$bindings" 'org/lwjgl/bgfx/*' -d "$classes"
app="$work/output/Minecraft Galaxy ARM64.app"
ditto "$base" "$app"
packaged="$app/Contents/Resources"
bgfx="$packaged/Bgfx"
mkdir -p "$bgfx" "$packaged/Licenses"
cp "$root/third-party/BGFX-LICENSE.txt" "$packaged/Licenses/BGFX-LICENSE.txt"
unzip -p "$deps/lwjgl-bgfx-3.4.3-natives-macos-arm64.jar" macos/arm64/org/lwjgl/bgfx/libbgfx.dylib > "$bgfx/libbgfx.dylib"
shaders="$root/renderer/bgfx/shaders"
compile_shader() {
    "$deps/shaderc" -f "$shaders/$1.sc" -o "$bgfx/$1.bin" --type "$2" --platform osx -p metal \
        --varyingdef "$shaders/varying.def.sc" -i "$deps"
}
compile_shader vs_terrain vertex
compile_shader fs_terrain fragment
compile_shader fs_terrain_offset fragment
xcrun clang -arch arm64 -mmacosx-version-min=14.0 -fobjc-arc -dynamiclib \
    -I"$jdk/include" -I"$jdk/include/darwin" -framework Cocoa -framework QuartzCore \
    "$root/renderer/bgfx/BgfxWindow.m" -o "$bgfx/bgfx-window.dylib"
bash "$root/tools/build-glfw-native.sh" "$4" "$work/libglfw.dylib" --metal-window
mv "$work/libglfw.dylib" "$bgfx/libglfw.dylib"
xcrun clang -arch arm64 -mmacosx-version-min=14.0 -DMCGL_METAL_WINDOW_ONLY -dynamiclib \
    -I"$jdk/include" -I"$jdk/include/darwin" -framework Cocoa \
    -install_name @rpath/libmcgl-mainthread.dylib "$root/platform/native/macos/MainThread.m" \
    -o "$bgfx/libmcgl-mainthread.dylib"
runtime="$packaged/MCGL ARM64 Runtime.app"
xcrun clang -x objective-c -arch arm64 -mmacosx-version-min=14.0 -Wno-deprecated-declarations \
    -I"$jdk/include" -I"$jdk/include/darwin" -framework Cocoa \
    "$root/arm64-runtime/MCGLARM64Runtime.c" -o "$runtime/Contents/MacOS/MCGL ARM64 Runtime"
swiftc -swift-version 5 -target arm64-apple-macosx14.0 -module-cache-path "$work/modules" -framework Cocoa -framework CryptoKit \
    "$root/native-launcher/"MCGL*.swift -o "$app/Contents/MacOS/MCGL ARM64 Launcher"
plist="$app/Contents/Info.plist"
version=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$plist")
[[ "$version" =~ ^[0-9]+$ ]]
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $((version + 1))" "$plist"
# The same game command producers retain their normal OpenGL implementation in the base JAR.
# Only the separate Metal JAR maps their binding calls to the CPU game state.
"$jdk/bin/javac" --release 8 -encoding UTF-8 -cp "$classes:$lwjgl" -d "$classes" \
    "$root/platform/src/local/mcgl/platform/glfw/GlfwWindow.java" \
    "$root/platform/src/local/mcgl/platform/NativeLibraries.java" \
    "$root/platform/src/local/mcgl/platform/macos/MacOSHostServices.java"
"$jdk/bin/jar" uf "$packaged/PortSupport/bin/lwjgl.jar" -C "$classes" local -C "$classes" org/lwjgl/bgfx -C "$classes" META-INF/mcgl
cp "$packaged/PortSupport/bin/lwjgl.jar" "$bgfx/lwjgl.jar"
asm="$resources/PatchTools/asm-debug-all.jar"
"$jdk/bin/javac" --release 8 -encoding UTF-8 -cp "$asm:$packaged/PatchTools" \
    -d "$packaged/PatchTools" "$root/tools/PatchMCGLGame.java"
bash "$root/tools/build-local-compatibility.sh" "$jdk" "$packaged/PatchTools"
"$jdk/bin/javac" --release 8 -cp "$asm" -d "$work/render/tools" "$root/tools/PrepareBgfxGameBridge.java"
"$jdk/bin/java" -cp "$work/render/tools:$asm" PrepareBgfxGameBridge "$classes"
"$jdk/bin/jar" uf "$bgfx/lwjgl.jar" -C "$classes" local/mcgl/render/backend
if otool -L "$runtime/Contents/MacOS/MCGL ARM64 Runtime" "$bgfx/"*.dylib | grep -Fq 'OpenGL.framework'; then
    echo 'Independent Metal binaries link OpenGL' >&2
    exit 1
fi
for binary in "$bgfx/"*.dylib "$runtime" "$app"; do codesign --force --sign - "$binary"; done
codesign --verify --deep --strict "$app"
test ! -e "$output" && test ! -L "$output"
mv "$work/output" "$output"
echo "BGFX_PREVIEW_APP $output/Minecraft Galaxy ARM64.app"
