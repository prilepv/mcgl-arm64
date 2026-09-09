#include <jni.h>
#import <Cocoa/Cocoa.h>
#import <OpenGL/OpenGL.h>
#import <objc/runtime.h>

// AppKit owns the Space, animation and saved window rectangle. Keep GLFW's
// delegate intact: it still owns resize/focus/input and locked NSGL updates.
// The bundled delegate forwards its two failure callbacks as notifications.
static char mcgl_fullscreen_key;
@interface MCGLNativeFullscreen : NSObject {
@public
    NSWindow *window; // weak: explicitly detached before GLFW destroys it
    BOOL desired, actual, transitioning;
}
- (id)initWithWindow:(NSWindow *)value;
- (void)reconcile;
- (void)changed:(NSNotification *)notification;
@end

@implementation MCGLNativeFullscreen
- (id)initWithWindow:(NSWindow *)value {
    self = [super init];
    if (self) {
        window = value;
        desired = actual = (window.styleMask & NSWindowStyleMaskFullScreen) != 0;
        NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
        for (NSString *name in @[NSWindowWillEnterFullScreenNotification, NSWindowDidEnterFullScreenNotification,
                NSWindowWillExitFullScreenNotification, NSWindowDidExitFullScreenNotification,
                @"MCGLWindowDidFailToEnterFullScreen", @"MCGLWindowDidFailToExitFullScreen"])
            [center addObserver:self selector:@selector(changed:) name:name object:window];
        window.collectionBehavior = (window.collectionBehavior & ~NSWindowCollectionBehaviorFullScreenNone)
                | NSWindowCollectionBehaviorFullScreenPrimary;
    }
    return self;
}
- (void)reconcile {
    if (transitioning || desired == actual) return;
    transitioning = YES;
    [window toggleFullScreen:nil];
}
- (void)changed:(NSNotification *)notification {
    NSString *name = notification.name;
    if ([name isEqualToString:NSWindowWillEnterFullScreenNotification]
            || [name isEqualToString:NSWindowWillExitFullScreenNotification]) {
        // A system/green-button transition has no preceding game request.
        if (!transitioning) desired = [name isEqualToString:NSWindowWillEnterFullScreenNotification];
        transitioning = YES;
    } else if ([name isEqualToString:NSWindowDidEnterFullScreenNotification]
            || [name isEqualToString:NSWindowDidExitFullScreenNotification]) {
        actual = [name isEqualToString:NSWindowDidEnterFullScreenNotification];
        transitioning = NO;
        // A queued reversal is reconciled at the next event pump, outside the
        // completion callback and only after AppKit finishes its own cleanup.
    } else {
        desired = actual = (window.styleMask & NSWindowStyleMaskFullScreen) != 0;
        transitioning = NO;
        fprintf(stderr, "[MCGL Fullscreen] AppKit transition failed; synchronized to native window\n");
    }
}
- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    [super dealloc];
}
@end

