#!/bin/bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd -P)
jdk=$1
tools=$2
"$jdk/bin/javac" -encoding UTF-8 -cp "$tools:$tools/asm-debug-all.jar" -d "$tools" \
    "$root/tools/MCGLCompatibilityPack.java" "$root/tools/CreateCompatibilityPack.java" \
    "$root/tools/compatibility/"*.java "$root/tests/ClientLinkageTest.java"
python3 - "$root/tools" "$tools/compatibility-guards.txt" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
patchers = ("Lightmap", "ChunkVbo", "QuadSort", "Chunks", "Game", "Transparency")
guards = {int(value) for name in patchers for value in
          re.findall(r"\b([0-9]{5,})L\b", (root / f"PatchMCGL{name}.java").read_text())}
Path(sys.argv[2]).write_text("".join(f"{guard}\n" for guard in sorted(guards)))
PY
