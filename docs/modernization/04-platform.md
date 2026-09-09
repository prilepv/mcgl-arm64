# Milestone 4 — platform abstraction cleanup / 1.6.11

## Scope and status

**PASS — verified locally on 2026-09-08, version 1.6.11.**
Starts from verified [1.6.10](03-glfw.md). This is the next point in the
[original roadmap](README.md), not the rendering architecture milestone.
No publication or installed-profile replacement is part of this work.

## Boundaries

- `org.lwjgl.opengl.Display` is the legacy client adapter: API values, old
  Mouse/Keyboard lifecycle, existing frame limiter/profiler and binding attachment.
  It no longer calls GLFW or macOS JNI directly. Its context request preserves
  2.1 compatibility, the existing RGB10 hints and 1:1 framebuffer scaling.
- `GLFWInput` retains key codes, Unicode pairing, queue layout, cursor coordinate
  conversion, fractional movement/wheel handling and the independent callback
  lock. Native callback/cursor operations move behind `InputBackend`. Physical
  input codes retain GLFW numbering; this is not a new key-binding scheme.
- `WindowBackend`, `WindowMode`, `ContextRequest` and event/input contracts use
  only JDK types. They do not import AWT, legacy LWJGL, game or renderer classes.
- `platform/.../glfw` owns native window/monitor/callback lifecycle and actual
  dimensions. Existing fullscreen semantics and the 1.6.10 viewport fix remain.
- `HostServices` separates main-thread dispatch and drawable locking.
  `platform/.../macos` owns the native bridge, host hints and URL opening.
  `NativeLibraries` centralizes the pinned bundle names for GLFW, OpenAL Soft
  and the main-thread bridge, with no system-library fallback.
- Only macOS ARM64 is implemented and selected. There are no empty alternate
  backends, renderer plugins or claims of Windows/Linux support.

The macOS bootstrap still creates the JVM on the first thread and runs AppKit
there. Rendering stays on the existing game thread. The peerless Applet,
original MCGLClassLoader, internal JDK compatibility options, JInput controllers,
audio lifecycle and game/network patches remain; this is not their removal.

## Lifecycle constraints

GLFW initialization, termination and event/window operations run on the main
thread. Context ownership is explicit: another thread cannot bind, release,
swap or destroy an owned context. The native NSGL update patch still holds the
same CGL lock as rendering; main-thread dispatch and frame-limit waits release
and restore it. No GPU work is moved onto a new worker.

Failed initialization frees the error callback; failed window creation tears
down the partial native window before rethrowing the original failure.
Clipboard access initializes the windowing library before use, even if no
window has yet been created. Tests must not inspect a user's clipboard or open
external URLs as part of validation.

