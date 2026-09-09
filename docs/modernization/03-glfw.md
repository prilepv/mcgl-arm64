# Milestone 3 — GLFW window and input / 1.6.10

## Scope and status

**PASS — local 1.6.10 milestone, verified on 8 September 2026.**
This work is not published or installed over the user's application/profile.

Starts from verified [1.6.9](02-lwjgl3.md), without the discarded hotpaths
experiment. This milestone is **1.6.10**, not 1.7.0. Version 1.7.0 is reserved
for completion of the modernization series.

GLFW **3.5.1** now owns the window, OpenGL context, keyboard/mouse callbacks
and cursors. Java 21, LWJGL 3.4.3 build 4, OpenAL Soft 1.25.2, legacy OpenGL 2.1,
fixed-function/display-list rendering and the existing VBO/APPLE VAO path remain.
No new renderer, network/protocol changes or performance improvement is claimed.
Native macOS Spaces fullscreen remains deferred.

## Implementation

- The native runtime still creates Java on the first macOS thread and runs
  its AppKit loop there. The game stays on the existing dedicated worker.
  A JNI bridge executes GLFW window/event operations on the first thread,
  propagating exceptions back to the caller. Context binding and rendering
  stay on the render owner.
- `Display` is now a GLFW implementation. Old Cocoa window/context Java
  classes, `libmcgl-window.dylib` and the foreground `libmcglcocoa.dylib`
  are absent from fresh bundles. The small Cocoa runtime bootstrap remains;
  broader platform cleanup is another milestone.
- Legacy Keyboard/Mouse/Cursor public queues and polling semantics remain.
  `GLFWInput` maps physical keys, pairs character events with keys, handles
  Unicode/repeat, converts mouse Y coordinates, retains fractional relative
  deltas and emits releases on focus loss. Bounded callback queues use their
  own lock, never the legacy Keyboard/Mouse global lock. Raw mouse mode is off.
  JInput controller enumeration is unchanged and controllers were not tested.
- The merged JAR contains official core/OpenGL/OpenAL/GLFW Java modules,
  1.6.9 binding adapters and a selected platform-neutral legacy API shell.
  It is not a general-purpose replacement for every LWJGL 2 API.
  Official multi-release classes and 3.4.3+4 metadata are retained.
- Existing Display counters were extracted into `MCGLDisplayMetrics`;
  the original frame limiter and profiler are compiled unchanged.
  DirectLauncher uses GLFW show/focus instead of the old Cocoa activation
  bridge and delayed window-repositioning thread. The peerless Applet remains.
- Retina framebuffer scaling is disabled, preserving 1:1 window/input/render
  coordinates. RGB10 hints reproduce the baseline RGB10_A2 framebuffer on
  the test Mac (depth/stencil 32/8). These measured driver results are not
  universal hardware guarantees.
- Fullscreen uses GLFW's monitor API, not Spaces. It saves windowed bounds,
  keeps the GL context and follows the actual selected mode. A requested
  desktop size need not equal the selected fullscreen mode; no unchanged
  display-resolution guarantee is made.
- Installer marker: `bootstrap lwjgl3-glfw-3.5.1-2`. When the marker changes,
  original client/utility JARs are refreshed before the existing transforms.
  Previous alphaSort preferences are preserved. Version 1.6.10 uses internal
  build 170, maintaining monotonic build numbering.

## Regressions found and corrected during validation

### Fullscreen viewport

The first candidate entered fullscreen but left the scene at 1512×982 inside
a 1920×1200 drawable. MCGL cached the requested dimensions before the native
transition, while the previous window-resize helper skipped fullscreen.
The new Display reports actual dimensions in both modes. The window patch
now synchronizes the client's dimensions/scaled GUI after the transition
and on later size events, with the existing positive-size guard.
No rendering algorithm was changed.

The new negative-control test reproduces this exact mismatch on the first
candidate and fails as expected. The fixed test extracts the actual patched
client resize helper into an account-free fixture and verifies all four
fullscreen corner pixels, not just the requested mode or context survival.
The user subsequently confirmed correct fullscreen rendering on the server.

### Native crash during manual resize

