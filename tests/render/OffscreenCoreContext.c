#include <jni.h>
#include <stdint.h>
#include <OpenGL/OpenGL.h>

JNIEXPORT jlong JNICALL Java_org_lwjgl_opengl_OffscreenCoreContext_create(JNIEnv *env, jclass type) {
    CGLPixelFormatAttribute attributes[] = {
        kCGLPFAOpenGLProfile, (CGLPixelFormatAttribute)kCGLOGLPVersion_GL4_Core,
        kCGLPFAAccelerated, kCGLPFAColorSize, (CGLPixelFormatAttribute)24,
        kCGLPFAAlphaSize, (CGLPixelFormatAttribute)8,
        kCGLPFADepthSize, (CGLPixelFormatAttribute)24,
        kCGLPFAStencilSize, (CGLPixelFormatAttribute)8, (CGLPixelFormatAttribute)0
    };
    CGLPixelFormatObj format = NULL; CGLContextObj context = NULL; GLint count = 0;
    if (CGLChoosePixelFormat(attributes, &format, &count) != kCGLNoError || !format) return 0;
    CGLError result = CGLCreateContext(format, NULL, &context); CGLDestroyPixelFormat(format);
    if (result != kCGLNoError || !context) return 0;
    if (CGLSetCurrentContext(context) != kCGLNoError) { CGLDestroyContext(context); return 0; }
    return (jlong)(intptr_t)context;
}
JNIEXPORT void JNICALL Java_org_lwjgl_opengl_OffscreenCoreContext_release(JNIEnv *env, jclass type, jlong handle) {
    CGLSetCurrentContext(NULL);
    if (handle) CGLDestroyContext((CGLContextObj)(intptr_t)handle);
}
