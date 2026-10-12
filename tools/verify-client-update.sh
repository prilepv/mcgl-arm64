#!/bin/bash
# Offline tests only: supplied official files, no accounts or installed profiles.
set -euo pipefail
if [[ $# != 4 ]]; then
    echo 'Usage: verify-client-update.sh JAVA21_HOME TEST.app ORIGINAL_CLIENT_DIR UPDATED_MCGL.jar' >&2
    exit 64
fi
source_root=$(cd "$(dirname "$0")/.." && pwd)
jdk_root=$(cd "$1" && pwd)
app_root=$(cd "$2" && pwd)
original=$(cd "$3" && pwd)
updated=$4
resources="$app_root/Contents/Resources"
result=$(mktemp -d /private/tmp/mcgl-client-update-test.XXXXXX)
echo "Offline compatibility test outputs (retained): $result"
mkdir -p "$result/classes" "$result/modules"
cp="$result/classes:$resources/PatchTools:$resources/PatchTools/asm-debug-all.jar"
"$jdk_root/bin/javac" --release 8 -encoding UTF-8 -cp "$cp" -d "$result/classes" \
    "$source_root/tests/ClassBytePatchTest.java" "$source_root/tests/QuadSortTest.java" \
    "$source_root/tests/ClientCompatibilityTest.java" "$source_root/tests/ClientLinkageTest.java"
"$jdk_root/bin/java" -Xverify:all -cp "$cp" ClassBytePatchTest "$original/bin/mcgl.jar" "$updated"
"$jdk_root/bin/java" -Xverify:all -cp "$cp" ClientCompatibilityTest "$original/bin/mcgl.jar" "$updated"
"$jdk_root/bin/java" -cp "$cp" MCGLClientCompatibility normalize "$updated" "$result/normalized.jar"
for jar in "$original/bin/mcgl.jar" "$result/normalized.jar"; do
    for enabled in true false; do
        "$jdk_root/bin/java" -Xverify:all -Dmcgl.quad.sort="$enabled" -cp "$cp" QuadSortTest "$jar"
    done
done
swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$result/modules" -framework CryptoKit \
    "$source_root/native-launcher/MCGLInstaller.swift" \
    "$source_root/tests/InstallerCompatibilityTest.swift" -o "$result/installer-test"
"$result/installer-test" "$app_root" "$original" "$updated" "$result/installer"
for profile in original updated; do
    upstream="$original/bin/mcgl.jar"
    if [[ "$profile" == updated ]]; then upstream="$updated"; fi
    "$jdk_root/bin/java" -cp "$cp" ClientLinkageTest "$upstream" \
        "$result/installer/$profile/bin/mcgl.jar" "$original/Minecraft.jar" \
        "$resources/PortSupport/bin/lwjgl.jar" "$original/bin/gson-2.8.5.jar"
done
echo 'CLIENT_UPDATE_TEST_PASS (offline staging and bytecode; gameplay still requires manual PASS)'