GLFW's [thread-safety contract](https://www.glfw.org/docs/latest/intro_guide.html#thread_safety)
and [context rules](https://www.glfw.org/docs/latest/context_guide.html#context_current)
remain authoritative. The [GLFW native source patch](../../third-party/GLFW-BUILD.md)
and all dependency versions are unchanged.

## Installer and versioning

Version 1.6.11 / internal build 171. Marker:
`Minecraft Galaxy ARM64 bootstrap lwjgl3-platform-1`.
Existing `lwjgl3-` profiles retain their alphaSort preference. The marker refresh
installs the matching new JNI entry points and Java platform classes together.
Future versions continue with 1.6.12, 1.6.13, etc.; 1.7.0 is reserved for the
whole completed roadmap.

## Validation

- The full existing source regression suite passes with Java 21 and the
  historical LWJGL 2 API fixture. The gameplay, rendering and bytecode patch
  sources match the verified 1.6.10 baseline; the runtime bootstrap is unchanged.
  The native CGL bridge implementation differs only in its JNI package names.
- The packaged-bytecode audit checks 14 platform classes, four legacy adapters
  and three public legacy ABIs. Dependency direction is enforced, including
  descriptors and callback references. As a negative control, the pre-platform
  1.6.10 bundle is rejected for lacking the platform contracts.
- 118 platform contract checks pass: host selection, value validation, pinned
  library paths and no native initialization from contract-only use.
- 45 injected input-backend checks pass: callback lifecycle, dimensions,
  coordinate conversion, opaque cursor handles, focus loss, late callbacks and
  preservation of `LWJGLException` when native cursor allocation fails.
- 64 native lifecycle checks pass: early/late initialization failure, partial
  window rollback, failed context binding, foreign-thread rejection, nested
  dispatch, lock restoration on exceptions, context reattachment, actual GPU
  pixels, cursor cleanup and complete GLFW restart.
- The unchanged 80 input and 412 GLFW window checks pass on the final package,
  including six fullscreen cycles and 80 concurrent resize requests during
  textured VBO drawing. The actual installed client retains exactly one
  post-fullscreen viewport-resynchronization hook.
- GPU comparison against 1.6.10 passes: 42 checks per version, identical
  framebuffer format and fixture pixels across two context lifetimes. This is
  not an assertion of identical output for every possible game scene or an FPS
  benchmark.
- Clean installation from the official client passes without an account.
  Updating a separate 1.6.10 fixture with the final package preserves
  `alphaSort:false` and installs matching Java/JNI files byte-for-byte.
  Repeated installer runs on fresh and upgraded fixtures require no changes.
- The user reports PASS for server gameplay, movement, mouse, inventory, sound,
  Cmd+Tab, F11 and mouse-driven resize. After returning to the menu, both network
  threads were absent. Normal close logged `Stopping!` and sound shutdown at
  10:57:51 UTC; the game process ended at 10:57:52 UTC.
- The final package audit passes for all 86 ARM64 Mach-O files, deployment
  target at most macOS 14, strict local signatures, paired new Java/JNI entry
  points and CGL locking on both native sides. ZIP integrity, DMG verification
  and both SHA-256 sidecars pass. Developer ID signing, notarization and a clean
  second-Mac installation are not certified by these local checks.

One earlier window-stress run overlapped another native GUI probe and failed
its existing two-second AppKit-entry assertion; it was not counted as a pass.
No native crash was recorded. The unchanged test passed when rerun separately
and again on the final package. Its assertions and timeouts were not relaxed.

The manual session used the validated candidate. Before final packaging, the
input adapter gained the checked-exception preservation for failed cursor
allocation described above. That error path is covered by the injected test;
the final packaged class was then used for all final platform/window/GPU tests.
All native libraries, patch-tool files and every other class in `lwjgl.jar`
match the manually tested candidate byte-for-byte.

## Repeating the checks

Use the prepared dependencies described in [BUILDING](../../BUILDING.md).
Run the native GUI suites sequentially:

```sh
bash tools/verify-platform.sh \
  "/path/to/jdk21/Contents/Home" \
  "/path/to/1.6.10/Minecraft Galaxy ARM64.app" \
  "/path/to/1.6.11/Minecraft Galaxy ARM64.app" --live

bash tools/verify-glfw.sh \
  "/path/to/jdk21/Contents/Home" \
  "/path/to/1.6.8/Minecraft Galaxy ARM64.app" \
  "/path/to/1.6.10/Minecraft Galaxy ARM64.app" \
  "/path/to/1.6.11/Minecraft Galaxy ARM64.app" \
  "/path/to/original-client/bin/mcgl.jar" --live
```

The 1.6.8 application supplies only the historical legacy API fixture. The GPU
baseline for this milestone is 1.6.10. Neither suite logs into a game account.

## Remaining scope

Native Spaces fullscreen, Core OpenGL, new shaders/renderer, gameplay/protocol
changes, new production threads and performance optimization are out of scope.
The next roadmap point is the rendering architecture, not an implicit switch
to Core OpenGL. Further work requires a separate request. Nothing was published
and the main installed application/profile was not replaced.
