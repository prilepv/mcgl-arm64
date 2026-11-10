// The direct and hybrid libraries may coexist without Objective-C class-name collisions.
#define PreviewMesh DirectTerrainMesh
#define PreviewTexture DirectTerrainTexture
#define TerrainResources DirectTerrainResources
#define MCGL_TERRAIN_REVERSED_DEPTH
#include "MetalTerrainCore.h"
#import <Cocoa/Cocoa.h>
#import <QuartzCore/CAMetalLayer.h>

@interface DirectMetalView : NSView
@end
@implementation DirectMetalView
- (CALayer *)makeBackingLayer { return [CAMetalLayer layer]; }
- (NSView *)hitTest:(NSPoint)point { (void)point; return nil; }
@end

@interface DirectMetalPanel : NSPanel
@end
@implementation DirectMetalPanel
- (BOOL)canBecomeKeyWindow { return NO; }
- (BOOL)canBecomeMainWindow { return NO; }
@end

@interface DirectSurface : TerrainResources <NSWindowDelegate>
@property(nonatomic, strong) NSWindow *window;
@property(nonatomic, strong) NSView *view;
@property(nonatomic, strong) NSArray *windowObservers;
@property(nonatomic, strong) CAMetalLayer *layer;
@property(nonatomic, strong) id<MTLTexture> depth;
@property(nonatomic, strong) id<MTLCommandBuffer> lastFrame;
@property(atomic) BOOL closed;
@property(atomic) NSUInteger presented;
- (void)updateSize;
@end

@implementation DirectSurface
- (void)updateSize {
    NSView *view = self.view;
    // The game's GLFW window uses a 1:1 framebuffer. Match that pixel budget for the main Metal output.
    NSSize backing = self.windowObservers ? view.bounds.size : [view convertSizeToBacking:view.bounds.size];
    self.layer.contentsScale = self.windowObservers ? 1 : self.window.backingScaleFactor;
    self.layer.drawableSize = CGSizeMake(MAX(1, ceil(backing.width)), MAX(1, ceil(backing.height)));
}
- (void)windowDidResize:(NSNotification *)notification { (void)notification; [self updateSize]; }
- (void)windowDidChangeBackingProperties:(NSNotification *)notification { (void)notification; [self updateSize]; }
- (void)windowWillClose:(NSNotification *)notification { (void)notification; self.closed = YES; }
@end

typedef struct {
    float material[112];
    jint flags[14];
    jint first, count;
} DirectPass;
_Static_assert(sizeof(DirectPass) == 512, "Direct Metal pass layout");

static void reverseTerrainDepth(DirectPass *terrain) {
    // Convert the projection before transforming vertices so distant depth does not
    // lose precision when the original clip-space z and w are nearly equal.
    for (int column = 0; column < 16; column += 4) {
        float *projection = terrain->material + column;
        projection[2] = (projection[3] - projection[2]) * .5f;
    }
    static const jint compare[] = {
        MTLCompareFunctionNever, MTLCompareFunctionGreater, MTLCompareFunctionEqual,
        MTLCompareFunctionGreaterEqual, MTLCompareFunctionLess, MTLCompareFunctionNotEqual,
        MTLCompareFunctionLessEqual, MTLCompareFunctionAlways
    };
    jint depth = terrain->flags[4];
    require(depth >= 0 && depth < 8, @"Invalid Metal depth function");
    terrain->flags[4] = compare[depth];
}

static void onAppKit(void (^action)(void)) {
    if ([NSThread isMainThread]) action();
    else dispatch_sync(dispatch_get_main_queue(), action);
}

