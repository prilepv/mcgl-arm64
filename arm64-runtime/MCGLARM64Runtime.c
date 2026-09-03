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
#import <OpenGL/OpenGL.h>

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
    NSString *java_home_path = [resources_root stringByAppendingPathComponent:@"java8-arm64/Home"];
    NSString *java_runtime_home_path = [java_home_path stringByAppendingPathComponent:@"jre"];
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

    // Bootstrap Cocoa and the legacy OpenGL renderer before the headless JVM
    // can establish its own AWT/AppKit state.  The first macOS thread remains
    // in NSApplication's event loop; the Java game loop runs on a worker and
    // synchronously schedules LWJGL window operations back to this thread.
    NSApplication *application = [NSApplication sharedApplication];
    [application setActivationPolicy:NSApplicationActivationPolicyRegular];
    [application finishLaunching];

    NSArray *screens = [NSScreen screens];
    fprintf(stdout, "Runtime: Cocoa initialized before Java VM; screens=%lu mainThread=%s.\n",
            (unsigned long)[screens count], [NSThread isMainThread] ? "true" : "false");

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
    NSOpenGLPixelFormat *probe_format = [[NSOpenGLPixelFormat alloc]
                                         initWithAttributes:probe_attributes];
    if (probe_format == nil) {
        fprintf(stderr, "Runtime: Cocoa OpenGL 2.1 preflight failed before Java VM.\n");
        [appkit_pool drain];
        return 76;
    }
    fprintf(stdout, "Runtime: Cocoa OpenGL 2.1 preflight succeeded before Java VM.\n");
    [probe_format release];

    char java_library_path[2048];
    char lwjgl_library_path[2048];
    char jinput_library_path[2048];
    char java_home_option[2048];
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

    JavaVMOption options[20];
    jint option_count = 0;
#define ADD_VM_OPTION(value) do { \
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
    ADD_VM_OPTION("-Dsun.java2d.opengl=false");
    ADD_VM_OPTION(java_library_path);
    ADD_VM_OPTION(lwjgl_library_path);
    ADD_VM_OPTION(jinput_library_path);
    ADD_VM_OPTION("-Dapple.awt.application.name=Minecraft Galaxy ARM64");
    ADD_VM_OPTION("-Djava.class.path=mcgl-nativewindow-patch.jar:Minecraft.jar");
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
        ADD_VM_OPTION("-Dmcgl.chunk.vbo=true");
        fprintf(stdout, "Runtime: experimental terrain VBO enabled.\n");
    } else {
        fprintf(stdout, "Runtime: original terrain display-list renderer.\n");
    }
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
    fprintf(stdout, "Runtime: creating native ARM64 Java 8 VM on the macOS application main thread.\n");
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
    (*vm)->DestroyJavaVM(vm);
    [appkit_pool drain];
    return game_context.exit_code;
}
