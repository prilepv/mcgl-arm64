#!/bin/bash
# Read-only packaging checks. Not a Gatekeeper bypass or a notarization claim.
set -euo pipefail
if [[ $# != 1 || ! -d "$1/Contents" ]]; then
    printf 'Usage: %s /absolute/path/Application.app\n' "$0" >&2
    exit 64
fi
app=$(cd "$1" && pwd -P)
resources="$app/Contents/Resources"
expected_min=$(/usr/libexec/PlistBuddy -c 'Print :LSMinimumSystemVersion' "$app/Contents/Info.plist")
launcher=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$app/Contents/Info.plist")
if [[ -d "$resources/java21-arm64/Home" ]]; then
    java_home="$resources/java21-arm64/Home"
    java_runtime="$java_home"
    properties=$("$java_home/bin/java" -XshowSettings:properties -version 2>&1)
    [[ "$properties" == *'java.specification.version = 21'* && "$properties" == *'os.arch = aarch64'* ]] || exit 65
else
    # Historical 1.6.7 packages remain independently auditable.
    java_home="$resources/java8-arm64/Home"
    java_runtime="$java_home/jre"
fi
for executable in "$app/Contents/MacOS/$launcher" \
    "$resources/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" \
    "$java_home/bin/java" "$java_home/bin/jar"; do
    [[ -x "$executable" ]] || { printf 'Missing executable permission/file: %s\n' "$executable" >&2; exit 1; }
done
for required in "$java_runtime/lib/server/libjvm.dylib" \
    "$resources/PortSupport/bin/lwjgl.jar" "$resources/PortSupport/bin/natives/liblwjgl.dylib"; do
    [[ -f "$required" ]] || { printf 'Missing bundled dependency: %s\n' "$required" >&2; exit 1; }
done
if [[ -f "$resources/PortSupport/bin/natives/libglfw.dylib" ]]; then
    for required in "$resources/PortSupport/bin/natives/liblwjgl_opengl.dylib" \
        "$resources/PortSupport/bin/natives/libmcgl-mainthread.dylib" \
        "$resources/PatchTools/PatchMCGLLwjgl3.class" \
        "$resources/PatchTools/LWJGL3LinkageAudit.class" \
        "$resources/Licenses/LWJGL3-LICENSE.txt" "$resources/Licenses/GLFW-LICENSE.txt" \
        "$resources/Licenses/LWJGL2-COMPAT-LICENSE.txt" "$resources/Licenses/GLFW-BUILD.md"; do
        [[ -f "$required" ]] || { printf 'Missing GLFW dependency: %s\n' "$required" >&2; exit 1; }
    done
    [[ ! -e "$resources/PortSupport/bin/natives/libmcgl-window.dylib" && \
       ! -e "$resources/PortSupport/bin/lib/libmcglcocoa.dylib" ]] || exit 1
    lwjgl_version=$("$java_home/bin/java" -cp "$resources/PortSupport/bin/lwjgl.jar" org.lwjgl.Version 2>/dev/null)
    [[ "$lwjgl_version" == 3.4.3+4 ]] || { echo 'LWJGL3 version metadata mismatch' >&2; exit 1; }
    library_entries=$(unzip -Z1 "$resources/PortSupport/bin/lwjgl.jar")
    if [[ -f "$resources/PatchTools/render-commands.txt" ]] || \
            grep -q '^local/mcgl/render/RenderDevice.class$' <<< "$library_entries"; then
        for class in local/mcgl/render/RenderDevice local/mcgl/render/RenderContext \
            local/mcgl/render/LegacyRenderCommands local/mcgl/render/GuardedRenderCommands \
            local/mcgl/render/backend/CompatibilityBackend local/mcgl/render/backend/CompatibilityCommands \
            local/mcgl/render/legacy/GL11; do
            grep -q "^$class.class$" <<< "$library_entries"
        done
        [[ -f "$resources/PatchTools/PatchMCGLRenderer.class" && -f "$resources/PatchTools/RenderCommandSpec.class" ]]
        cmp <(unzip -p "$resources/PortSupport/bin/lwjgl.jar" META-INF/mcgl/render-commands.txt) \
            "$resources/PatchTools/render-commands.txt"
        if grep -q '^org/lwjgl/opengl/MCGLGL11\$Arrays.class$' <<< "$library_entries"; then
            echo 'Old pointer-lifetime owner remains outside renderer'; exit 1
        fi
        echo 'RENDER_PACKAGE_PASS renderer/typed-bridge/installer manifest paired'
        if grep -q '^local/mcgl/render/backend/Core41Backend.class$' <<< "$library_entries"; then
            for class in local/mcgl/render/RenderProfile local/mcgl/render/FrameCommands \
                local/mcgl/render/GuardedFrameCommands local/mcgl/render/backend/NativeFrameCommands \
                org/lwjgl/opengl/MCGLCoreDisplay; do
                grep -q "^$class.class$" <<< "$library_entries"
            done
            echo 'CORE41_PACKAGE_COMPONENTS_PRESENT (not a Core runtime/gameplay validation)'
            if grep -q '^local/mcgl/render/backend/NativeShaderPipeline.class$' <<< "$library_entries"; then
                for class in ShaderSources ShaderPipeline ShaderProgram ShaderUniform GuardedShaderPipeline ShaderLibrary; do
                    grep -q "^local/mcgl/render/$class.class$" <<< "$library_entries"
                done
                for shader in material.vert material.frag; do
                    grep -Fxq "local/mcgl/render/shaders/$shader" <<< "$library_entries"
                done
                echo 'GLSL_PACKAGE_COMPONENTS_PRESENT (shader compilation/rendering not validated)'
            fi
            if grep -Fxq 'local/mcgl/render/backend/NativeMeshPipeline.class' <<< "$library_entries"; then
                for class in VertexLayout VertexFormats IndexData MeshData Mesh MeshPipeline GuardedMeshPipeline; do
                    grep -Fxq "local/mcgl/render/$class.class" <<< "$library_entries"
                done
                echo 'GEOMETRY_PACKAGE_COMPONENTS_PRESENT (GPU geometry not validated by package audit)'
            fi
            if grep -Fxq 'local/mcgl/render/ChunkRenderer.class' <<< "$library_entries"; then
                for class in ChunkMaterial ChunkTessellator ChunkMeshBuilder ChunkMeshData ChunkFrustum; do
                    grep -Fxq "local/mcgl/render/$class.class" <<< "$library_entries"
                done
                [[ -f "$resources/PatchTools/PatchMCGLChunks.class" ]]
                echo 'CHUNK_PACKAGE_COMPONENTS_PRESENT (not an authenticated world validation)'
            fi
            if grep -Fxq 'local/mcgl/render/GameRenderCommands.class' <<< "$library_entries"; then
                for class in GameMatrices GameRenderState GameShaderSource GameEffect GameGeometry GameChunkHandle GuardedGameRenderCommands backend/GameRenderer backend/GameCommands backend/GameChunks backend/GameRasterState; do
                    grep -Fxq "local/mcgl/render/$class.class" <<< "$library_entries"
                done
                for shader in game.vert game.frag; do
                    grep -Fxq "local/mcgl/render/shaders/$shader" <<< "$library_entries"
                done
                [[ -f "$resources/PatchTools/PatchMCGLGame.class" ]]
                echo 'GAME_CORE_PACKAGE_COMPONENTS_PRESENT (gameplay still requires runtime validation)'
            fi
        fi
    fi
    if grep -q '^local/mcgl/platform/WindowBackend.class$' <<< "$library_entries"; then
        for class in local/mcgl/platform/Platform local/mcgl/platform/NativeLibraries \
            local/mcgl/platform/glfw/GlfwWindow local/mcgl/platform/glfw/GlfwInput \
            local/mcgl/platform/macos/MacOSMainThread; do
            grep -q "^$class.class$" <<< "$library_entries"
        done
        if grep -q '^local/mcgl/glfw/MainThread.class$' <<< "$library_entries"; then
            echo 'Obsolete unscoped JNI entry point remains'; exit 1
        fi
        native_symbols=$(nm -gU "$resources/PortSupport/bin/natives/libmcgl-mainthread.dylib")
        for method in invoke isMainThread lockContext unlockContext isContextLocked; do
            grep -q "_Java_local_mcgl_platform_macos_MacOSMainThread_$method$" <<< "$native_symbols"
        done
        if grep -q '0002-cocoa-fullscreen-failure-notifications' "$resources/Licenses/GLFW-BUILD.md"; then
            for method in attachFullscreen requestFullscreen fullscreenState detachFullscreen; do
                grep -q "_Java_local_mcgl_platform_macos_MacOSMainThread_$method$" <<< "$native_symbols"
            done
            for library in libglfw.dylib libmcgl-mainthread.dylib; do
                fullscreen_strings=$(strings "$resources/PortSupport/bin/natives/$library")
                for notification in MCGLWindowDidFailToEnterFullScreen MCGLWindowDidFailToExitFullScreen; do
                    grep -q "$notification" <<< "$fullscreen_strings"
                done
            done
            echo 'NATIVE_FULLSCREEN_PACKAGE_PASS JNI/GLFW failure notifications paired (runtime checked separately)'
        fi
        if grep -q '_Java_local_mcgl_glfw_MainThread_' <<< "$native_symbols"; then
            echo 'Old JNI exports remain in platform package'; exit 1
        fi
        echo 'PLATFORM_PACKAGE_PASS scoped Java/JNI entry points paired'
    fi
    if grep -Eq '^org/lwjgl/opengl/(MacOSX|ContextGL|DisplayImplementation)' <<< "$library_entries"; then
        echo 'Legacy window/context backend remains in GLFW package' >&2; exit 1
    fi
    for library in libglfw.dylib libmcgl-mainthread.dylib; do
        symbols=$(nm -u "$resources/PortSupport/bin/natives/$library")
        grep -q '_CGLLockContext' <<< "$symbols"
        grep -q '_CGLUnlockContext' <<< "$symbols"
    done
    echo 'GLFW_PACKAGE_PASS LWJGL=3.4.3+4 legacy-window-backend=absent CGL-lock=both-sides'
elif [[ -f "$resources/PortSupport/bin/natives/libmcgl-window.dylib" ]]; then
    for required in "$resources/PortSupport/bin/natives/liblwjgl_opengl.dylib" \
        "$resources/PatchTools/PatchMCGLLwjgl3.class" \
        "$resources/PatchTools/LWJGL3LinkageAudit.class" \
        "$resources/Licenses/LWJGL3-LICENSE.txt" \
        "$resources/Licenses/LWJGL2-WINDOW-LICENSE.txt"; do
        [[ -f "$required" ]] || { printf 'Missing LWJGL3 dependency: %s\n' "$required" >&2; exit 1; }
    done
    lwjgl_version=$("$java_home/bin/java" -cp "$resources/PortSupport/bin/lwjgl.jar" org.lwjgl.Version 2>/dev/null)
    [[ "$lwjgl_version" == 3.4.3+4 ]] || { echo 'LWJGL3 version metadata mismatch' >&2; exit 1; }
    window_symbols=$(nm -gU "$resources/PortSupport/bin/natives/libmcgl-window.dylib")
    if grep -Eq 'Java_org_lwjgl_(openal|opencl|opengl_(GL[0-9]|ARB|EXT|NV|APPLE))' <<< "$window_symbols"; then
        echo 'Legacy GL/AL binding exported by the window module' >&2; exit 1
    fi
    echo 'LWJGL3_PACKAGE_PASS version=3.4.3+4 legacy-generated-bindings=absent'
fi
codesign --verify --deep --strict "$app"
count=0
while IFS= read -r -d '' binary; do
    kind=$(file -b "$binary")
    [[ "$kind" == *Mach-O* ]] || continue
    [[ "$kind" == *arm64* ]] || { printf 'Non-ARM64 code: %s\n' "$binary" >&2; exit 1; }
    count=$((count+1))
    versions=$(otool -l "$binary" | awk '
        /LC_BUILD_VERSION/{mode=1} mode==1 && $1=="minos"{print $2;mode=0}
        /LC_VERSION_MIN_MACOSX/{mode=2} mode==2 && $1=="version"{print $2;mode=0}')
    [[ -n "$versions" ]] || { printf 'No deployment target: %s\n' "$binary" >&2; exit 1; }
    while IFS= read -r version; do
        awk -v actual="$version" -v declared="$expected_min" 'BEGIN {
            split(actual,a,"."); split(declared,d,".");
            for(i=1;i<=3;i++){if(a[i]+0>d[i]+0)exit 1;if(a[i]+0<d[i]+0)exit 0}
        }' || { printf 'Deployment mismatch: %s needs %s; declared %s\n' "$binary" "$version" "$expected_min" >&2; exit 1; }
    done <<< "$versions"
    # LC_ID_DYLIB is the library's own name, not a load dependency.
    dependencies=$(otool -l "$binary" | awk '
        $1=="cmd" {p=($2=="LC_LOAD_DYLIB" || $2=="LC_LOAD_WEAK_DYLIB" ||
                      $2=="LC_REEXPORT_DYLIB" || $2=="LC_LOAD_UPWARD_DYLIB")}
        p && $1=="name" {sub(/^[[:space:]]*name /,"");sub(/ [(]offset.*/,"");print;p=0}')
    while IFS= read -r dependency; do
        case "$dependency" in
            /System/Library/*|/usr/lib/*) ;;
            /*) printf 'External absolute dependency: %s -> %s\n' "$binary" "$dependency" >&2; exit 1 ;;
        esac
    done <<< "$dependencies"
    search_paths=$(otool -l "$binary" | awk '/LC_RPATH/{p=1} p && $1=="path"{sub(/^[[:space:]]*path /,"");sub(/ [(]offset.*/,"");print;p=0}')
    while IFS= read -r search_path; do
        case "$search_path" in
            /System/Library/*|/usr/lib/*) ;;
            /*) printf 'External absolute rpath: %s -> %s\n' "$binary" "$search_path" >&2; exit 1 ;;
        esac
    done <<< "$search_paths"
done < <(rg --files --hidden -0 "$app")
printf 'PACKAGING_CHECK_PASS ARM64 Mach-O files=%d deployment<=%s signature=valid; Gatekeeper/notarization and target-Mac runtime not certified\n' "$count" "$expected_min"