JNIEXPORT jlong JNICALL Java_local_mcgl_render_backend_MetalTerrainWindow_openNative(
        JNIEnv *env, jclass type, jstring title, jint width, jint height, jboolean validation, jboolean passive,
        jlong hostWindow) {
    (void)type;
    @autoreleasepool {
        @try {
            jsize length = (*env)->GetStringLength(env, title);
            const jchar *units = (*env)->GetStringChars(env, title, NULL);
            if (!units) return 0;
            NSString *caption = [NSString stringWithCharacters:units length:(NSUInteger)length];
            (*env)->ReleaseStringChars(env, title, units);
            DirectSurface *surface = [DirectSurface new];
            initializeTerrain(surface, MTLPixelFormatBGRA8Unorm);
            onAppKit(^{
                NSWindowStyleMask style = NSWindowStyleMaskTitled | NSWindowStyleMaskClosable
                    | NSWindowStyleMaskResizable | NSWindowStyleMaskMiniaturizable;
                NSRect content = NSMakeRect(0, 0, width, height);
                surface.window = hostWindow ? resource(hostWindow) : passive ? [[DirectMetalPanel alloc] initWithContentRect:content
                    styleMask:style | NSWindowStyleMaskNonactivatingPanel backing:NSBackingStoreBuffered defer:NO]
                    : [[NSWindow alloc] initWithContentRect:content styleMask:style backing:NSBackingStoreBuffered defer:NO];
                surface.window.title = caption;
                DirectMetalView *view = [[DirectMetalView alloc] initWithFrame:hostWindow ? surface.window.contentView.bounds : content];
                view.wantsLayer = YES;
                surface.view = view;
                if (hostWindow) {
                    view.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
                    [surface.window.contentView addSubview:view positioned:NSWindowAbove relativeTo:nil];
                    NSNotificationCenter *center = NSNotificationCenter.defaultCenter;
                    __weak DirectSurface *weakSurface = surface;
                    surface.windowObservers = @[
                        [center addObserverForName:NSWindowDidResizeNotification object:surface.window queue:nil
                                        usingBlock:^(NSNotification *event) { [weakSurface windowDidResize:event]; }],
                        [center addObserverForName:NSWindowDidChangeBackingPropertiesNotification object:surface.window queue:nil
                                        usingBlock:^(NSNotification *event) { [weakSurface windowDidChangeBackingProperties:event]; }],
                        [center addObserverForName:NSWindowWillCloseNotification object:surface.window queue:nil
                                        usingBlock:^(NSNotification *event) { [weakSurface windowWillClose:event]; }]
                    ];
                } else {
                    surface.window.releasedWhenClosed = NO;
                    surface.window.delegate = surface;
                    surface.window.contentView = view;
                }
                surface.layer = (CAMetalLayer *)view.layer;
                surface.layer.device = surface.device;
                surface.layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
                surface.layer.framebufferOnly = !validation;
                surface.layer.opaque = YES;
                surface.layer.allowsNextDrawableTimeout = YES;
                [surface updateSize];
                if (hostWindow) {
                    NSLog(@"[MCGL Metal] attached CAMetalLayer to the game window");
                } else if (passive) {
                    NSRect display = NSScreen.mainScreen.visibleFrame;
                    surface.window.hidesOnDeactivate = NO;
                    [surface.window setFrameTopLeftPoint:NSMakePoint(NSMaxX(display) - width - 24, NSMaxY(display) - 24)];
                    [surface.window orderFrontRegardless];
                } else {
                    [surface.window center];
                    [surface.window makeKeyAndOrderFront:nil];
                    [NSApp activateIgnoringOtherApps:YES];
                }
            });
            require(surface.window && surface.layer, @"Cannot create direct Metal window");
            return own(surface);
        } @catch (NSException *exception) {
            report(env, exception);
            return 0;
        }
    }
}

JNIEXPORT jlong JNICALL Java_local_mcgl_render_backend_MetalTerrainWindow_uploadMesh(
        JNIEnv *env, jclass type, jlong handle, jobject vertices, jobject indices, jint count, jint indexBytes) {
    (void)type;
    return uploadTerrainMesh(env, handle, vertices, indices, count, indexBytes);
}

JNIEXPORT jlong JNICALL Java_local_mcgl_render_backend_MetalTerrainWindow_uploadTexture(
        JNIEnv *env, jclass type, jlong handle, jint width, jint height, jobjectArray pixels,
        jint min, jint mag, jint s, jint t) {
    (void)type;
    return uploadTerrainTexture(env, handle, width, height, pixels, min, mag, s, t);
}

