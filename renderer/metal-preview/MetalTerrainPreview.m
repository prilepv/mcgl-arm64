#import <IOSurface/IOSurface.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl3.h>
#include <time.h>
#include "MetalTerrainCore.h"

@interface SharedTerrain : NSObject
@property(nonatomic, strong) id<MTLTexture> color, depthBits, depth;
@property(nonatomic) GLuint colorGL, depthGL;
@property(nonatomic) GLsync fence;
@end
@implementation SharedTerrain
@end

@interface Preview : TerrainResources
@property(nonatomic, strong) id<MTLRenderPipelineState> interop;
@property(nonatomic, strong) NSMutableArray<SharedTerrain *> *shared;
@property(nonatomic) GLuint composite, vao;
@property(nonatomic, strong) id<MTLTexture> color, depth;
@end
@implementation Preview
@end

JNIEXPORT jlong JNICALL Java_local_mcgl_render_backend_MetalTerrainPreview_create(JNIEnv *env, jclass type, jboolean visible) {
    (void)type;
    @autoreleasepool {
        @try {
            Preview *scene = [Preview new];
            id<MTLLibrary> shaders = initializeTerrain(scene, MTLPixelFormatRGBA8Unorm);
            if (visible) {
                scene.interop = pipeline(scene.device, shaders, NO, MTLPixelFormatBGRA8Unorm, YES, 0);
                scene.shared = [NSMutableArray arrayWithCapacity:3];
            }
            return own(scene);
        } @catch (NSException *exception) {
            report(env, exception);
            return 0;
        }
    }
}

JNIEXPORT jlong JNICALL Java_local_mcgl_render_backend_MetalTerrainPreview_uploadMesh(
        JNIEnv *env, jclass type, jlong handle, jobject vertices, jobject indices, jint count, jint indexBytes) {
    (void)type;
    return uploadTerrainMesh(env, handle, vertices, indices, count, indexBytes);
}

JNIEXPORT jlong JNICALL Java_local_mcgl_render_backend_MetalTerrainPreview_uploadTexture(
        JNIEnv *env, jclass type, jlong handle, jint width, jint height, jobjectArray pixels,
        jint min, jint mag, jint s, jint t) {
    (void)type;
    return uploadTerrainTexture(env, handle, width, height, pixels, min, mag, s, t);
}

JNIEXPORT jboolean JNICALL Java_local_mcgl_render_backend_MetalTerrainPreview_updateTexture(
        JNIEnv *env, jclass type, jlong handle, jint level, jint x, jint y, jint width, jint height,
        jobject pixels, jboolean bgra) {
    (void)type;
    @autoreleasepool {
        @try {
            PreviewTexture *texture = resource(handle);
            if (level < 0 || (NSUInteger)level >= texture.image.mipmapLevelCount) return JNI_TRUE;
            if (width == 0 || height == 0) return JNI_TRUE;
            NSUInteger w = MAX(1, texture.image.width >> level), h = MAX(1, texture.image.height >> level);
            if (x < 0 || y < 0 || width < 0 || height < 0 || (uint64_t)x + width > w || (uint64_t)y + height > h)
                return JNI_FALSE;
            size_t length = (size_t)width * height * 4;
            if ((*env)->GetDirectBufferCapacity(env, pixels) < (jlong)length) return JNI_FALSE;
            const uint8_t *source = buffer(env, pixels, (jlong)length);
            NSMutableData *converted = nil;
            if (bgra) {
                converted = [NSMutableData dataWithLength:length];
                uint8_t *rgba = converted.mutableBytes;
                for (size_t i = 0; i < length; i += 4) {
                    rgba[i] = source[i + 2]; rgba[i + 1] = source[i + 1];
                    rgba[i + 2] = source[i]; rgba[i + 3] = source[i + 3];
                }
                source = rgba;
            }
            [texture.image replaceRegion:MTLRegionMake2D(x, y, width, height) mipmapLevel:level
                withBytes:source bytesPerRow:(NSUInteger)width * 4];
            return JNI_TRUE;
        } @catch (NSException *exception) {
            report(env, exception);
            return JNI_FALSE;
        }
    }
}

