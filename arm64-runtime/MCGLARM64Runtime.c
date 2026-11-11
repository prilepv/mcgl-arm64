#include <errno.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#import <Cocoa/Cocoa.h>
#ifndef MCGL_DIRECT_METAL
#import <OpenGL/OpenGL.h>
#endif

typedef jint (*create_java_vm_fn)(JavaVM **, void **, void *);

static int mcgl_fps_limit(void) {
    const char *value = getenv("MCGL_FPS_LIMIT");
    if (value == NULL || value[0] == '\0') return 0;
    char *end = NULL;
    errno = 0;
    long fps = strtol(value, &end, 10);
    if (errno == 0 && end != value && *end == '\0' &&
        (fps == 0 || fps == 60 || fps == 120 || fps == 144 ||
         fps == 165 || fps == 180 || fps == 240)) return (int)fps;
    fprintf(stderr, "Runtime: invalid FPS limit; launcher cap disabled.\n");
    return 0;
}

static int mcgl_memory_limit_mb(void) {
    const int default_memory_mb = 2048;
    const int supported_memory_mb[] = {1024, 2048, 4096, 6144, 8192};
    const char *value = getenv("MCGL_MEMORY_MB");
    if (value == NULL || value[0] == '\0') {
        return default_memory_mb;
    }

    char *end = NULL;
    errno = 0;
    long parsed = strtol(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0') {
        fprintf(stderr,
                "Runtime: invalid MCGL_MEMORY_MB '%s'; using %d MB.\n",
                value, default_memory_mb);
        return default_memory_mb;
    }

    for (size_t index = 0;
         index < sizeof(supported_memory_mb) / sizeof(supported_memory_mb[0]);
         index++) {
        if (parsed == supported_memory_mb[index]) {
            return (int)parsed;
        }
    }

    fprintf(stderr,
            "Runtime: unsupported memory limit %ld MB; using %d MB.\n",
            parsed, default_memory_mb);
    return default_memory_mb;
}

static int mcgl_initial_memory_mb(int maximum_memory_mb) {
    const int default_memory_mb = 512;
    const int supported_memory_mb[] = {512, 1024, 2048, 4096, 6144, 8192};
    const char *value = getenv("MCGL_INITIAL_MEMORY_MB");
    if (value == NULL || value[0] == '\0') {
        // Compatibility with launchers up to 1.6.4.
        const char *preallocate_memory = getenv("MCGL_PREALLOCATE_MEMORY");
        return preallocate_memory != NULL && strcmp(preallocate_memory, "1") == 0
            ? maximum_memory_mb : default_memory_mb;
    }

    char *end = NULL;
    errno = 0;
    long parsed = strtol(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0') {
        fprintf(stderr,
                "Runtime: invalid MCGL_INITIAL_MEMORY_MB '%s'; using %d MB.\n",
                value, default_memory_mb);
        return default_memory_mb;
    }

    for (size_t index = 0;
         index < sizeof(supported_memory_mb) / sizeof(supported_memory_mb[0]);
         index++) {
        if (parsed == supported_memory_mb[index]) {
            if (parsed > maximum_memory_mb) {
                fprintf(stderr,
                        "Runtime: initial heap %ld MB exceeds maximum %d MB; clamping it.\n",
                        parsed, maximum_memory_mb);
                return maximum_memory_mb;
            }
            return (int)parsed;
        }
    }

    fprintf(stderr,
            "Runtime: unsupported initial memory %ld MB; using %d MB.\n",
            parsed, default_memory_mb);
    return default_memory_mb;
}

typedef struct {
    JavaVM *vm;
    jclass launcher_class;
    jmethodID main_method;
    jobjectArray arguments;
    int exit_code;
} game_thread_context;

static void bound_game_shutdown(int seconds, int exit_code) {
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)seconds * NSEC_PER_SEC),
                   dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
        fprintf(stderr, "Runtime: game shutdown timed out.\n");
        _Exit(exit_code);
    });
}

@interface MCGLRuntimeApplication : NSApplication
@end

@implementation MCGLRuntimeApplication
- (void)terminate:(id)sender {
    // GLFW first forwards Quit to the game loop as a window close request.
    static dispatch_once_t quit_requested;
    dispatch_once(&quit_requested, ^{ bound_game_shutdown(8, 0); });
    [super terminate:sender];
}
@end