JNIEXPORT jboolean JNICALL Java_local_mcgl_render_backend_MetalTerrainWindow_presentNative(
        JNIEnv *env, jclass type, jlong handle, jobject commands, jint count, jobject batches,
        jint batchCount, jfloat red, jfloat green, jfloat blue, jobject readback, jboolean image) {
    (void)type;
    @autoreleasepool {
        @try {
            DirectSurface *surface = resource(handle);
            if (surface.closed) return JNI_FALSE;
            const Draw *draws = buffer(env, commands, (jlong)count * sizeof(Draw));
            const DirectPass *passes = buffer(env, batches, (jlong)batchCount * sizeof(DirectPass));
            for (jint i = 0; i < batchCount; i++) {
                require(passes[i].first >= 0 && passes[i].first <= count
                    && passes[i].count >= 0 && passes[i].count <= count - passes[i].first,
                    @"Invalid Metal terrain pass range");
            }
            id<CAMetalDrawable> drawable = [surface.layer nextDrawable];
            if (!drawable) return JNI_FALSE;
            NSUInteger width = drawable.texture.width, height = drawable.texture.height;
            if (surface.depth.width != width || surface.depth.height != height) {
                surface.depth = target(surface.device, MTLPixelFormatDepth32Float_Stencil8, width, height);
            }
            require(surface.depth != nil, @"Cannot allocate direct Metal depth buffer");
            MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
            pass.colorAttachments[0].texture = drawable.texture;
            pass.colorAttachments[0].loadAction = MTLLoadActionClear;
            pass.colorAttachments[0].storeAction = MTLStoreActionStore;
            pass.colorAttachments[0].clearColor = MTLClearColorMake(red, green, blue, 1);
            pass.depthAttachment.texture = surface.depth;
            pass.depthAttachment.loadAction = MTLLoadActionClear;
            pass.depthAttachment.storeAction = MTLStoreActionStore;
            pass.depthAttachment.clearDepth = 0;
            pass.stencilAttachment.texture = surface.depth;
            pass.stencilAttachment.loadAction = MTLLoadActionClear;
            pass.stencilAttachment.storeAction = MTLStoreActionStore;
            pass.stencilAttachment.clearStencil = batchCount && (passes[0].flags[9] & 2) ? passes[0].flags[13] : 0;
            id<MTLCommandBuffer> frame = [surface.queue commandBuffer];
            id<MTLRenderCommandEncoder> encoder = [frame renderCommandEncoderWithDescriptor:pass];
            require(encoder != nil, @"Cannot encode direct Metal frame");
            for (jint i = 0; i < batchCount; i++) {
                DirectPass terrain = passes[i];
                if (terrain.count == 0) continue;
                if (terrain.flags[9] != 0 && i > 0) {
                    [encoder endEncoding];
                    pass.colorAttachments[0].loadAction = MTLLoadActionLoad;
                    pass.depthAttachment.loadAction = terrain.flags[9] & 1 ? MTLLoadActionClear : MTLLoadActionLoad;
                    pass.stencilAttachment.loadAction = terrain.flags[9] & 2 ? MTLLoadActionClear : MTLLoadActionLoad;
                    pass.stencilAttachment.clearStencil = terrain.flags[13];
                    encoder = [frame renderCommandEncoderWithDescriptor:pass];
                    require(encoder != nil, @"Cannot start Metal depth pass");
                }
                reverseTerrainDepth(&terrain);
                encodeTerrain(surface, encoder, draws + terrain.first, terrain.count, terrain.material, terrain.flags, nil);
            }
            [encoder endEncoding];

            id<MTLBuffer> samples = nil;
            NSUInteger rowBytes = (width * 4 + 255) & ~((NSUInteger)255);
            uint8_t *output = NULL;
            if (readback) {
                output = buffer(env, readback, image ? (jlong)(width * height * 4) : 8);
                samples = [surface.device newBufferWithLength:image ? rowBytes * height : 512 options:MTLResourceStorageModeShared];
                id<MTLBlitCommandEncoder> blit = [frame blitCommandEncoder];
                require(samples && blit, @"Cannot encode drawable readback");
                if (image) {
                    [blit copyFromTexture:drawable.texture sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0)
                        sourceSize:MTLSizeMake(width, height, 1) toBuffer:samples destinationOffset:0
                        destinationBytesPerRow:rowBytes destinationBytesPerImage:0];
                } else for (NSUInteger i = 0; i < 2; i++) {
                    MTLOrigin origin = i == 0 ? MTLOriginMake(width / 2, height / 2, 0) : MTLOriginMake(0, 0, 0);
                    [blit copyFromTexture:drawable.texture sourceSlice:0 sourceLevel:0 sourceOrigin:origin
                        sourceSize:MTLSizeMake(1, 1, 1) toBuffer:samples destinationOffset:i * 256
                        destinationBytesPerRow:256 destinationBytesPerImage:0];
                }
                [blit endEncoding];
            }
            __weak DirectSurface *weakSurface = surface;
            [drawable addPresentedHandler:^(id<MTLDrawable> presented) {
                DirectSurface *current = weakSurface;
                if (current && presented.presentedTime > 0) {
                    @synchronized(current) { current.presented += 1; }
                }
            }];
            [frame presentDrawable:drawable];
            [frame commit];
            surface.lastFrame = frame;
            if (samples) {
                [frame waitUntilCompleted];
                require(frame.status == MTLCommandBufferStatusCompleted, frame.error.localizedDescription ?: @"Direct Metal frame failed");
                if (image) {
                    for (NSUInteger row = 0; row < height; row++)
                        memcpy(output + row * width * 4, (const uint8_t *)samples.contents + row * rowBytes, width * 4);
                } else {
                    memcpy(output, samples.contents, 4);
                    memcpy(output + 4, (const uint8_t *)samples.contents + 256, 4);
                }
            }
            return JNI_TRUE;
        } @catch (NSException *exception) {
            report(env, exception);
            return JNI_FALSE;
        }
    }
}