static id<MTLTexture> sharedTexture(id<MTLDevice> device, NSUInteger width, NSUInteger height, GLuint *name) {
    NSUInteger row = IOSurfaceAlignProperty(kIOSurfaceBytesPerRow, width * 4);
    NSDictionary *properties = @{
        (__bridge NSString *)kIOSurfaceWidth: @(width), (__bridge NSString *)kIOSurfaceHeight: @(height),
        (__bridge NSString *)kIOSurfaceBytesPerElement: @4, (__bridge NSString *)kIOSurfaceBytesPerRow: @(row),
        (__bridge NSString *)kIOSurfacePixelFormat: @(0x42475241), // BGRA
    };
    IOSurfaceRef surface = IOSurfaceCreate((__bridge CFDictionaryRef)properties);
    require(surface != NULL, @"Cannot create terrain IOSurface");
    MTLTextureDescriptor *desc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
        width:width height:height mipmapped:NO];
    desc.storageMode = MTLStorageModeShared;
    desc.usage = MTLTextureUsageRenderTarget;
    id<MTLTexture> texture = [device newTextureWithDescriptor:desc iosurface:surface plane:0];
    GLuint glName = 0;
    CGLError status = kCGLBadDrawable;
    if (texture) {
        GLint previous = 0;
        glGetIntegerv(GL_TEXTURE_BINDING_RECTANGLE, &previous);
        glGenTextures(1, &glName);
        glBindTexture(GL_TEXTURE_RECTANGLE, glName);
        status = CGLTexImageIOSurface2D(CGLGetCurrentContext(), GL_TEXTURE_RECTANGLE, GL_RGBA8,
            (GLsizei)width, (GLsizei)height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, surface, 0);
        glBindTexture(GL_TEXTURE_RECTANGLE, (GLuint)previous);
    }
    CFRelease(surface);
    if (status != kCGLNoError && glName) glDeleteTextures(1, &glName);
    require(status == kCGLNoError && glName, @"Cannot import terrain IOSurface into OpenGL");
    *name = glName;
    return texture;
}

static void deleteShared(SharedTerrain *frame) {
    if (frame.fence) glDeleteSync(frame.fence);
    GLuint names[] = {frame.colorGL, frame.depthGL};
    glDeleteTextures(2, names);
}

static SharedTerrain *sharedTarget(Preview *scene, NSUInteger width, NSUInteger height) {
    if (scene.shared.count && (scene.shared[0].color.width != width || scene.shared[0].color.height != height)) {
        for (SharedTerrain *frame in scene.shared) if (frame.fence) {
            GLenum result = glClientWaitSync(frame.fence, 0, 0);
            if (result != GL_ALREADY_SIGNALED && result != GL_CONDITION_SATISFIED) return nil;
        }
        for (SharedTerrain *frame in scene.shared) deleteShared(frame);
        [scene.shared removeAllObjects];
    }
    for (SharedTerrain *frame in scene.shared) {
        if (!frame.fence) return frame;
        GLenum result = glClientWaitSync(frame.fence, 0, 0);
        if (result == GL_ALREADY_SIGNALED || result == GL_CONDITION_SATISFIED) {
            glDeleteSync(frame.fence);
            frame.fence = NULL;
            return frame;
        }
    }
    // Three small targets, or fewer at higher resolutions, within 96 MiB.
    NSUInteger limit = MIN(3, (96u * 1024u * 1024u) / (width * height * 12));
    if (scene.shared.count >= limit) return nil;
    SharedTerrain *frame = [SharedTerrain new];
    @try {
        GLuint color = 0, depth = 0;
        frame.color = sharedTexture(scene.device, width, height, &color);
        frame.colorGL = color;
        frame.depthBits = sharedTexture(scene.device, width, height, &depth);
        frame.depthGL = depth;
        frame.depth = target(scene.device, MTLPixelFormatDepth32Float, width, height);
        require(frame.depth != nil, @"Cannot allocate terrain depth");
        [scene.shared addObject:frame];
        return frame;
    } @catch (NSException *exception) {
        deleteShared(frame);
        @throw exception;
    }
}

