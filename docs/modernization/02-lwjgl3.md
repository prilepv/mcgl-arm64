# Milestone 2 — LWJGL 3 bindings

## Scope and status

**PASS — local 1.6.9 milestone, verified on 8 September 2026.**
This is local work, not published or installed over the user's app.
Starts from the verified [Java 21 / 1.6.8 baseline](01-java21.md), which starts
from the original 1.6.7 without the discarded hotpaths experiment.
The isolated gameplay candidate still carried 1.6.8 metadata. The final
milestone is packaged as 1.6.9; its entire `PortSupport` directory is
byte-for-byte identical to the gameplay candidate's.

Desktop OpenGL, OpenAL and core bindings move to **LWJGL 3.4.3 build 4**.
The existing Cocoa window/input backend remains temporarily. GLFW/window
modernization is a separate next milestone. Native Spaces-friendly fullscreen
is explicitly deferred. Zulu 21, legacy OpenGL 2.1, fixed-function rendering,
display lists, existing VBO/APPLE VAO fallback and other performance patches,
gameplay, network code and native protocol components are preserved.
No Core-profile request, new renderer, shader rewrite or speedup is claimed.

## Implementation

- The original MCGLClassLoader has a fixed JAR URL list. Core/OpenGL/OpenAL
  modules and compatibility classes share `bin/lwjgl.jar`; the classloader is
  not rewritten. The merge rejects conflicting entries, preserves the core
  manifest and multi-release classes, and omits separate JPMS module descriptors
  for this unnamed-classpath aggregate. LWJGL reports the real `3.4.3+4`.
- Official ARM64 `liblwjgl.dylib` and `liblwjgl_opengl.dylib` are separate from
  the newly built `libmcgl-window.dylib`. The latter compiles existing Cocoa
  and common window support only: **no generated LWJGL 2 GL/AL/CL bindings**.
  Symbol checks enforce this. Merely renaming the old complete dylib would be
  unsafe because the old and new libraries export overlapping JNI names.
- `GLContext` attaches LWJGL 3 capabilities to the existing current Cocoa
  context. Legacy flags reflect actual capabilities. Resize preserves their
  identity; context recreation produces a new identity.
- Small GL11/GL13/ARB facades retain used legacy overloads, client-array buffer
  lifetimes, shared ARB/core client texture-unit tracking and the existing
  display-list diagnostic hook. Calls delegate directly to LWJGL 3, without
  per-call reflection. Client attribute push/pop respects the vertex-array mask.
- OpenAL retains PaulsCode's process-wide context and old 44100 Hz / 60 Hz
  context attributes. The binding explicitly loads the **unchanged OpenAL Soft
  1.25.2** native; the OpenAL Soft native offered by LWJGL is not substituted.
- Legacy PointerBuffer/BufferUtils names are isolated from LWJGL 3's classes.
  Existing window/input scaffolding and GLU/math/WAV utilities remain. This is
  not a claim that every LWJGL 2 source class has already been removed.
- `PatchMCGLLwjgl3` runs after the existing game transforms. It changes binding
  references and an exact signature map, leaving unrelated class bytes and
  resource bytes intact. A narrow adapter avoids ASM 4's automatic local-slot
  renumbering; game instructions, branches and local indexes are not changed.
- The installer refreshes original `mcgl.jar` and `lwjgl_util.jar`, transforms
  them in staging, then audits binding signatures before applying files.
  Marker: `bootstrap lwjgl3-3.4.3-1`. Hash differences in adapted JARs do not
  trigger repeated downloads. Migrating from the 1.6.5–1.6.8 marker does not
  reset a user's later `alphaSort` preference.

## Provenance and validation

