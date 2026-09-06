#!/bin/bash
# UI-only 1.6.7 candidate: reuse a verified 1.6.6 runtime without rebuilding it.
# For a full build from prepared dependencies use build-bootstrap-release.sh.
set -euo pipefail
if [[ $# != 2 || "$2" != /* ]]; then
    echo 'Usage: bash tools/build-launcher-candidate.sh BASELINE-1.6.6.app NEW-OUTPUT-DIRECTORY' >&2
    exit 64
fi
source_root=$(cd "$(dirname "$0")/.." && pwd)
baseline=$(cd "$1" && pwd -P)
output="$2"
[[ ! -e "$output" && ! -L "$output" ]] || { echo 'Output already exists; refusing overwrite.' >&2; exit 1; }
[[ $(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$baseline/Contents/Info.plist") == 1.6.6 ]] || exit 65
[[ $(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$source_root/native-launcher/Info.plist") == 1.6.7 ]] || exit 65
codesign --verify --deep --strict "$baseline"
release="$output/Minecraft-Galaxy-ARM64-Bootstrap-1.6.7"
app="$release/Minecraft Galaxy ARM64.app"
mkdir -p "$release"
ditto "$baseline" "$app"
build_tmp=$(mktemp -d /private/tmp/mcgl-launcher-candidate.XXXXXX)
swiftc -swift-version 5 -target arm64-apple-macosx14.0 \
    -module-cache-path "$build_tmp/modules" -framework Cocoa -framework CryptoKit \
    "$source_root/native-launcher/MCGLNativeLauncher.swift" \
    "$source_root/native-launcher/MCGLLauncherPreferences.swift" \
    "$source_root/native-launcher/MCGLAccounts.swift" \
    "$source_root/native-launcher/MCGLPasswordStore.swift" \
    "$source_root/native-launcher/MCGLAccountCard.swift" \
    "$source_root/native-launcher/MCGLAccountsDocumentView.swift" \
    "$source_root/native-launcher/MCGLInstaller.swift" \
    "$source_root/native-launcher/MCGLLauncherUpdater.swift" \
    -o "$app/Contents/MacOS/MCGL ARM64 Launcher"
ditto "$source_root/native-launcher/Info.plist" "$app/Contents/Info.plist"
ditto "$source_root/native-launcher/Assets/Professions" "$app/Contents/Resources/Professions"
ditto "$source_root/bootstrap-release/README.md" "$release/README.md"
ditto "$source_root/bootstrap-release/THIRD-PARTY-NOTICES.md" "$release/THIRD-PARTY-NOTICES.md"
# Only the outer application changed. Nested code keeps its original signature.
codesign --force --sign - "$app"
codesign --verify --deep --strict "$app"
for item in java8-arm64 'MCGL ARM64 Runtime.app' PortSupport PatchTools app.icns app-icon-symbol.png launcher-background.png; do
    diff -qr "$baseline/Contents/Resources/$item" "$app/Contents/Resources/$item"
done
echo 'GAME_RESOURCES_UNCHANGED Java/runtime/LWJGL/patches/icon/background match baseline byte-for-byte'
bash "$source_root/tools/audit-portable-release.sh" "$app"
echo "CANDIDATE_APP $app"
echo "Build cache retained: $build_tmp"