@interface MCGLRuntimeLifecycle : NSObject
+ (void)stopApplicationLoop;
@end

@implementation MCGLRuntimeLifecycle
+ (void)stopApplicationLoop {
    [NSApp stop:nil];
    NSEvent *wake_event = [NSEvent otherEventWithType:NSEventTypeApplicationDefined
                                             location:NSZeroPoint
                                        modifierFlags:0
                                            timestamp:0
                                         windowNumber:0
                                              context:nil
                                              subtype:0
                                                data1:0
                                                data2:0];
    [NSApp postEvent:wake_event atStart:NO];
}
@end

static void *run_game_thread(void *opaque_context) {
    @autoreleasepool {
        game_thread_context *context = (game_thread_context *)opaque_context;
        JNIEnv *environment = NULL;
        jint attach_result = (*context->vm)->AttachCurrentThread(
            context->vm, (void **)&environment, NULL);
        if (attach_result != JNI_OK) {
            fprintf(stderr, "Runtime: could not attach the dedicated Java game thread (%d).\n",
                    attach_result);
            context->exit_code = 77;
        } else {
            fprintf(stdout, "Runtime: calling MCGL DirectLauncher on the dedicated Java game thread.\n");
            (*environment)->CallStaticVoidMethod(environment,
                                                  context->launcher_class,
                                                  context->main_method,
                                                  context->arguments);
            if ((*environment)->ExceptionCheck(environment)) {
                (*environment)->ExceptionDescribe(environment);
                (*environment)->ExceptionClear(environment);
                context->exit_code = 75;
            }

            (*environment)->DeleteGlobalRef(environment, context->launcher_class);
            (*environment)->DeleteGlobalRef(environment, context->arguments);
            (*context->vm)->DetachCurrentThread(context->vm);
        }

        [MCGLRuntimeLifecycle performSelectorOnMainThread:@selector(stopApplicationLoop)
                                               withObject:nil
                                            waitUntilDone:NO];
    }
    return NULL;
}

static int open_owned_fifo(const char *path, int flags) {
    struct stat metadata;
    if (strncmp(path, "/private/tmp/mcgl-", 18) != 0) {
        errno = EINVAL;
        return -1;
    }
    if (lstat(path, &metadata) != 0 || !S_ISFIFO(metadata.st_mode) ||
            metadata.st_uid != geteuid()) {
        errno = EINVAL;
        return -1;
    }
    return open(path, flags | O_NOFOLLOW);
}

