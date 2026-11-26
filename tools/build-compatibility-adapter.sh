#!/bin/bash
set -euo pipefail
[[ $# == 5 && "$5" == /*.adapter && ! -e "$5" ]] || {
    echo 'usage: PRIVATE_KEY APP REVISION MINIMUM_BUILD /absolute/NEW.adapter' >&2
    exit 64
}
root=$(cd "$(dirname "$0")/.." && pwd -P)
resources="$2/Contents/Resources"
work=$(mktemp -d /private/tmp/mcgl-adapter-build.XXXXXX)
trap 'rm -rf "$work"' EXIT
python3 - "$resources/PatchTools" "$work/tools.jar" <<'PY'
from pathlib import Path
import sys
import zipfile

root = Path(sys.argv[1])
with zipfile.ZipFile(sys.argv[2], 'x', compression=zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise SystemExit(f'Symbolic link in patch tools: {path}')
        if path.is_file() and (path.suffix == '.class' or
                path.relative_to(root).as_posix() in ('render-commands.txt', 'compatibility-guards.txt') or
                path.relative_to(root).as_posix().startswith('client-profiles/') and path.suffix == '.tsv'):
            archive.writestr(path.relative_to(root).as_posix(), path.read_bytes())
PY
swiftc -module-cache-path "$work/modules" "$root/native-launcher/MCGLInstaller.swift" \
    "$root/native-launcher/MCGLCompatibilityAdapter.swift" "$root/tools/BuildCompatibilityAdapter.swift" -o "$work/sign"
"$work/sign" "$1" "$2" "$3" "$4" "$work/tools.jar" "$5"