The first candidate crashed in Apple's OpenGL-over-Metal texture draw path
while AppKit was handling live window resizing. GLFW's Cocoa resize, move and
layer-update paths called `NSOpenGLContext.update` independently of the
render worker. Apple's documentation requires serialized access to a shared
context/drawable. See [context update](https://developer.apple.com/documentation/appkit/nsopenglcontext/update%28%29)
and [OpenGL threading](https://developer.apple.com/library/archive/documentation/GraphicsImaging/Conceptual/OpenGL-MacProgGuide/opengl_threading/opengl_threading.html).

A small checked-in GLFW source patch locks the CGL context around those three
update paths. The render owner holds the matching lock and releases it before
waiting for main-thread operations or the frame limiter, then reacquires it.
Exception propagation also restores the lock. No global Objective-C method
replacement, disabled resizing or disabled VBO workaround is used.

The GPU test attempts asynchronous native resize while the worker draws
textured VBOs. It verifies that NSGL updates cannot complete while the render
lock is held and do complete at the frame boundary. The user then confirmed
that repeated manual resizing works on the server.

## Dependency provenance

- Java bindings: official `org.lwjgl:lwjgl-glfw:3.4.3` from Maven Central;
  [SHA-256](../../third-party/glfw-sha256.txt).
- Native GLFW: official 3.5.1 source commit
  `d9d6f0f1f967807ffade6598ea9a631ebaf37a56`, plus the narrow local NSGL
  synchronization patch. [Build/provenance](../../third-party/GLFW-BUILD.md),
  [source SHA-256](../../third-party/glfw-source-sha256.txt),
  [patch](../../third-party/glfw-patches/0001-serialize-nsgl-update.patch).
- The initial candidate used the vendor native JAR; the corrected build is
  **not** that unmodified binary. It is compiled with Xcode clang for ARM64
  and macOS 14 minimum. The official GLFW license and build notice are bundled.
- LWJGL's supported absolute custom-library path selects this GLFW binary.
  Core/OpenGL hash checks remain enabled. Game JARs and accounts are not
  included in the public source tree or release package.

## Checks completed on the corrected build

- Existing regression suite: 46 test-process invocations (20 Swift, 26 Java;
  patch preparation is not counted). Existing gameplay expectations retained.
- Binding/resource/control-flow/idempotence audit; 250 original-client and
  53 utility binding references resolve with zero missing symbols.
- Input translation: 80 assertions covering keys, Unicode, repeat, focus,
  mouse movement/buttons/wheel and queue limits.
- Real GPU comparison against 1.6.9: 42 assertions per backend, two context
  lifetimes, identical format and fixture pixels; includes shaders, FBOs,
  client arrays/VBO state, audio recreation and audio use on another thread.
- Window/input lifecycle: 412 assertions, two lifetimes, six fullscreen
  cycles, corner coverage, cursor modes/custom cursor, exception/lock unwind,
  80 asynchronous resizes and 8,000 textured VBO draw calls.
- Probe processes exit normally with status 0, without timeout or native-loader
  errors. The negative control exits with an assertion failure as intended.
- Application audit: 86 ARM64 Mach-O files, deployment targets compatible
  with declared macOS 14 minimum, strict ad-hoc signature verification.
  This is not Developer ID signing or notarization.
- Final prepared-input rebuild repeats the source and GLFW/GPU checks above.
  `PortSupport` is byte-identical to the manually tested corrected application
  except for ZIP timestamps in `mcgl-nativewindow-patch.jar`; all extracted
  entries of that JAR are identical. `PatchTools` is byte-identical as well.
- Final installer: clean official-client installation, upgrade from 1.6.9 and
  upgrade from the initial 1.6.10 marker all pass in isolated account-free
  profiles. Both upgrades preserve `alphaSort:false`. The actual installed
  client in all three profiles contains the required post-fullscreen resize
  hook. Rechecking each profile requires no repeated download or patching.
- Final DMG passes `hdiutil verify`; ZIP passes compressed-data validation.
  SHA-256 sidecars verify for both. Neither package contains original game
  JARs, account data or private forum drafts.

Manual server login, corrected fullscreen, repeated manual resizing, movement,
mouse look, inventory, audio and Cmd+Tab recovery are confirmed. After server
disconnect no client network threads remained. Normal game closure logged
`Stopping!` and SoundSystem shutdown, followed by process termination; the
game process was absent afterward. No long soak, multi-monitor/hotplug,
clean-Mac build, complete shader-pack compatibility or controlled FPS
comparison is claimed.

## Re-run locally

```sh
bash tools/verify-glfw.sh \
  "/path/to/jdk21/Contents/Home" \
  "/path/to/legacy-1.6.8/Minecraft Galaxy ARM64.app" \
  "/path/to/baseline-1.6.9/Minecraft Galaxy ARM64.app" \
  "/path/to/candidate-1.6.10/Minecraft Galaxy ARM64.app" \
  "/path/to/original/bin/mcgl.jar" --live
```

The adjacent original `lwjgl_util.jar` is also required. Without `--live`,
only binding/input/structure checks run. The live probes contain no account
or network gameplay and do not modify installed applications or profiles.
Source verification and prepared-input requirements are in [BUILDING](../../BUILDING.md).