int main(int argc, char **argv) {
    if (argc != 5)
        return 64;

    const char *login = argv[1];
    const char *password_fifo = argv[2];
    const char *log_fifo = argv[3];
    const char *game_directory = argv[4];

    int log_fd = open_owned_fifo(log_fifo, O_WRONLY);
    if (log_fd < 0)
        return 65;
    if (dup2(log_fd, STDOUT_FILENO) < 0 || dup2(log_fd, STDERR_FILENO) < 0)
        return 66;
    close(log_fd);
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    int password_fd = open_owned_fifo(password_fifo, O_RDONLY);
    if (password_fd < 0) {
        perror("Cannot open password FIFO");
        return 67;
    }
    if (dup2(password_fd, STDIN_FILENO) < 0) {
        perror("Cannot connect password FIFO");
        return 68;
    }
    close(password_fd);
    unlink(password_fifo);
    unlink(log_fifo);

    NSAutoreleasePool *appkit_pool = [[NSAutoreleasePool alloc] init];
    NSString *runtime_bundle_path = [[NSBundle mainBundle] bundlePath];
    NSString *resources_root = [runtime_bundle_path stringByDeletingLastPathComponent];
    NSString *java_home_path = [resources_root stringByAppendingPathComponent:@"java21-arm64/Home"];
    NSString *java_runtime_home_path = java_home_path;
    NSString *jvm_path = [java_runtime_home_path stringByAppendingPathComponent:@"lib/server/libjvm.dylib"];
    NSString *game_path = [NSString stringWithUTF8String:game_directory];
    NSString *native_directory = [game_path stringByAppendingPathComponent:@"bin/natives"];
    NSString *custom_library_directory = [game_path stringByAppendingPathComponent:@"bin/lib"];

    if (game_path == nil || ![game_path isAbsolutePath]) {
        fprintf(stderr, "Runtime: the portable game directory is invalid.\n");
        [appkit_pool drain];
        return 80;
    }
    if (access([jvm_path fileSystemRepresentation], R_OK) != 0) {
        fprintf(stderr, "Runtime: bundled ARM64 Java VM is missing.\n");
        [appkit_pool drain];
        return 81;
    }
    if (chdir([game_path fileSystemRepresentation]) != 0) {
        perror("Cannot enter MCGL ARM64 game directory");
        [appkit_pool drain];
        return 69;
    }
    setenv("JAVA_HOME", [java_home_path fileSystemRepresentation], 1);

    // Bootstrap Cocoa before the headless JVM can establish its own
    // AWT/AppKit state. The first macOS thread remains
    // in NSApplication's event loop; the Java game loop runs on a worker and
    // synchronously schedules LWJGL window operations back to this thread.
    NSApplication *application = [MCGLRuntimeApplication sharedApplication];
    [application setActivationPolicy:NSApplicationActivationPolicyRegular];
    [application finishLaunching];

    NSArray *screens = [NSScreen screens];
    fprintf(stdout, "Runtime: Cocoa initialized before Java VM; screens=%lu mainThread=%s.\n",
            (unsigned long)[screens count], [NSThread isMainThread] ? "true" : "false");

#ifndef MCGL_DIRECT_METAL
    const char *renderer_choice = getenv("MCGL_RENDERER");
    int use_metal = renderer_choice && strcmp(renderer_choice, "metal") == 0;
    NSString *metal_library = [resources_root stringByAppendingPathComponent:@"libmcgl-metal-world-direct.dylib"];
    const char *requested_metal = getenv("MCGL_METAL_LIVE_LIBRARY");
    if (use_metal && (!requested_metal || strcmp(requested_metal, [metal_library fileSystemRepresentation]) != 0
            || access(requested_metal, R_OK) != 0)) {
        fprintf(stderr, "Runtime: the bundled Metal library is unavailable.\n");
        [appkit_pool drain];
        return 76;
    }
    if (!use_metal) {
        NSOpenGLPixelFormatAttribute probe_attributes[] = {
            NSOpenGLPFAAccelerated,
            NSOpenGLPFANoRecovery,
            NSOpenGLPFADoubleBuffer,
            NSOpenGLPFAAlphaSize, (NSOpenGLPixelFormatAttribute)8,
            NSOpenGLPFAColorSize, (NSOpenGLPixelFormatAttribute)24,
            NSOpenGLPFADepthSize, (NSOpenGLPixelFormatAttribute)24,
            NSOpenGLPFAStencilSize, (NSOpenGLPixelFormatAttribute)8,
            NSOpenGLPFAOpenGLProfile, NSOpenGLProfileVersionLegacy,
            (NSOpenGLPixelFormatAttribute)0
        };
        NSOpenGLPixelFormat *probe_format = [[NSOpenGLPixelFormat alloc] initWithAttributes:probe_attributes];
        if (probe_format == nil) {
            fprintf(stderr, "Runtime: Cocoa OpenGL 2.1 preflight failed before Java VM.\n");
            [appkit_pool drain];
            return 76;
        }
        fprintf(stdout, "Runtime: Cocoa OpenGL 2.1 preflight succeeded before Java VM.\n");
        [probe_format release];
    }
#endif

    char java_library_path[2048];
    char lwjgl_library_path[2048];
    char jinput_library_path[2048];
    char java_home_option[2048];
#ifndef MCGL_DIRECT_METAL
    char preview_class_path[4096], metal_window_path[2048];
#endif
    snprintf(java_library_path, sizeof(java_library_path),
             "-Djava.library.path=%s:%s",
             [custom_library_directory fileSystemRepresentation],
             [native_directory fileSystemRepresentation]);
    snprintf(lwjgl_library_path, sizeof(lwjgl_library_path),
             "-Dorg.lwjgl.librarypath=%s", [native_directory fileSystemRepresentation]);
    snprintf(jinput_library_path, sizeof(jinput_library_path),
             "-Dnet.java.games.input.librarypath=%s", [native_directory fileSystemRepresentation]);
    snprintf(java_home_option, sizeof(java_home_option),
             "-Djava.home=%s", [java_runtime_home_path fileSystemRepresentation]);

    JavaVMOption options[24];
    jint option_count = 0;
#define ADD_VM_OPTION(value) do { \
    if (option_count >= (jint)(sizeof(options) / sizeof(options[0]))) { \
        fprintf(stderr, "Runtime: JVM option capacity exceeded.\n"); \
        return 82; \
    } \
    options[option_count].optionString = (value); \
    options[option_count].extraInfo = NULL; \
    option_count++; \
} while (0)

    char initial_heap_option[64];
    char maximum_heap_option[64];
    int memory_limit_mb = mcgl_memory_limit_mb();
    int initial_memory_mb = mcgl_initial_memory_mb(memory_limit_mb);
    snprintf(initial_heap_option, sizeof(initial_heap_option),
             "-Xms%dM", initial_memory_mb);
    snprintf(maximum_heap_option, sizeof(maximum_heap_option),
             "-Xmx%dM", memory_limit_mb);
    ADD_VM_OPTION(initial_heap_option);
    ADD_VM_OPTION(maximum_heap_option);
    char fps_option[64];
    int fps_limit = mcgl_fps_limit();
    snprintf(fps_option, sizeof(fps_option), "-Dmcgl.fps.limit=%d", fps_limit);
    ADD_VM_OPTION(fps_option);
    if (fps_limit > 0) fprintf(stdout, "Runtime: launcher frame limit %d FPS.\n", fps_limit);
    else fprintf(stdout, "Runtime: launcher frame limit disabled.\n");
    fprintf(stdout,
            "Runtime: Java heap set to initial %d MB, maximum %d MB%s.\n",
            initial_memory_mb, memory_limit_mb,
            initial_memory_mb == memory_limit_mb ? " (preallocated)" : "");
    ADD_VM_OPTION("-Djava.awt.headless=true");
    // Preserve the peerless Applet and existing macOS foreground bridge.
    ADD_VM_OPTION("--add-opens=java.desktop/java.awt=ALL-UNNAMED");
    ADD_VM_OPTION("--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED");
    // LWJGL 2 WaveData.create(URL) uses this legacy WAV reader.
    ADD_VM_OPTION("--add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED");
    ADD_VM_OPTION("-Dsun.java2d.opengl=false");
    ADD_VM_OPTION(java_library_path);
    ADD_VM_OPTION(lwjgl_library_path);
    ADD_VM_OPTION(jinput_library_path);
    ADD_VM_OPTION("-Dapple.awt.application.name=Minecraft Galaxy ARM64");
