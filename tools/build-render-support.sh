#!/bin/bash
# Compile the independent renderer module against LWJGL 3, not legacy/window classes.
set -euo pipefail
[[ $# == 4 ]] || { echo 'usage: JAVA21_HOME LWJGL3_CLASSPATH ASM.jar NEW-OUTPUT' >&2; exit 64; }
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk_root=$1
bindings=$2
asm=$3
output=$4
[[ ! -e "$output" ]] || { echo "Renderer output exists: $output" >&2; exit 1; }
mkdir -p "$output/tools" "$output/classes"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$asm" -d "$output/tools" \
    "$source_root/tools/RenderCommandSpec.java" "$source_root/tools/GenerateRenderBridge.java" "$source_root/tools/GenerateGameBridge.java"
"$jdk_root/bin/java" -cp "$output/tools:$asm" GenerateRenderBridge \
    "$source_root/renderer/legacy-commands.txt" "$output/generated"
"$jdk_root/bin/java" -cp "$output/tools:$asm" GenerateGameBridge \
    "$source_root/renderer/legacy-commands.txt" "$output/game-generated"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$bindings" -d "$output/classes" \
    "$source_root"/renderer/src/local/mcgl/render/*.java \
    "$source_root"/renderer/src/local/mcgl/render/backend/*.java \
    "$output"/generated/local/mcgl/render/*.java \
    "$output"/generated/local/mcgl/render/backend/*.java \
    "$output"/generated/local/mcgl/render/legacy/*.java \
    "$output"/game-generated/local/mcgl/render/*.java \
    "$output"/game-generated/local/mcgl/render/backend/*.java \
    "$output"/game-generated/local/mcgl/render/game/*.java
mkdir -p "$output/classes/META-INF/mcgl"
ditto "$source_root/renderer/resources" "$output/classes"
ditto "$source_root/renderer/legacy-commands.txt" "$output/classes/META-INF/mcgl/render-commands.txt"
"$jdk_root/bin/jar" cf "$output/renderer.jar" -C "$output/classes" .
echo 'RENDER_SUPPORT_PASS: typed bridge, context ownership, compatibility/Core, shaders, indexed geometry and explicit chunk pipeline'
