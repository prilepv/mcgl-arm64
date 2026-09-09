# GLFW native build for MCGL 1.6.18

- Upstream: https://github.com/glfw/glfw
- Release: 3.5.1; commit `d9d6f0f1f967807ffade6598ea9a631ebaf37a56`.
- Source archive: https://codeload.github.com/glfw/glfw/tar.gz/d9d6f0f1f967807ffade6598ea9a631ebaf37a56
- Source SHA-256: `96722514789b1aa1ad9c4debd874450156ea457aea691e546ffaf5b53892d5a3`.
- Local modification: `glfw-patches/0001-serialize-nsgl-update.patch` wraps
  the three Cocoa NSGL update paths in CGLLockContext/CGLUnlockContext.
  The render owner uses the matching lock and releases it before main-thread
  dispatch and frame-limit waits. No global Objective-C method replacement.
- `glfw-patches/0002-cocoa-fullscreen-failure-notifications.patch` forwards the
  two AppKit fullscreen failure delegate callbacks as window-scoped notifications.
  The host observes normal AppKit transition notifications, owns the serialized
  requested/actual state, and calls `NSWindow.toggleFullScreen:`. GLFW retains
  its original delegate and context, and never acquires a monitor for Spaces.
- Build: `tools/build-glfw-native.sh`; Xcode clang, ARM64, macOS 14 minimum.
  Source list/definitions follow upstream's shared Cocoa CMake target.
- Java bindings: unmodified `org.lwjgl:lwjgl-glfw:3.4.3` from Maven Central.
  The official native JAR was used for the initial candidate/comparison, but
  the final native library is this source build, not that Maven binary.
- License: the accompanying GLFW-LICENSE.txt (zlib/libpng).

The absolute custom-library path is configured using LWJGL's supported loader.
LWJGL core/OpenGL hash checks remain enabled. This native build is not claimed
to be byte-identical to the vendor distribution.
