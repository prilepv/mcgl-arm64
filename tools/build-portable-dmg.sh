#!/bin/bash
# Package an existing release without rebuilding, modifying or re-signing it.
# A DMG is a distribution container, not a Gatekeeper bypass.
set -euo pipefail

if [[ $# != 2 || "$2" != /*.dmg ]]; then
    printf 'Usage: %s release-directory /absolute/path/output.dmg\n' "$0" >&2
    exit 64
fi

release_dir=$(cd "$1" && pwd -P)
source_app="$release_dir/Minecraft Galaxy ARM64.app"
output_dmg="$2"
script_dir=$(cd "$(dirname "$0")" && pwd -P)
install_guide="$script_dir/../bootstrap-release/DMG-INSTALL.txt"

for required in "$source_app/Contents/Info.plist" "$release_dir/README.md" \
    "$release_dir/THIRD-PARTY-NOTICES.md" "$install_guide"; do
    [[ -f "$required" ]] || { printf 'Missing input: %s\n' "$required" >&2; exit 1; }
done
if [[ -e "$output_dmg" || -L "$output_dmg" || \
      -e "$output_dmg.sha256" || -L "$output_dmg.sha256" ]]; then
    printf 'Refusing to overwrite an existing release: %s\n' "$output_dmg" >&2
    exit 1
fi
/usr/bin/codesign --verify --deep --strict "$source_app"
version=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' \
    "$source_app/Contents/Info.plist")

# Only a fresh staging directory is written. Keep it for inspection on failure;
# no application or existing release is ever deleted by this packaging helper.
staging_root=$(/usr/bin/mktemp -d /private/tmp/mcgl-dmg.XXXXXX)
staging_dir="$staging_root/contents"
/bin/mkdir -p "$staging_dir/Документация" "$(dirname "$output_dmg")"
/usr/bin/ditto "$source_app" "$staging_dir/Minecraft Galaxy ARM64.app"
/bin/ln -s /Applications "$staging_dir/Applications"
/usr/bin/ditto "$install_guide" "$staging_dir/Как установить.txt"
/usr/bin/ditto "$release_dir/README.md" "$staging_dir/Документация/README.md"
/usr/bin/ditto "$release_dir/THIRD-PARTY-NOTICES.md" \
    "$staging_dir/Документация/THIRD-PARTY-NOTICES.md"
/usr/bin/codesign --verify --deep --strict "$staging_dir/Minecraft Galaxy ARM64.app"

/usr/bin/hdiutil create -volname "Minecraft Galaxy ARM64 $version" \
    -srcfolder "$staging_dir" -fs HFS+ -format UDZO \
    -imagekey zlib-level=9 "$output_dmg"
/usr/bin/hdiutil verify "$output_dmg"
(
    cd "$(dirname "$output_dmg")"
    set -o noclobber
    /usr/bin/shasum -a 256 "$(basename "$output_dmg")" > "$(basename "$output_dmg").sha256"
)
printf 'DMG_CREATED %s\nStaging retained for inspection: %s\n' "$output_dmg" "$staging_root"
printf 'Application unchanged; Developer ID/notarization has not been added.\n'