Official [LWJGL 3.4.3 release](https://github.com/LWJGL/lwjgl3/releases/tag/3.4.3).
Maven Central artifacts: `org.lwjgl`, version `3.4.3`; modules `lwjgl`,
`lwjgl-opengl`, `lwjgl-openal`; native classifiers `natives-macos-arm64` for
core and OpenGL. Vendor SHA-1 sidecars were compared after download. The build
checks all five pinned [SHA-256 values](../../third-party/lwjgl3-sha256.txt).
LWJGL licenses are included in `Contents/Resources/Licenses`. No vendor binaries,
original client JARs, profile data or local diagnostics are committed.

- Original client audit found 243 unique LWJGL method references and 10
  capability fields. Excluding GLU/math internals, the focused binding audit
  checks **250 references**, with **0 missing**. Utility JAR: **53**, **0 missing**.
- Original client/utility patch regression: **3281 unchanged entries, 257
  adapted class entries**; second application changes **0** classes. Checks
  inventory, resources, unrelated classes, control flow/opcodes/local indexes
  and refusal to overwrite an existing output. The already performance-patched
  client was also exercised.
- Existing `verify-source.sh` passes on Java 21 with retained 1.6.8 legacy API
  build inputs. This validates the unchanged game patches before adaptation;
  it does not claim old generated LWJGL 2 tests compile against the new adapter.
  The final 1.6.9 sources passed the full **46 test-process invocations**
  (20 Swift and 26 Java; the patch preparation pipeline is not counted as a
  separate test). Existing game-test expectations remain intact.
- A 20-second real client/menu smoke initialized LWJGL 3, unchanged OpenAL Soft,
  VBO and APPLE VAO. It ended via the planned timeout (143), not a clean-exit claim.
- Initial comparative GPU tests passed **34 assertions per backend** and exited
  **0**, without timeout. Two window/context and audio-context lifetimes,
  fixed-function rendering, core/ARB shaders, FBO attachment, client arrays,
  VBO state restoration, resize and audio upload were exercised.
- Fixed-function 64×64 blend/display-list pixels matched exactly on both
  backends, across both lifetimes: SHA-256
  `ea8e64bb361ad88ea7d0fda87a1e104072345a1ca7cb0745bb309d05bad76e76`.
  This does not prove every game scene or shader matches.
- Prepared-input APP/ZIP builds pass: **86 ARM64 Mach-O files**, deployment
  <= macOS 14, strict ad-hoc signature valid. Not Developer ID/notarized.
- Fresh isolated official-client installation passed the new staged transforms
  and linker audit. No account was needed for that installation.
- The expanded comparison passes **42 assertions per backend**, including both
  texture-pointer overloads, ARB/core client texture-unit restoration, a
  pixel-store-only attribute mask, color/normal pointers and OpenAL access from
  another thread. Both backends exit **0**, without timeout, and fixture hashes
  still match with the full multi-release/version-preserving candidate JAR.
- Reopening the isolated launcher reports no client update needed: repeated
  installation does not download or reapply the adapted JARs.
- The user entered a real server using credentials supplied only in the test
  launcher's UI. Inventory, keyboard, mouse, sound, resize and the existing
  fullscreen toggle all worked. FPS was roughly unchanged by the user's
  observation; this is not a controlled performance A/B.
- The connected process loaded the bundled Java 21 and LWJGL 3 core/OpenGL
  natives, the separate Cocoa-only native and unchanged OpenAL Soft.
  No graphics exceptions were found in the test log. After an ordinary
  disconnect, both `Client read` and `Client write` threads were absent from
  the live JVM while the menu remained open.
- The user then closed the game normally. The log recorded animation shutdown,
  `Stopping!`, SoundSystem shutdown and completion of the game process; its
  PID was no longer present. The launcher remained separate. No forced kill
  or timeout was used for this gameplay session.
- An account-free upgrade fixture using legacy 1.6.8 support and the old
  1.6.5 port marker upgraded successfully through the final app's tools.
  Both adapted JARs passed the installer audit; `alphaSort:false` was retained.
  A second run reported no update needed.
- The final 1.6.9 app repeated the **42-assertion-per-backend** live comparison
  with identical fixture pixels and two clean zero-status, non-timeout exits.
  The APP again passed the **86 ARM64 Mach-O** packaging/signature audit.
  ZIP integrity and both SHA-256 sidecars passed; `hdiutil verify` accepted
  the DMG. App and nested runtime metadata are 1.6.9 / build 169.

Final local containers (not uploaded):

- `Minecraft-Galaxy-ARM64-Bootstrap-1.6.9.dmg` — SHA-256
  `8453f6e9bd02af641212d277e8842e44ba7ec427335b3df16ac071c7ae037009`.
- `Minecraft-Galaxy-ARM64-Bootstrap-1.6.9.zip` — SHA-256
  `1b43990d8e5a52676cf070b758f82fdebe7a786ed45668f580b5903f22b29a23`.

Failed development attempts are retained in local logs, not counted as passes:
mixed sealed JARs during the initial audit; missing expanded ASM frames; default
ASM remapper local-slot renumbering; a conflicting old `GLChecks` caught by the
strict merge; and the first public GPU harness using a temporary FIFO path
outside the production runtime's accepted `/private/tmp/mcgl-` prefix. The harness
path was corrected; production FIFO validation was not relaxed. The first
hand-assembled probe lacked upstream version metadata and printed `snapshot`;
the proper merge now preserves upstream metadata and tests exact `3.4.3+4`.

## Running checks

Keep a verified 1.6.8 app as legacy API input:

```sh
bash tools/verify-source.sh "$JAVA21_HOME" "$LEGACY_168_APP" \
  "$ORIGINAL_MCGL_JAR" "$ORIGINAL_LAUNCHER_JAR"
bash tools/verify-lwjgl3.sh "$JAVA21_HOME" "$LEGACY_168_APP" \
  "$LWJGL3_APP" "$ORIGINAL_MCGL_JAR"
```

Append `--live` to the second command for bounded real GPU/window/audio tests.
They open test windows, use no account/network and retain local temporary outputs.
Do not publish original-client artifacts from those directories.

## Deferred work / limits

No long-session soak, controlled world-performance A/B, optional Discord fix,
GLFW or native Spaces fullscreen is claimed. Java 21's documented rare
Thread.stop watchdog limitation remains unchanged.
