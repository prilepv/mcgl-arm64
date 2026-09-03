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
for executable in "$app/Contents/MacOS/$launcher" \
    "$resources/MCGL ARM64 Runtime.app/Contents/MacOS/MCGL ARM64 Runtime" \
    "$resources/java8-arm64/Home/bin/java" "$resources/java8-arm64/Home/bin/jar"; do
    [[ -x "$executable" ]] || { printf 'Missing executable permission/file: %s\n' "$executable" >&2; exit 1; }
done
for required in "$resources/java8-arm64/Home/jre/lib/server/libjvm.dylib" \
    "$resources/PortSupport/bin/lwjgl.jar" "$resources/PortSupport/bin/natives/liblwjgl.dylib"; do
    [[ -f "$required" ]] || { printf 'Missing bundled dependency: %s\n' "$required" >&2; exit 1; }
done
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
