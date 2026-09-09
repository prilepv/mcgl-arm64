// Deterministic, account-free state-machine test; no application or display server.
#include "../../platform/native/macos/MainThread.m"

@interface FullscreenTestWindow : NSObject
@property NSWindowStyleMask styleMask;
@property NSWindowCollectionBehavior collectionBehavior;
@property int toggles;
@end
@implementation FullscreenTestWindow
- (void)toggleFullScreen:(id)sender {
    (void)sender;
    self.toggles++;
    [[NSNotificationCenter defaultCenter] postNotificationName:
        (self.styleMask & NSWindowStyleMaskFullScreen) ? NSWindowWillExitFullScreenNotification
        : NSWindowWillEnterFullScreenNotification object:self];
}
@end
static int checks;
static void check(BOOL condition, const char *message) {
    checks++;
    if (!condition) { fprintf(stderr, "FAIL: %s\n", message); exit(1); }
}
static void completed(FullscreenTestWindow *window, BOOL fullscreen) {
    window.styleMask = fullscreen ? NSWindowStyleMaskFullScreen : 0;
    [[NSNotificationCenter defaultCenter] postNotificationName:fullscreen
        ? NSWindowDidEnterFullScreenNotification : NSWindowDidExitFullScreenNotification object:window];
}
int main(void) {
    @autoreleasepool {
        for (int lifetime = 0; lifetime < 3; lifetime++) {
            FullscreenTestWindow *window = [FullscreenTestWindow new];
            jlong handle = (jlong)(uintptr_t)window;
            Java_local_mcgl_platform_macos_MacOSMainThread_attachFullscreen(NULL, NULL, handle);
            Java_local_mcgl_platform_macos_MacOSMainThread_attachFullscreen(NULL, NULL, handle);
            check((window.collectionBehavior & NSWindowCollectionBehaviorFullScreenPrimary) != 0, "native primary Space");
            check(Java_local_mcgl_platform_macos_MacOSMainThread_fullscreenState(NULL, NULL, handle, NO) == 0, "windowed state");
            Java_local_mcgl_platform_macos_MacOSMainThread_requestFullscreen(NULL, NULL, handle, YES);
            check(window.toggles == 1, "entry started once");
            check(Java_local_mcgl_platform_macos_MacOSMainThread_fullscreenState(NULL, NULL, handle, NO) == 5, "requested entry in flight");
            Java_local_mcgl_platform_macos_MacOSMainThread_requestFullscreen(NULL, NULL, handle, YES);
            Java_local_mcgl_platform_macos_MacOSMainThread_requestFullscreen(NULL, NULL, handle, NO);
            check(window.toggles == 1, "duplicate/reversal serialized");
            completed(window, YES);
            check(window.toggles == 1, "no nested toggle in AppKit completion");
            check(Java_local_mcgl_platform_macos_MacOSMainThread_fullscreenState(NULL, NULL, handle, YES) == 6, "queued exit starts at pump");
            check(window.toggles == 2, "single queued reversal");
            completed(window, NO);
            check(Java_local_mcgl_platform_macos_MacOSMainThread_fullscreenState(NULL, NULL, handle, YES) == 0, "reversal settled");
            // External system/green-button action has no Java request.
            [window toggleFullScreen:nil]; completed(window, YES);
            check(Java_local_mcgl_platform_macos_MacOSMainThread_fullscreenState(NULL, NULL, handle, YES) == 3, "system entry synchronized");
            [window toggleFullScreen:nil]; completed(window, NO);
            check(Java_local_mcgl_platform_macos_MacOSMainThread_fullscreenState(NULL, NULL, handle, YES) == 0, "system exit synchronized");
            Java_local_mcgl_platform_macos_MacOSMainThread_requestFullscreen(NULL, NULL, handle, YES);
            [[NSNotificationCenter defaultCenter] postNotificationName:@"MCGLWindowDidFailToEnterFullScreen" object:window];
            int previous = window.toggles;
            check(Java_local_mcgl_platform_macos_MacOSMainThread_fullscreenState(NULL, NULL, handle, YES) == 0, "failed entry restores actual state");
            check(window.toggles == previous, "failed entry does not retry forever");
            Java_local_mcgl_platform_macos_MacOSMainThread_requestFullscreen(NULL, NULL, handle, YES); completed(window, YES);
            Java_local_mcgl_platform_macos_MacOSMainThread_requestFullscreen(NULL, NULL, handle, NO);
            [[NSNotificationCenter defaultCenter] postNotificationName:@"MCGLWindowDidFailToExitFullScreen" object:window];
            check(Java_local_mcgl_platform_macos_MacOSMainThread_fullscreenState(NULL, NULL, handle, YES) == 3, "failed exit restores actual fullscreen");
            Java_local_mcgl_platform_macos_MacOSMainThread_requestFullscreen(NULL, NULL, handle, NO);
            Java_local_mcgl_platform_macos_MacOSMainThread_detachFullscreen(NULL, NULL, handle);
            check(objc_getAssociatedObject(window, &mcgl_fullscreen_key) == nil, "detach during transition releases observer");
            completed(window, NO); // must not call a released observer
            [window release];
        }
        printf("NATIVE_FULLSCREEN_STATE_PASS checks=%d\n", checks);
    }
    return 0;
}