static NSWindow *mcglFullscreenWindow(JNIEnv *environment, jlong handle) {
    if (![NSThread isMainThread] || handle == 0) {
        jclass error = (*environment)->FindClass(environment, "java/lang/IllegalStateException");
        (*environment)->ThrowNew(environment, error, "Native fullscreen requires a live window on the AppKit thread");
        return nil;
    }
    return (NSWindow *)(uintptr_t)handle;
}
static MCGLNativeFullscreen *mcglFullscreen(JNIEnv *environment, jlong handle) {
    NSWindow *window = mcglFullscreenWindow(environment, handle);
    if (!window) return nil;
    MCGLNativeFullscreen *state = objc_getAssociatedObject(window, &mcgl_fullscreen_key);
    if (!state) {
        jclass error = (*environment)->FindClass(environment, "java/lang/IllegalStateException");
        (*environment)->ThrowNew(environment, error, "Native fullscreen is not attached");
    }
    return state;
}
JNIEXPORT void JNICALL
Java_local_mcgl_platform_macos_MacOSMainThread_attachFullscreen(JNIEnv *environment, jclass type, jlong handle) {
    (void)type;
    NSWindow *window = mcglFullscreenWindow(environment, handle);
    if (!window || objc_getAssociatedObject(window, &mcgl_fullscreen_key)) return;
    MCGLNativeFullscreen *state = [[MCGLNativeFullscreen alloc] initWithWindow:window];
    objc_setAssociatedObject(window, &mcgl_fullscreen_key, state, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    [state release];
}
JNIEXPORT void JNICALL
Java_local_mcgl_platform_macos_MacOSMainThread_requestFullscreen(JNIEnv *environment, jclass type, jlong handle, jboolean value) {
    (void)type;
    MCGLNativeFullscreen *state = mcglFullscreen(environment, handle);
    if (!state) return;
    state->desired = value == JNI_TRUE;
    [state reconcile];
}
JNIEXPORT jint JNICALL
Java_local_mcgl_platform_macos_MacOSMainThread_fullscreenState(JNIEnv *environment, jclass type, jlong handle, jboolean reconcile) {
    (void)type;
    MCGLNativeFullscreen *state = mcglFullscreen(environment, handle);
    if (!state) return 0;
    if (reconcile) [state reconcile];
    return (state->desired ? 1 : 0) | (state->actual ? 2 : 0) | (state->transitioning ? 4 : 0);
}
JNIEXPORT void JNICALL
Java_local_mcgl_platform_macos_MacOSMainThread_detachFullscreen(JNIEnv *environment, jclass type, jlong handle) {
    (void)type;
    NSWindow *window = mcglFullscreenWindow(environment, handle);
    if (window) objc_setAssociatedObject(window, &mcgl_fullscreen_key, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

// The owner holds this between frame boundaries. GLFW's Cocoa update paths
// acquire the same CGL lock. Never hold it while waiting for the AppKit thread.
static _Thread_local CGLContextObj mcgl_render_context;

JNIEXPORT void JNICALL
Java_local_mcgl_platform_macos_MacOSMainThread_lockContext(JNIEnv *environment, jclass type) {
    (void)type;
    if (mcgl_render_context != NULL) return;
    CGLContextObj context = CGLGetCurrentContext();
    if (context == NULL) {
        jclass error = (*environment)->FindClass(environment, "java/lang/IllegalStateException");
        (*environment)->ThrowNew(environment, error, "No current render context to lock");
        return;
    }
    CGLLockContext(context);
    mcgl_render_context = context;
}

JNIEXPORT void JNICALL
Java_local_mcgl_platform_macos_MacOSMainThread_unlockContext(JNIEnv *environment, jclass type) {
    (void)environment; (void)type;
    if (mcgl_render_context == NULL) return;
    CGLUnlockContext(mcgl_render_context);
    mcgl_render_context = NULL;
}

JNIEXPORT jboolean JNICALL
Java_local_mcgl_platform_macos_MacOSMainThread_isContextLocked(JNIEnv *environment, jclass type) {
    (void)environment; (void)type;
    return mcgl_render_context != NULL ? JNI_TRUE : JNI_FALSE;
}

@interface MCGLMainThreadTask : NSObject {
@public
    JavaVM *vm;
    jobject action;
    jmethodID run;
    jthrowable failure;
    BOOL executed;
}
- (void)execute;
@end

@implementation MCGLMainThreadTask
- (void)execute {
    @autoreleasepool {
        JNIEnv *environment = NULL;
        BOOL attached = NO;
        if ((*vm)->GetEnv(vm, (void **)&environment, JNI_VERSION_1_8) != JNI_OK) {
            if ((*vm)->AttachCurrentThread(vm, (void **)&environment, NULL) != JNI_OK)
                return;
            attached = YES;
        }
        (*environment)->CallVoidMethod(environment, action, run);
        executed = YES;
        if ((*environment)->ExceptionCheck(environment)) {
            jthrowable local = (*environment)->ExceptionOccurred(environment);
            (*environment)->ExceptionClear(environment);
            failure = (jthrowable)(*environment)->NewGlobalRef(environment, local);
            (*environment)->DeleteLocalRef(environment, local);
        }
        if (attached) (*vm)->DetachCurrentThread(vm);
    }
}
@end

JNIEXPORT jboolean JNICALL
Java_local_mcgl_platform_macos_MacOSMainThread_isMainThread(JNIEnv *environment, jclass type) {
    (void)environment; (void)type;
    return [NSThread isMainThread] ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_local_mcgl_platform_macos_MacOSMainThread_invoke(JNIEnv *environment, jclass type, jobject action) {
    (void)type;
    if (action == NULL) {
        jclass error = (*environment)->FindClass(environment, "java/lang/NullPointerException");
        (*environment)->ThrowNew(environment, error, "Missing main-thread action");
        return;
    }
    jclass action_type = (*environment)->GetObjectClass(environment, action);
    jmethodID run = (*environment)->GetMethodID(environment, action_type, "run", "()V");
    (*environment)->DeleteLocalRef(environment, action_type);
    if (run == NULL) return;
    if ([NSThread isMainThread]) {
        (*environment)->CallVoidMethod(environment, action, run);
        return;
    }
    MCGLMainThreadTask *task = [[MCGLMainThreadTask alloc] init];
    (*environment)->GetJavaVM(environment, &task->vm);
    task->action = (*environment)->NewGlobalRef(environment, action);
    task->run = run;
    if (task->action != NULL) {
        CGLContextObj released_context = mcgl_render_context;
        if (released_context != NULL) {
            CGLUnlockContext(released_context);
            mcgl_render_context = NULL;
        }
        [task performSelectorOnMainThread:@selector(execute) withObject:nil waitUntilDone:YES];
        if (released_context != NULL) {
            CGLLockContext(released_context);
            mcgl_render_context = released_context;
        }
        (*environment)->DeleteGlobalRef(environment, task->action);
        if (!task->executed) {
            jclass error = (*environment)->FindClass(environment, "java/lang/IllegalStateException");
            (*environment)->ThrowNew(environment, error, "Could not attach the macOS main thread to Java");
        } else if (task->failure != NULL) {
            (*environment)->Throw(environment, task->failure);
            (*environment)->DeleteGlobalRef(environment, task->failure);
        }
    }
    [task release];
}
