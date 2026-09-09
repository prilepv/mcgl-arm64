# Native macOS Spaces fullscreen — 1.6.18 build 185

Separate, user-requested window follow-up after acceptance of renderer build 184.
No renderer algorithm, shader, world scheduler, visibility, transparency policy,
graphics option, framebuffer scale, dependency version or frame limit is changed.
The main application/profile and earlier test bundles remain separate; no release
or archive is published. Milestones 11–13 are not included.

## Window ownership and state

macOS uses AppKit's `NSWindow.toggleFullScreen:` and `FullScreenPrimary` collection
behavior. The window remains a GLFW window with no acquired monitor, so AppKit
owns the separate Space, standard animation, content size and restored window
bounds. OpenGL resources remain attached to the same context.

The optional host contract is outside the legacy Display ABI and renderer.
Native state is window-scoped: requested, actual, and transitioning. A repeated
request is idempotent; a reversal during an animation is queued until completion
and the next event pump, without recursively starting another AppKit animation
inside its completion notification. The green button/system action updates the
same state. Transition failure synchronizes to the actual native style and does
not retry indefinitely. Destruction removes the observer before GLFW teardown.

GLFW retains its original delegate, input callbacks and three locked NSGL update
paths. Its small second source patch forwards the two AppKit failure callbacks
as window-scoped notifications; there is no delegate replacement, method swizzle,
custom animation, borderless-screen simulation or video-mode switch. The game
owner still releases its CGL lock before dispatching to the AppKit thread.

The client patch discovers and validates the original fullscreen flag. It reads
the platform's requested state before the game's toggle inverts it, and also
synchronizes that flag in the existing per-frame resize helper, including frames
without size changes. Actual nonzero content dimensions drive viewport/GUI
resizing. The installer marker `lwjgl3-game-original-core-4` refreshes that hook.

## Verification

`tools/verify-native-fullscreen.sh JAVA21_HOME CANDIDATE.app ORIGINAL_MCGL.jar --live`
builds fresh account-free fixtures. Native state tests exercise duplicate requests,
queued reversals, system changes, failed entry/exit and observer teardown (45
assertions). The real Core 4.1 test uses the original patched client helper,
the real standard green button and native AppKit selector. It checks actual
NSWindow style, no GLFW monitor, unchanged video mode and 1:1 framebuffer,
window size/position restoration, resource/context identity, corner pixels,
startup fullscreen, rapid requests, resize, and late callbacks after destruction
during animation. The packaged run passes 247 assertions over two Core lifetimes;
the final extended run uses the helper extracted from the actual upgraded client
and also verifies restoration after a later ordinary resize (273 assertions).
The installed toggle's state-sync prefix and resize hook are both checked.

The final signed APP passes the 436-assertion GLFW lifecycle test (six fullscreen
cycles, cursor, resize/render synchronization), 64 platform ownership/failure
checks, 450 Core geometry checks, 1,192 original-world game-renderer checks and
the compatibility pixel/lifecycle regression. All 155 renderer class/resource
entries are byte-identical to the accepted build 184 output. The source/launcher
regression suite passes. Packaging validates 86 ARM64 Mach-O files and the ad-hoc
signature; this is not a notarization or clean-Mac certification.

An isolated 184 → 185 installer transaction updates two game JARs; repeating it
changes nothing. The installed library matches the signed APP, HotSpot verifies
243 transformed classes without initialization, and installed boundary, original
algorithm and font audits pass (4,271 routed calls, 237,323 and 106 assertions).
The separately signed Native Fullscreen Test 9 uses the existing isolated manual
profile; authenticated/manual Spaces acceptance remains the user's next step.

An initial stress sequence closed a transitioning fullscreen window and immediately
requested fullscreen in a replacement window. AppKit rejected the second entry;
the state correctly returned to windowed and the assertion expecting entry failed
(no native crash). Startup and teardown are now tested independently; the complete
window test passes. This does not turn an OS-rejected transition into a success.

GUI tests require the macOS desktop session. Automated window checks do not replace
the user's server-session checks of F11, green button, Cmd+Tab/Space switching,
mouse capture, restored GUI size and unchanged performance expectations.

## API references

- [Apple: toggleFullScreen](https://developer.apple.com/documentation/appkit/nswindow/togglefullscreen(_:))
- [Apple: failed entry](https://developer.apple.com/documentation/appkit/nswindowdelegate/windowdidfailtoenterfullscreen(_:))
- [Apple: failed exit](https://developer.apple.com/documentation/appkit/nswindowdelegate/windowdidfailtoexitfullscreen(_:))
- [GLFW: native Cocoa window access](https://www.glfw.org/docs/latest/group__native.html)