JNIEXPORT jlong JNICALL Java_local_mcgl_render_backend_MetalTerrainWindow_presentedNative(
        JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    DirectSurface *surface = resource(handle);
    return (jlong)surface.presented;
}

JNIEXPORT void JNICALL Java_local_mcgl_render_backend_MetalTerrainWindow_titleNative(
        JNIEnv *env, jclass type, jlong handle, jstring title) {
    (void)type;
    @autoreleasepool {
        const char *bytes = (*env)->GetStringUTFChars(env, title, NULL);
        if (!bytes) return;
        NSString *caption = [NSString stringWithUTF8String:bytes];
        (*env)->ReleaseStringUTFChars(env, title, bytes);
        DirectSurface *surface = resource(handle);
        onAppKit(^{ if (!surface.closed) surface.window.title = caption; });
    }
}

JNIEXPORT jboolean JNICALL Java_local_mcgl_render_backend_MetalTerrainWindow_isVisibleNative(
        JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    DirectSurface *surface = resource(handle);
    __block BOOL visible;
    // AppKit's occlusion notification can lag a passive panel's actual visibility.
    // Let CAMetalLayer decide when a drawable is available.
    onAppKit(^{ visible = surface.window.isVisible && !surface.window.isMiniaturized; });
    return visible ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_local_mcgl_render_backend_MetalTerrainWindow_presentationPolicyNative(
        JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    DirectSurface *surface = resource(handle);
    __block BOOL exposed;
    onAppKit(^{
        exposed = surface.window.isVisible && !surface.window.isMiniaturized
            && (surface.window.occlusionState & NSWindowOcclusionStateVisible) != 0;
    });
    return (jint)((NSProcessInfo.processInfo.thermalState << 1) | (exposed ? 1 : 0));
}

JNIEXPORT jboolean JNICALL Java_local_mcgl_render_backend_MetalTerrainWindow_isClosedNative(
        JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    DirectSurface *surface = resource(handle);
    return surface.closed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_local_mcgl_render_backend_MetalTerrainWindow_resizeNative(
        JNIEnv *env, jclass type, jlong handle, jint width, jint height) {
    (void)env;
    (void)type;
    DirectSurface *surface = resource(handle);
    onAppKit(^{
        if (!surface.closed) {
            [surface.window setContentSize:NSMakeSize(width, height)];
            [surface updateSize];
        }
    });
}

JNIEXPORT jlong JNICALL Java_local_mcgl_render_backend_MetalTerrainWindow_sizeNative(
        JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    DirectSurface *surface = resource(handle);
    __block uint64_t size;
    onAppKit(^{
        CGSize drawable = surface.layer.drawableSize;
        size = ((uint64_t)drawable.width << 32) | (uint32_t)drawable.height;
    });
    return (jlong)size;
}

JNIEXPORT void JNICALL Java_local_mcgl_render_backend_MetalTerrainWindow_closeNative(
        JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    @autoreleasepool {
        DirectSurface *surface = (__bridge_transfer DirectSurface *)(void *)(uintptr_t)handle;
        [surface.lastFrame waitUntilCompleted];
        onAppKit(^{
            surface.closed = YES;
            if (surface.windowObservers) {
                for (id observer in surface.windowObservers) [NSNotificationCenter.defaultCenter removeObserver:observer];
                surface.windowObservers = nil;
                [surface.view removeFromSuperview];
            } else {
                surface.window.delegate = nil;
                [surface.window close];
            }
            surface.window = nil;
        });
    }
}

JNIEXPORT void JNICALL Java_local_mcgl_render_backend_MetalTerrainWindow_releaseResource(
        JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    releaseTerrainResource(handle);
}
