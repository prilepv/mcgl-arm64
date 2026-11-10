#import <Cocoa/Cocoa.h>
#import <QuartzCore/CAMetalLayer.h>
#include <jni.h>
#include <stdint.h>

static jlong openWindow(jint width, jint height, BOOL preview) {
    __block NSWindow *window;
    dispatch_sync(dispatch_get_main_queue(), ^{
        NSRect rect = NSMakeRect(0, 0, width, height);
        if (preview) {
            NSPanel *panel = [[NSPanel alloc] initWithContentRect:rect
                styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskNonactivatingPanel
                backing:NSBackingStoreBuffered defer:NO];
            panel.hidesOnDeactivate = NO;
            panel.ignoresMouseEvents = YES;
            window = panel;
        } else {
            window = [[NSWindow alloc] initWithContentRect:rect
                styleMask:NSWindowStyleMaskTitled backing:NSBackingStoreBuffered defer:NO];
        }
        window.releasedWhenClosed = NO;
        window.title = preview ? @"Minecraft Galaxy · bgfx Metal Terrain" : @"Minecraft Galaxy · bgfx Metal Test";
        window.contentView.wantsLayer = YES;
        window.contentView.layer = [CAMetalLayer layer];
        if (preview) {
            NSRect screen = NSScreen.mainScreen.visibleFrame;
            [window setFrameTopLeftPoint:NSMakePoint(NSMaxX(screen) - width - 24, NSMaxY(screen) - 24)];
            [window orderFrontRegardless];
        } else {
            [window center];
            [window makeKeyAndOrderFront:nil];
        }
    });
    return (jlong)(uintptr_t)(__bridge_retained void *)window;
}

static void closeWindow(jlong handle) {
    dispatch_sync(dispatch_get_main_queue(), ^{
        NSWindow *window = (__bridge_transfer NSWindow *)(void *)(uintptr_t)handle;
        [window orderOut:nil];
        window.contentView.layer = nil;
        [window close];
    });
}

JNIEXPORT jlong JNICALL Java_local_mcgl_DirectLauncher_openWindow(JNIEnv *env, jclass cls, jint width, jint height) {
    (void)env; (void)cls;
    return openWindow(width, height, NO);
}

JNIEXPORT void JNICALL Java_local_mcgl_DirectLauncher_closeWindow(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    closeWindow(handle);
}

JNIEXPORT jlong JNICALL Java_local_mcgl_render_backend_BgfxTerrainStream_openPreview(JNIEnv *env, jclass cls, jint width, jint height) {
    (void)env; (void)cls;
    return openWindow(width, height, YES);
}

JNIEXPORT void JNICALL Java_local_mcgl_render_backend_BgfxTerrainStream_closePreview(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    closeWindow(handle);
}

JNIEXPORT jlong JNICALL Java_local_mcgl_render_backend_BgfxTerrainStream_attachGameWindow(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    NSWindow *window = (__bridge NSWindow *)(void *)(uintptr_t)handle;
    if (!window) return 0;
    dispatch_sync(dispatch_get_main_queue(), ^{
        window.contentView.wantsLayer = YES;
        window.contentView.layer = [CAMetalLayer layer];
        window.title = @"Minecraft Galaxy · bgfx Metal";
    });
    return (jlong)(uintptr_t)(__bridge_retained void *)window;
}

JNIEXPORT void JNICALL Java_local_mcgl_render_backend_BgfxTerrainStream_detachGameWindow(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    dispatch_sync(dispatch_get_main_queue(), ^{
        NSWindow *window = (__bridge_transfer NSWindow *)(void *)(uintptr_t)handle;
        window.contentView.layer = nil;
    });
}

JNIEXPORT jint JNICALL Java_local_mcgl_render_backend_BgfxTerrainStream_windowPolicy(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    NSWindow *window = (__bridge NSWindow *)(void *)(uintptr_t)handle;
    __block BOOL exposed;
    dispatch_sync(dispatch_get_main_queue(), ^{
        exposed = window.isVisible && !window.isMiniaturized
            && (window.occlusionState & NSWindowOcclusionStateVisible) != 0;
    });
    return (jint)((NSProcessInfo.processInfo.thermalState << 1) | (exposed ? 1 : 0));
}