#ifdef MCGL_DIRECT_METAL
    ADD_VM_OPTION("-Djava.class.path=mcgl-nativewindow-patch.jar:Minecraft.jar:bin/lwjgl.jar");
    const char *verify_metal = getenv("MCGL_VERIFY_DIRECT_METAL");
    if (verify_metal != NULL && strcmp(verify_metal, "1") == 0)
        ADD_VM_OPTION("-Xlog:class+load=info:file=direct-metal-classes.log");
#else
    const char *class_path = "-Djava.class.path=mcgl-nativewindow-patch.jar:Minecraft.jar";
    NSString *preview_library = [resources_root stringByAppendingPathComponent:@"libmcgl-metal-terrain-preview.dylib"];
    const char *requested_preview = getenv("MCGL_METAL_TERRAIN_LIBRARY");
    NSString *bgfx_library = [resources_root stringByAppendingPathComponent:@"Bgfx/bgfx-window.dylib"];
    const char *requested_bgfx = getenv("MCGL_BGFX_LIVE_LIBRARY");
    int use_bgfx_preview = requested_bgfx
        && strcmp(requested_bgfx, [bgfx_library fileSystemRepresentation]) == 0 && access(requested_bgfx, R_OK) == 0;
    if (!use_bgfx_preview) unsetenv("MCGL_BGFX_LIVE_LIBRARY");
    const char *independent = getenv("MCGL_METAL_INDEPENDENT");
    int use_independent = use_metal && independent && strcmp(independent, "1") == 0;
    if (!use_independent) unsetenv("MCGL_METAL_INDEPENDENT");
    if (use_independent) {
        NSString *platform_path = [resources_root stringByAppendingPathComponent:@"Bgfx"];
        int path_length = snprintf(metal_window_path, sizeof(metal_window_path),
                "-Dmcgl.metal.windowpath=%s", [platform_path fileSystemRepresentation]);
        if (path_length <= 0 || (size_t)path_length >= sizeof(metal_window_path)
                || access([[platform_path stringByAppendingPathComponent:@"libglfw.dylib"] fileSystemRepresentation], R_OK) != 0
                || access([[platform_path stringByAppendingPathComponent:@"libmcgl-mainthread.dylib"] fileSystemRepresentation], R_OK) != 0) {
            fprintf(stderr, "Runtime: the independent Metal window libraries are unavailable.\n");
            [appkit_pool drain];
            return 76;
        }
        ADD_VM_OPTION(metal_window_path);
    }
    if (use_independent && !use_bgfx_preview) {
        fprintf(stderr, "Runtime: the independent bgfx Metal renderer is unavailable.\n");
        [appkit_pool drain];
        return 76;
    }
    if (use_metal || use_bgfx_preview || (requested_preview && strcmp(requested_preview, [preview_library fileSystemRepresentation]) == 0
            && access(requested_preview, R_OK) == 0)) {
        NSString *marker_path = [game_path stringByAppendingPathComponent:@".arm64-port-version"];
        NSString *marker = [NSString stringWithContentsOfFile:marker_path encoding:NSUTF8StringEncoding error:NULL];
        NSString *renderer = [resources_root stringByAppendingPathComponent:use_independent ? @"Bgfx/lwjgl.jar" : @"PortSupport/bin/lwjgl.jar"];
        NSString *controllers = [resources_root stringByAppendingPathComponent:@"PortSupport/bin/jinput.jar"];
        if (([marker isEqualToString:@"Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-17\n"]
                || [marker hasPrefix:@"Minecraft Galaxy ARM64 bootstrap lwjgl3-game-original-core-17-"])
                && [renderer rangeOfString:@":"].location == NSNotFound
                && access([renderer fileSystemRepresentation], R_OK) == 0
                && access([controllers fileSystemRepresentation], R_OK) == 0) {
            // The official URLClassLoader delegates to this parent. Load the whole
            // renderer together so JNI and package-private classes share one owner.
            int length = snprintf(preview_class_path, sizeof(preview_class_path), "%s:%s:%s",
                class_path, [renderer fileSystemRepresentation], [controllers fileSystemRepresentation]);
            if (length > 0 && (size_t)length < sizeof(preview_class_path)) {
                class_path = preview_class_path;
                fprintf(stdout, "Runtime: using bundled renderer for %s.\n",
                        use_independent ? "independent bgfx Metal"
                        : use_bgfx_preview ? (use_metal ? "the bgfx Metal game window" : "the bgfx terrain preview")
                                         : use_metal ? "the Metal game window" : "Metal terrain preview");
            }
        }
    }
    if (use_metal && class_path != preview_class_path) {
        fprintf(stderr, "Runtime: the saved client is incompatible with this Metal renderer.\n");
        [appkit_pool drain];
        return 76;
    }
    if (use_bgfx_preview && class_path != preview_class_path) {
        fprintf(stdout, "Runtime: bgfx terrain preview requires the bundled renderer and a compatible profile.\n");
        unsetenv("MCGL_BGFX_LIVE_LIBRARY");
    }
    ADD_VM_OPTION((char *)class_path);
