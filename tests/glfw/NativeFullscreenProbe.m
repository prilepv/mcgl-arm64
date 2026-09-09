#include <jni.h>
#import <Cocoa/Cocoa.h>
JNIEXPORT jint JNICALL Java_org_lwjgl_opengl_NativeFullscreenProbe_cocoaFacts(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    NSWindow *window = (NSWindow *)(uintptr_t)handle;
    return ((window.styleMask & NSWindowStyleMaskFullScreen) ? 1 : 0)
        | ((window.collectionBehavior & NSWindowCollectionBehaviorFullScreenPrimary) ? 2 : 0)
        | ([window.delegate respondsToSelector:@selector(windowDidFailToEnterFullScreen:)]
            && [window.delegate respondsToSelector:@selector(windowDidFailToExitFullScreen:)] ? 4 : 0);
}
JNIEXPORT void JNICALL Java_org_lwjgl_opengl_NativeFullscreenProbe_systemToggle(JNIEnv *env, jclass type, jlong handle, jboolean green) {
    (void)env; (void)type;
    NSWindow *window = (NSWindow *)(uintptr_t)handle;
    if (green) [[window standardWindowButton:NSWindowZoomButton] performClick:nil];
    else [window toggleFullScreen:nil];
}
