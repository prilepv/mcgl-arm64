#import <Cocoa/Cocoa.h>
#import <Metal/MTLDrawable.h>
#import <QuartzCore/CAMetalLayer.h>
#import <objc/runtime.h>
#include <jni.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>

static char startupCoverKey;

// bgfx skips the onscreen drawable for an occluded window. Cover the visible
// window until Metal has actually presented a frame underneath the cover.
@interface MCGLStartupMetalLayer : CAMetalLayer
- (instancetype)initWithStartupCover:(NSView *)cover;
@end

@implementation MCGLStartupMetalLayer {
    __weak NSView *startupCover;
    atomic_bool revealed;
    BOOL logPacing;
    MTLDrawablePresentedHandler presentedHandler;
    double gapThresholdMs;
    CFTimeInterval lastPresented, reportStart, peakTime;
    double peakMs, totalMs;
    unsigned intervals, gaps, dropped;
}

- (instancetype)initWithStartupCover:(NSView *)cover {
    self = [super init];
    if (self) {
        startupCover = cover;
        atomic_init(&revealed, false);
        const char *diagnostics = getenv("MCGL_GRAPHICS_DIAGNOSTICS");
        logPacing = diagnostics && strcmp(diagnostics, "1") == 0;
        const char *limit = getenv("MCGL_FPS_LIMIT");
        long fps = limit ? strtol(limit, NULL, 10) : 0;
        gapThresholdMs = fps > 0 ? 1500.0 / fps : 16.67;
        __weak MCGLStartupMetalLayer *layer = self;
        presentedHandler = ^(id<MTLDrawable> presented) {
            [layer handlePresentation:presented.presentedTime];
        };
    }
    return self;
}

- (void)recordPresentation:(CFTimeInterval)time {
    @synchronized (self) {
        if (time <= 0) { dropped++; return; }
        if (time <= lastPresented) return;
        if (lastPresented > 0) {
            double elapsedMs = (time - lastPresented) * 1000;
            totalMs += elapsedMs;
            intervals++;
            if (elapsedMs > gapThresholdMs) gaps++;
            if (elapsedMs > peakMs) {
                peakMs = elapsedMs;
                peakTime = time;
            }
        } else {
            reportStart = time;
        }
        lastPresented = time;
        if (time - reportStart < 5) return;
        double epochMs = (CFAbsoluteTimeGetCurrent() + kCFAbsoluteTimeIntervalSince1970
            + peakTime - CACurrentMediaTime()) * 1000;
        fprintf(stdout, "[MCGL Metal Pacing] %.3fs intervals=%u avg=%.3f max=%.3f ms over%.2f=%u dropped=%u peakEpochMs=%.0f\n",
            time - reportStart, intervals, totalMs / intervals, peakMs, gapThresholdMs, gaps, dropped, epochMs);
        fflush(stdout);
        reportStart = time;
        totalMs = peakMs = 0;
        intervals = gaps = dropped = 0;
    }
}

- (void)handlePresentation:(CFTimeInterval)time {
    if (logPacing) [self recordPresentation:time];
    if (time <= 0 || atomic_load_explicit(&revealed, memory_order_relaxed)
            || atomic_exchange_explicit(&revealed, true, memory_order_relaxed)) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        NSView *cover = self->startupCover;
        NSWindow *window = cover.window;
        if (!window || objc_getAssociatedObject(window, &startupCoverKey) != cover) return;
        [cover removeFromSuperview];
        objc_setAssociatedObject(window, &startupCoverKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        fputs("[MCGL bgfx] first Metal frame presented; startup cover removed\n", stdout);
        fflush(stdout);
    });
}

- (id<CAMetalDrawable>)nextDrawable {
    id<CAMetalDrawable> drawable = [super nextDrawable];
    if (logPacing || !atomic_load_explicit(&revealed, memory_order_relaxed))
        [drawable addPresentedHandler:presentedHandler];
    return drawable;
}
@end

JNIEXPORT void JNICALL Java_local_mcgl_render_backend_MetalGameTarget_installStartupCover(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    // GLFW constructs the game window on the AppKit main thread.
    NSWindow *window = (__bridge NSWindow *)(void *)(uintptr_t)handle;
    NSView *content = window.contentView;
    NSView *cover = [[NSView alloc] initWithFrame:content.bounds];
    cover.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    cover.wantsLayer = YES;
    cover.layer.backgroundColor = [NSColor colorWithSRGBRed:0.06 green:0.05 blue:0.10 alpha:1].CGColor;
    NSTextField *label = [NSTextField labelWithString:@"Запуск игры…"];
    label.textColor = NSColor.whiteColor;
    label.font = [NSFont systemFontOfSize:15 weight:NSFontWeightMedium];
    label.translatesAutoresizingMaskIntoConstraints = NO;
    [cover addSubview:label];
    [NSLayoutConstraint activateConstraints:@[
        [label.centerXAnchor constraintEqualToAnchor:cover.centerXAnchor],
        [label.centerYAnchor constraintEqualToAnchor:cover.centerYAnchor],
    ]];
    [content addSubview:cover positioned:NSWindowAbove relativeTo:nil];
    objc_setAssociatedObject(window, &startupCoverKey, cover, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    fputs("[MCGL bgfx] startup cover installed\n", stdout);
    fflush(stdout);
}

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
        NSView *cover = objc_getAssociatedObject(window, &startupCoverKey);
        window.contentView.wantsLayer = YES;
        window.contentView.layer = cover ? [[MCGLStartupMetalLayer alloc] initWithStartupCover:cover] : [CAMetalLayer layer];
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