static GLuint shader(GLenum type, const char *source) {
    GLuint value = glCreateShader(type);
    glShaderSource(value, 1, &source, NULL);
    glCompileShader(value);
    GLint ok = 0;
    glGetShaderiv(value, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {0};
        glGetShaderInfoLog(value, sizeof(log), NULL, log);
        glDeleteShader(value);
        require(NO, [NSString stringWithFormat:@"Terrain compositor shader: %s", log]);
    }
    return value;
}

static void createComposite(Preview *scene) {
    if (scene.composite) return;
    const char *vertex = "#version 410 core\n"
        "void main(){ vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2); gl_Position=vec4(p*2.0-1.0,0,1); }";
    const char *fragment = "#version 410 core\n"
        "uniform sampler2DRect c,d; layout(location=0) out vec4 color;"
        "void main(){ ivec2 p=ivec2(gl_FragCoord.xy); p.y=textureSize(d).y-1-p.y;"
        "uvec4 v=uvec4(round(texelFetch(d,p)*255.0)); uint bits=v.x|(v.y<<8)|(v.z<<16)|(v.w<<24);"
        "if(bits==0u) discard; gl_FragDepth=uintBitsToFloat(bits-1u); color=texelFetch(c,p); }";
    GLuint vs = shader(GL_VERTEX_SHADER, vertex), fs = 0, program = 0;
    @try {
        fs = shader(GL_FRAGMENT_SHADER, fragment);
        program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        GLint ok = 0;
        glGetProgramiv(program, GL_LINK_STATUS, &ok);
        require(ok, @"Cannot link terrain compositor");
        GLuint vao = 0;
        glGenVertexArrays(1, &vao);
        require(vao != 0, @"Cannot allocate terrain compositor VAO");
        scene.vao = vao;
        scene.composite = program;
        program = 0;
    } @finally {
        glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        if (program) glDeleteProgram(program);
    }
}

static void composite(Preview *scene, SharedTerrain *frame) {
    GLint program, vao, active, bound[2];
    glGetIntegerv(GL_CURRENT_PROGRAM, &program);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &active);
    GLboolean culling = glIsEnabled(GL_CULL_FACE);
    if (culling) glDisable(GL_CULL_FACE);
    glUseProgram(scene.composite);
    glUniform1i(glGetUniformLocation(scene.composite, "c"), 0);
    glUniform1i(glGetUniformLocation(scene.composite, "d"), 1);
    for (unsigned int unit = 0; unit < 2; unit++) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glGetIntegerv(GL_TEXTURE_BINDING_RECTANGLE, &bound[unit]);
        glBindTexture(GL_TEXTURE_RECTANGLE, unit == 0 ? frame.colorGL : frame.depthGL);
    }
    glBindVertexArray(scene.vao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    frame.fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    if (!frame.fence) glFinish();
    glBindVertexArray((GLuint)vao);
    for (unsigned int unit = 0; unit < 2; unit++) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_RECTANGLE, (GLuint)bound[unit]);
    }
    glActiveTexture((GLenum)active);
    glUseProgram((GLuint)program);
    if (culling) glEnable(GL_CULL_FACE);
}