#endif
    ADD_VM_OPTION(java_home_option);

    const char *multicore_memory = getenv("MCGL_MULTICORE_MEMORY");
    if (multicore_memory != NULL && strcmp(multicore_memory, "1") == 0) {
        ADD_VM_OPTION("-XX:+UseG1GC");
        ADD_VM_OPTION("-XX:+ParallelRefProcEnabled");
        ADD_VM_OPTION("-XX:MaxGCPauseMillis=35");
        ADD_VM_OPTION("-XX:+UseStringDeduplication");
        fprintf(stdout,
                "Runtime: multicore memory profile enabled (G1GC, parallel references, string deduplication).\n");
    } else {
        ADD_VM_OPTION("-XX:+UseParallelGC");
        fprintf(stdout, "Runtime: standard ParallelGC memory profile enabled.\n");
    }

    const char *graphics_diagnostics = getenv("MCGL_GRAPHICS_DIAGNOSTICS");
    if (graphics_diagnostics != NULL && strcmp(graphics_diagnostics, "1") == 0) {
        ADD_VM_OPTION("-Dmcgl.graphics.profile=true");
        fprintf(stdout,
                "Runtime: graphics frame diagnostics enabled (rendering is unchanged).\n");
    } else {
        fprintf(stdout, "Runtime: graphics frame diagnostics disabled.\n");
    }
    const char *chunk_vbo = getenv("MCGL_CHUNK_VBO");
    if (chunk_vbo != NULL && strcmp(chunk_vbo, "1") == 0) {
        fprintf(stdout, "Runtime: obsolete MCGL_CHUNK_VBO switch ignored; the game uses the Core chunk renderer.\n");
    }
