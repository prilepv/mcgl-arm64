#include <jni.h>
#import <Cocoa/Cocoa.h>

static jint raised_window_count = 0;

@interface MCGLWindowActivator : NSObject
+ (void)activateAndRaiseWindows;
@end

@implementation MCGLWindowActivator
+ (void)activateAndRaiseWindows {
    @autoreleasepool {
        NSApplication *application = [NSApplication sharedApplication];
        [application setActivationPolicy:NSApplicationActivationPolicyRegular];
        [application unhide:nil];

        NSInteger raised = 0;
        for (NSWindow *window in [application windows]) {
            if ([window isMiniaturized])
                [window deminiaturize:nil];

            NSWindowCollectionBehavior behavior = [window collectionBehavior];
            behavior &= ~NSWindowCollectionBehaviorCanJoinAllSpaces;
            behavior |= NSWindowCollectionBehaviorMoveToActiveSpace;
            [window setCollectionBehavior:behavior];
            [window setLevel:NSNormalWindowLevel];
            [window setAlphaValue:1.0];
            [window center];
            [window orderFrontRegardless];
            [window makeMainWindow];
            [window makeKeyAndOrderFront:nil];
            raised++;
        }

        [application activateIgnoringOtherApps:YES];
        raised_window_count = (jint)raised;
        fprintf(stdout, "Cocoa bridge: raised %ld window(s) on mainThread=%s.\n",
                (long)raised, [NSThread isMainThread] ? "true" : "false");
    }
}
@end

JNIEXPORT jint JNICALL
Java_local_mcgl_CocoaWindowBridge_activateAndRaise(JNIEnv *environment, jclass bridgeClass) {
    (void)environment;
    (void)bridgeClass;

    if ([NSThread isMainThread])
        [MCGLWindowActivator activateAndRaiseWindows];
    else
        [MCGLWindowActivator performSelectorOnMainThread:@selector(activateAndRaiseWindows)
                                              withObject:nil
                                           waitUntilDone:YES];
    return raised_window_count;
}