JNIEXPORT jdoubleArray JNICALL Java_local_mcgl_render_backend_MetalTerrainPreview_replay(
        JNIEnv *env, jclass type, jlong handle, jint width, jint height, jobject commands, jint count,
        jobject uniform, jintArray inputFlags, jobject readback, jboolean visible) {
    (void)type;
    @autoreleasepool {
        @try {
            Preview *scene = resource(handle);
            const Draw *draws = buffer(env, commands, (jlong)count * sizeof(Draw));
            const void *material = buffer(env, uniform, 64);
            jint flags[10];
            (*env)->GetIntArrayRegion(env, inputFlags, 0, 10, flags);
            if ((*env)->ExceptionCheck(env)) return NULL;
            require(flags[4] >= 0 && flags[4] < 8 && flags[5] >= 0 && flags[5] < 2, @"Invalid Metal depth state");
            SharedTerrain *shared = nil;
            if (visible) {
                createComposite(scene);
                shared = sharedTarget(scene, width, height);
                if (!shared) return NULL;
            } else if (scene.color.width != (NSUInteger)width || scene.color.height != (NSUInteger)height) {
                scene.color = target(scene.device, MTLPixelFormatRGBA8Unorm, width, height);
                scene.depth = target(scene.device, MTLPixelFormatDepth32Float, width, height);
            }
            require(shared || (scene.color && scene.depth), @"Cannot allocate Metal terrain targets");
            uint64_t start = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
            MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
            pass.colorAttachments[0].texture = shared ? shared.color : scene.color;
            pass.colorAttachments[0].loadAction = MTLLoadActionClear;
            pass.colorAttachments[0].storeAction = MTLStoreActionStore;
            pass.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0);
            if (shared) {
                pass.colorAttachments[1].texture = shared.depthBits;
                pass.colorAttachments[1].loadAction = MTLLoadActionClear;
                pass.colorAttachments[1].storeAction = MTLStoreActionStore;
                pass.colorAttachments[1].clearColor = MTLClearColorMake(0, 0, 0, 0);
            }
            pass.depthAttachment.texture = shared ? shared.depth : scene.depth;
            pass.depthAttachment.loadAction = MTLLoadActionClear;
            pass.depthAttachment.storeAction = MTLStoreActionStore;
            pass.depthAttachment.clearDepth = 1.0;
            id<MTLCommandBuffer> frame = [scene.queue commandBuffer];
            id<MTLRenderCommandEncoder> encoder = [frame renderCommandEncoderWithDescriptor:pass];
            require(encoder != nil, @"Cannot encode Metal terrain");
            encodeTerrain(scene, encoder, draws, count, material, flags, shared ? scene.interop : nil);
            [encoder endEncoding];
            [frame commit];
            jdouble submit = (clock_gettime_nsec_np(CLOCK_UPTIME_RAW) - start) / 1e6;
            [frame waitUntilCompleted];
            require(frame.status == MTLCommandBufferStatusCompleted, frame.error.localizedDescription ?: @"Metal terrain command failed");
            if (shared) composite(scene, shared);
            if (readback) {
                NSUInteger row = (NSUInteger)width * 4, plane = row * height;
                require(row % 256 == 0, @"Validation width must align readback rows");
                void *pixels = buffer(env, readback, (jlong)(plane * 2));
                id<MTLBuffer> copy = [scene.device newBufferWithLength:plane * 2 options:MTLResourceStorageModeShared];
                id<MTLCommandBuffer> command = [scene.queue commandBuffer];
                id<MTLBlitCommandEncoder> blit = [command blitCommandEncoder];
                require(copy && blit, @"Cannot allocate terrain readback");
                MTLSize size = MTLSizeMake(width, height, 1);
                [blit copyFromTexture:scene.color sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0)
                    sourceSize:size toBuffer:copy destinationOffset:0 destinationBytesPerRow:row destinationBytesPerImage:0];
                [blit copyFromTexture:scene.depth sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0)
                    sourceSize:size toBuffer:copy destinationOffset:plane destinationBytesPerRow:row destinationBytesPerImage:0];
                [blit endEncoding];
                [command commit];
                [command waitUntilCompleted];
                require(command.status == MTLCommandBufferStatusCompleted, @"Terrain readback failed");
                memcpy(pixels, copy.contents, plane * 2);
            }
            jdouble times[] = {submit, (frame.GPUEndTime - frame.GPUStartTime) * 1000};
            jdoubleArray result = (*env)->NewDoubleArray(env, 2);
            if (result) (*env)->SetDoubleArrayRegion(env, result, 0, 2, times);
            return result;
        } @catch (NSException *exception) {
            report(env, exception);
            return NULL;
        }
    }
}

JNIEXPORT void JNICALL Java_local_mcgl_render_backend_MetalTerrainPreview_releaseResource(
        JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    releaseTerrainResource(handle);
}