#ifdef MCGL_DIRECT_METAL
    fprintf(stdout, "Runtime: direct Metal bootstrap; Cocoa window has no GL context.\n");
#else
    fprintf(stdout, use_independent ? "Runtime: independent bgfx Metal presentation.\n"
            : use_metal ? "Runtime: Metal presentation with an offscreen Core context.\n"
            : "Runtime: Core 4.1 game renderer with indexed chunk geometry; actual context is verified at window creation.\n");
#endif
#undef ADD_VM_OPTION

    void *jvm_library = dlopen([jvm_path fileSystemRepresentation], RTLD_NOW | RTLD_GLOBAL);
    if (jvm_library == NULL) {
        fprintf(stderr, "Cannot load ARM64 Java VM: %s\n", dlerror());
        return 70;
    }

    create_java_vm_fn create_vm =
        (create_java_vm_fn)dlsym(jvm_library, "JNI_CreateJavaVM");
    if (create_vm == NULL) {
        fprintf(stderr, "Cannot find JNI_CreateJavaVM: %s\n", dlerror());
        return 71;
    }

    JavaVMInitArgs vm_arguments;
    vm_arguments.version = JNI_VERSION_1_8;
    vm_arguments.nOptions = option_count;
    vm_arguments.options = options;
    vm_arguments.ignoreUnrecognized = JNI_FALSE;

    JavaVM *vm = NULL;
    JNIEnv *environment = NULL;
    fprintf(stdout, "Runtime: creating native ARM64 Java 21 VM on the macOS application main thread.\n");
    jint result = create_vm(&vm, (void **)&environment, &vm_arguments);
    if (result != JNI_OK) {
        fprintf(stderr, "JNI_CreateJavaVM returned %d.\n", result);
        return 72;
    }
    fprintf(stdout, "Runtime: ARM64 Java VM created; loading MCGL DirectLauncher.\n");

    jclass local_launcher_class = (*environment)->FindClass(environment, "local/mcgl/DirectLauncher");
    if (local_launcher_class == NULL) {
        (*environment)->ExceptionDescribe(environment);
        return 73;
    }
    jmethodID main_method = (*environment)->GetStaticMethodID(
        environment, local_launcher_class, "main", "([Ljava/lang/String;)V");
    if (main_method == NULL) {
        (*environment)->ExceptionDescribe(environment);
        return 74;
    }

    jclass string_class = (*environment)->FindClass(environment, "java/lang/String");
    jobjectArray main_arguments = (*environment)->NewObjectArray(
        environment, 1, string_class, NULL);
    jstring login_string = (*environment)->NewStringUTF(environment, login);
    (*environment)->SetObjectArrayElement(environment, main_arguments, 0, login_string);

    game_thread_context game_context;
    game_context.vm = vm;
    game_context.launcher_class = (jclass)(*environment)->NewGlobalRef(
        environment, local_launcher_class);
    game_context.main_method = main_method;
    game_context.arguments = (jobjectArray)(*environment)->NewGlobalRef(
        environment, main_arguments);
    game_context.exit_code = 0;
    if (game_context.launcher_class == NULL || game_context.arguments == NULL) {
        fprintf(stderr, "Runtime: could not retain Java launch objects.\n");
        return 78;
    }

    pthread_t game_thread;
    int thread_result = pthread_create(&game_thread, NULL, run_game_thread, &game_context);
    if (thread_result != 0) {
        fprintf(stderr, "Runtime: could not create the Java game thread (%d).\n", thread_result);
        return 79;
    }

    fprintf(stdout, "Runtime: Cocoa event loop active on the first macOS thread.\n");
    [application activateIgnoringOtherApps:YES];
    [application run];
    pthread_join(game_thread, NULL);

    fprintf(stdout, "Runtime: MCGL DirectLauncher returned; Cocoa event loop stopped.\n");
    bound_game_shutdown(5, game_context.exit_code);
    (*vm)->DestroyJavaVM(vm);
    [appkit_pool drain];
    return game_context.exit_code;
}
