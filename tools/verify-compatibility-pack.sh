#!/bin/bash
# Explicit local fixtures only; no real credentials or installed-profile writes.
set -euo pipefail
[[ $# == 5 ]] || { echo 'usage: JAVA21_HOME TEST.app ORIGINAL_CLIENT_DIR UPDATED_MCGL.jar SIGNED_PACK' >&2; exit 64; }
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk=$1
resources="$2/Contents/Resources"
asm="$resources/PatchTools/asm-debug-all.jar"
out=$(mktemp -d /private/tmp/mcgl-pack-check.XXXXXX)
echo "Compatibility test outputs (retained): $out"
mkdir -p "$out/classes" "$out/modules"
"$jdk/bin/javac" --release 8 -encoding UTF-8 -cp "$asm" -d "$out/classes" \
    "$source_root/tools/MCGLCompatibilityPack.java" "$source_root/tools/MCGLBytecodeFingerprint.java" \
    "$source_root/tools/MCGLClientCompatibility.java" "$source_root/tests/CompatibilityPackTest.java" \
    "$source_root/tests/ClientCompatibilityTest.java" "$source_root/tests/ClientLinkageTest.java"
ditto "$source_root/tools/client-profiles" "$out/classes/client-profiles"
sha=$(shasum -a 256 "$4" | awk '{print $1}')
"$jdk/bin/java" -cp "$out/classes:$asm" CompatibilityPackTest "$5" "$sha"
"$jdk/bin/java" -Dmcgl.compat.pack="$5" -Dmcgl.compat.official="$4" -cp "$out/classes:$asm" \
    ClientCompatibilityTest "$3/bin/mcgl.jar" "$4"
for test in CompatibilityDeliveryTest SavedClientTest InstallerCompatibilityTest; do
    swiftc -parse-as-library -swift-version 5 -module-cache-path "$out/modules" \
        "$source_root/native-launcher/MCGLInstaller.swift" "$source_root/tests/$test.swift" -o "$out/$test"
done
"$out/CompatibilityDeliveryTest" "$5" "$sha"
"$out/SavedClientTest"
"$out/InstallerCompatibilityTest" "$2" "$3" "$4" "$out/staging" "$5"
"$jdk/bin/java" -cp "$out/classes:$asm" ClientLinkageTest "$4" "$out/staging/updated/bin/mcgl.jar" \
    "$3/Minecraft.jar" "$resources/PortSupport/bin/lwjgl.jar" "$3/bin/gson-2.8.5.jar"
echo 'SIGNED_COMPATIBILITY_CHECK_PASS'
