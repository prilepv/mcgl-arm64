#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <jni.h>
#include <stdint.h>
#include "terrain_shader.h"

@interface PreviewMesh : NSObject
@property(nonatomic, strong) id<MTLBuffer> vertices, indices;
@property(nonatomic) NSUInteger count;
@property(nonatomic) MTLIndexType indexType;
@end
@implementation PreviewMesh
@end

@interface PreviewTexture : NSObject
@property(nonatomic, strong) id<MTLTexture> image;
@property(nonatomic, strong) id<MTLSamplerState> sampler;
@end
@implementation PreviewTexture
@end

@interface TerrainResources : NSObject
@property(nonatomic, strong) id<MTLDevice> device;
@property(nonatomic, strong) id<MTLCommandQueue> queue;
@property(nonatomic, strong) id<MTLRenderPipelineState> opaque, blended;
@property(nonatomic, strong) id<MTLLibrary> shaders;
@property(nonatomic) MTLPixelFormat format;
@property(nonatomic, strong) NSMutableDictionary<NSNumber *, id<MTLRenderPipelineState>> *customBlends;
@property(nonatomic, strong) NSArray<id<MTLDepthStencilState>> *depthStates;
#ifdef MCGL_TERRAIN_REVERSED_DEPTH
@property(nonatomic, strong) NSMutableDictionary<NSNumber *, id<MTLDepthStencilState>> *stencilStates;
#endif
@property(nonatomic, strong) PreviewTexture *white;
@end
@implementation TerrainResources
@end

typedef struct {
    uint64_t mesh, atlas;
    uint32_t mask, padding;
    uint64_t lightmap;
    float model[16];
} Draw;
_Static_assert(sizeof(Draw) == 96, "Preview draw layout");

static void require(BOOL condition, NSString *message) {
    if (!condition) @throw [NSException exceptionWithName:@"MetalTerrainPreview" reason:message userInfo:nil];
}
static void report(JNIEnv *env, NSException *exception) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, exception.reason.UTF8String);
}
static id resource(uint64_t pointer) {
    return (__bridge id)(void *)(uintptr_t)pointer;
}
static jlong own(id object) {
    return (jlong)(uintptr_t)(__bridge_retained void *)object;
}
static void *buffer(JNIEnv *env, jobject data, jlong size) {
    void *result = (*env)->GetDirectBufferAddress(env, data);
    require(result && (*env)->GetDirectBufferCapacity(env, data) >= size, @"Invalid native preview buffer");
    return result;
}

static id<MTLRenderPipelineState> pipeline(id<MTLDevice> device, id<MTLLibrary> shaders, jint blend,
                                           MTLPixelFormat format, BOOL interop, jint writeMask) {
    MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
#ifdef MCGL_TERRAIN_REVERSED_DEPTH
    desc.vertexFunction = [shaders newFunctionWithName:@"terrainVertexReversedDepth"];
    desc.fragmentFunction = [shaders newFunctionWithName:@"terrainDirectFragment"];
#else
    desc.vertexFunction = [shaders newFunctionWithName:@"terrainVertex"];
    desc.fragmentFunction = [shaders newFunctionWithName:interop ? @"terrainInteropFragment" : @"terrainFragment"];
#endif
    MTLVertexDescriptor *vertex = [MTLVertexDescriptor vertexDescriptor];
    vertex.attributes[0].format = MTLVertexFormatFloat3;
    vertex.attributes[1].format = MTLVertexFormatUChar4Normalized;
    vertex.attributes[1].offset = 20;
    vertex.attributes[2].format = MTLVertexFormatFloat2;
    vertex.attributes[2].offset = 12;
    vertex.attributes[3].format = MTLVertexFormatShort2;
    vertex.attributes[3].offset = 28;
    vertex.attributes[4].format = MTLVertexFormatChar4Normalized;
    vertex.attributes[4].offset = 24;
    vertex.attributes[5].format = MTLVertexFormatUChar4Normalized;
    vertex.attributes[5].offset = 32;
    vertex.attributes[6].format = MTLVertexFormatChar4Normalized;
    vertex.attributes[6].offset = 36;
    vertex.layouts[0].stride = 40;
    desc.vertexDescriptor = vertex;
#ifdef MCGL_TERRAIN_REVERSED_DEPTH
    desc.depthAttachmentPixelFormat = desc.stencilAttachmentPixelFormat = MTLPixelFormatDepth32Float_Stencil8;
#else
    desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
#endif
    MTLRenderPipelineColorAttachmentDescriptor *color = desc.colorAttachments[0];
    color.pixelFormat = format;
    if (writeMask) color.writeMask = (MTLColorWriteMask)(writeMask & 15);
    if (interop) desc.colorAttachments[1].pixelFormat = MTLPixelFormatBGRA8Unorm;
    color.blendingEnabled = blend != 0;
    if (blend == 1) {
        color.sourceRGBBlendFactor = color.sourceAlphaBlendFactor = MTLBlendFactorSourceAlpha;
        color.destinationRGBBlendFactor = color.destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
    } else if (blend != 0) {
        jint src = blend & 15, dst = (blend >> 4) & 15;
        jint srcAlpha = (blend >> 8) & 15, dstAlpha = (blend >> 12) & 15;
        jint op = (blend >> 16) & 7, alphaOp = (blend >> 19) & 7;
        require((blend >> 22) == 4 && src <= 10 && dst <= 10 && srcAlpha <= 10 && dstAlpha <= 10
                && op <= 4 && alphaOp <= 4, @"Invalid Metal blending state");
        color.sourceRGBBlendFactor = (MTLBlendFactor)src;
        color.destinationRGBBlendFactor = (MTLBlendFactor)dst;
        color.sourceAlphaBlendFactor = (MTLBlendFactor)srcAlpha;
        color.destinationAlphaBlendFactor = (MTLBlendFactor)dstAlpha;
        color.rgbBlendOperation = (MTLBlendOperation)op;
        color.alphaBlendOperation = (MTLBlendOperation)alphaOp;
    }
    NSError *error = nil;
    id<MTLRenderPipelineState> state = [device newRenderPipelineStateWithDescriptor:desc error:&error];
    require(state != nil, error.localizedDescription ?: @"Cannot create Metal terrain pipeline");
    return state;
}

static MTLSamplerAddressMode wrap(jint value) {
    switch (value) {
        case 10497: return MTLSamplerAddressModeRepeat;
        case 33648: return MTLSamplerAddressModeMirrorRepeat;
        case 33069: return MTLSamplerAddressModeClampToZero;
        case 33071: return MTLSamplerAddressModeClampToEdge;
        default: @throw [NSException exceptionWithName:@"MetalTerrainPreview" reason:@"Unsupported texture wrap" userInfo:nil];
    }
}

static PreviewTexture *newTexture(id<MTLDevice> device, NSUInteger width, NSUInteger height, NSUInteger levels,
                                  jint min, jint mag, jint s, jint t) {
    MTLTextureDescriptor *desc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
        width:width height:height mipmapped:levels > 1];
    desc.mipmapLevelCount = levels;
    desc.storageMode = MTLStorageModeShared;
    desc.usage = MTLTextureUsageShaderRead;
    MTLSamplerDescriptor *sampler = [MTLSamplerDescriptor new];
    sampler.minFilter = min == 9729 || min == 9985 || min == 9987 ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
    sampler.magFilter = mag == 9729 ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
    if (levels > 1) sampler.mipFilter = min >= 9986 ? MTLSamplerMipFilterLinear : MTLSamplerMipFilterNearest;
    sampler.sAddressMode = wrap(s);
    sampler.tAddressMode = wrap(t);
    PreviewTexture *texture = [PreviewTexture new];
    texture.image = [device newTextureWithDescriptor:desc];
    texture.sampler = [device newSamplerStateWithDescriptor:sampler];
    require(texture.image && texture.sampler, @"Cannot allocate Metal terrain texture");
    return texture;
}

static id<MTLLibrary> initializeTerrain(TerrainResources *scene, MTLPixelFormat format) {
    scene.device = MTLCreateSystemDefaultDevice();
    scene.queue = [scene.device newCommandQueue];
    require(scene.queue != nil, @"Metal command queue unavailable");
    NSString *source = [[NSString alloc] initWithBytes:terrain_metal length:terrain_metal_len encoding:NSUTF8StringEncoding];
    MTLCompileOptions *options = [MTLCompileOptions new];
    options.fastMathEnabled = NO;
    NSError *error = nil;
    id<MTLLibrary> shaders = [scene.device newLibraryWithSource:source options:options error:&error];
    require(shaders != nil, error.localizedDescription ?: @"Cannot compile Metal terrain shader");
    scene.shaders = shaders;
    scene.format = format;
    scene.customBlends = [NSMutableDictionary dictionary];
    scene.opaque = pipeline(scene.device, shaders, NO, format, NO, 0);
    scene.blended = pipeline(scene.device, shaders, YES, format, NO, 0);
#ifdef MCGL_TERRAIN_REVERSED_DEPTH
    scene.stencilStates = [NSMutableDictionary dictionary];
#endif
    NSMutableArray<id<MTLDepthStencilState>> *depth = [NSMutableArray arrayWithCapacity:16];
    for (NSUInteger write = 0; write < 2; write++) for (NSUInteger compare = 0; compare < 8; compare++) {
        MTLDepthStencilDescriptor *desc = [MTLDepthStencilDescriptor new];
        desc.depthWriteEnabled = write != 0;
        desc.depthCompareFunction = (MTLCompareFunction)compare;
        id<MTLDepthStencilState> state = [scene.device newDepthStencilStateWithDescriptor:desc];
        require(state != nil, @"Cannot create Metal depth state");
        [depth addObject:state];
    }
    scene.depthStates = depth;
    scene.white = newTexture(scene.device, 1, 1, 1, 9728, 9728, 33071, 33071);
    uint32_t white = UINT32_MAX;
    [scene.white.image replaceRegion:MTLRegionMake2D(0, 0, 1, 1) mipmapLevel:0 withBytes:&white bytesPerRow:4];
    return shaders;
}

static id<MTLRenderPipelineState> terrainPipeline(TerrainResources *scene, jint blend, jint writeMask) {
    if (writeMask == 0 && blend == 0) return scene.opaque;
    if (writeMask == 0 && blend == 1) return scene.blended;
    NSNumber *key = @((uint64_t)(uint32_t)blend | (uint64_t)(uint32_t)writeMask << 32);
    id<MTLRenderPipelineState> selected = scene.customBlends[key];
    if (!selected) {
        selected = pipeline(scene.device, scene.shaders, blend, scene.format, NO, writeMask);
        if (scene.customBlends.count >= 24) [scene.customBlends removeAllObjects];
        scene.customBlends[key] = selected;
    }
    return selected;
}

#ifdef MCGL_TERRAIN_REVERSED_DEPTH
static id<MTLDepthStencilState> stencilState(TerrainResources *scene, const jint *flags) {
    uint32_t state = flags[10], masks = (uint32_t)flags[11] >> 8;
    uint64_t key = (uint64_t)state | (uint64_t)masks << 16 | (uint64_t)(flags[4] + flags[5] * 8) << 32;
    NSNumber *identifier = @(key);
    id<MTLDepthStencilState> selected = scene.stencilStates[identifier];
    if (!selected) {
        MTLStencilDescriptor *stencil = [MTLStencilDescriptor new];
        stencil.stencilCompareFunction = (MTLCompareFunction)(state & 7);
        stencil.stencilFailureOperation = (MTLStencilOperation)((state >> 3) & 7);
        stencil.depthFailureOperation = (MTLStencilOperation)((state >> 6) & 7);
        stencil.depthStencilPassOperation = (MTLStencilOperation)((state >> 9) & 7);
        stencil.readMask = masks & 255;
        stencil.writeMask = (masks >> 8) & 255;
        MTLDepthStencilDescriptor *depth = [MTLDepthStencilDescriptor new];
        depth.depthCompareFunction = (MTLCompareFunction)flags[4];
        depth.depthWriteEnabled = flags[5] != 0;
        depth.frontFaceStencil = depth.backFaceStencil = stencil;
        selected = [scene.device newDepthStencilStateWithDescriptor:depth];
        require(selected != nil, @"Cannot create Metal stencil state");
        if (scene.stencilStates.count >= 32) [scene.stencilStates removeAllObjects];
        scene.stencilStates[identifier] = selected;
    }
    return selected;
}
#endif

static jlong uploadTerrainMesh(
        JNIEnv *env, jlong handle, jobject vertices, jobject indices, jint count, jint indexBytes) {
    @autoreleasepool {
        @try {
            TerrainResources *scene = resource(handle);
            jlong length = (*env)->GetDirectBufferCapacity(env, vertices);
            require(length > 0 && length % 40 == 0 && count > 0 && (indexBytes == 2 || indexBytes == 4),
                @"Invalid Metal terrain mesh");
            PreviewMesh *mesh = [PreviewMesh new];
            mesh.vertices = [scene.device newBufferWithBytes:buffer(env, vertices, length)
                length:(NSUInteger)length options:MTLResourceStorageModeShared];
            NSUInteger indexLength = (NSUInteger)count * indexBytes;
            mesh.indices = [scene.device newBufferWithBytes:buffer(env, indices, (jlong)indexLength)
                length:indexLength options:MTLResourceStorageModeShared];
            mesh.count = count;
            mesh.indexType = indexBytes == 2 ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32;
            require(mesh.vertices && mesh.indices, @"Cannot allocate Metal terrain mesh");
            return own(mesh);
        } @catch (NSException *exception) {
            report(env, exception);
            return 0;
        }
    }
}

static jlong uploadTerrainTexture(
        JNIEnv *env, jlong handle, jint width, jint height, jobjectArray pixels, jint min, jint mag, jint s, jint t) {
    @autoreleasepool {
        @try {
            TerrainResources *scene = resource(handle);
            NSUInteger levels = (*env)->GetArrayLength(env, pixels);
            require(width > 0 && height > 0 && levels > 0, @"Invalid Metal terrain texture");
            PreviewTexture *texture = newTexture(scene.device, width, height, levels, min, mag, s, t);
            for (NSUInteger i = 0; i < levels; i++) {
                jobject data = (*env)->GetObjectArrayElement(env, pixels, (jsize)i);
                NSUInteger w = MAX(1, width >> i), h = MAX(1, height >> i);
                [texture.image replaceRegion:MTLRegionMake2D(0, 0, w, h) mipmapLevel:i
                    withBytes:buffer(env, data, (jlong)(w * h * 4)) bytesPerRow:w * 4];
                (*env)->DeleteLocalRef(env, data);
            }
            return own(texture);
        } @catch (NSException *exception) {
            report(env, exception);
            return 0;
        }
    }
}

static id<MTLTexture> target(id<MTLDevice> device, MTLPixelFormat format, NSUInteger width, NSUInteger height) {
    MTLTextureDescriptor *desc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:width height:height mipmapped:NO];
    desc.storageMode = MTLStorageModePrivate;
    desc.usage = MTLTextureUsageRenderTarget;
    return [device newTextureWithDescriptor:desc];
}

static void textures(id<MTLRenderCommandEncoder> encoder, PreviewTexture *texture, NSUInteger slot) {
    [encoder setFragmentTexture:texture.image atIndex:slot];
    [encoder setFragmentSamplerState:texture.sampler atIndex:slot];
}

static void encodeTerrain(TerrainResources *scene, id<MTLRenderCommandEncoder> encoder, const Draw *draws,
                          jint count, const void *material, const jint *flags, id<MTLRenderPipelineState> override) {
    require(flags[4] >= 0 && flags[4] < 8 && flags[5] >= 0 && flags[5] < 2, @"Invalid Metal depth state");
#ifdef MCGL_TERRAIN_REVERSED_DEPTH
    [encoder setRenderPipelineState:override ?: terrainPipeline(scene, flags[6], flags[12])];
    if (flags[10]) {
        [encoder setDepthStencilState:stencilState(scene, flags)];
        [encoder setStencilReferenceValue:(uint32_t)flags[11] & 255];
    } else
#else
    [encoder setRenderPipelineState:override ?: terrainPipeline(scene, flags[6], 0)];
#endif
    [encoder setDepthStencilState:scene.depthStates[(NSUInteger)(flags[4] + flags[5] * 8)]];
    [encoder setCullMode:flags[7] == 1 ? MTLCullModeFront : flags[7] == 2 ? MTLCullModeBack : MTLCullModeNone];
    [encoder setFrontFacingWinding:flags[8] ? MTLWindingCounterClockwise : MTLWindingClockwise];
#ifdef MCGL_TERRAIN_REVERSED_DEPTH
    NSUInteger materialLength = 112 * sizeof(float);
#else
    NSUInteger materialLength = 64 * sizeof(float);
#endif
    [encoder setVertexBytes:material length:materialLength atIndex:1];
    [encoder setFragmentBytes:material length:materialLength atIndex:1];
    [encoder setFragmentBytes:flags length:4 * sizeof(jint) atIndex:2];
    PreviewTexture *lastAtlas = nil, *lastLightmap = nil;
    for (jint i = 0; i < count; i++) {
        const Draw *draw = &draws[i];
        PreviewMesh *mesh = resource(draw->mesh);
        PreviewTexture *atlas = resource(draw->atlas) ?: scene.white;
        PreviewTexture *lightmap = resource(draw->lightmap) ?: scene.white;
        if (atlas != lastAtlas) { textures(encoder, atlas, 0); lastAtlas = atlas; }
        if (lightmap != lastLightmap) { textures(encoder, lightmap, 1); lastLightmap = lightmap; }
        [encoder setVertexBuffer:mesh.vertices offset:0 atIndex:0];
        [encoder setVertexBytes:draw->model length:sizeof(draw->model) atIndex:2];
        [encoder setVertexBytes:&draw->mask length:sizeof(draw->mask) atIndex:3];
        [encoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:mesh.count indexType:mesh.indexType
            indexBuffer:mesh.indices indexBufferOffset:0];
    }
}

static void releaseTerrainResource(jlong handle) {
    @autoreleasepool {
        id object = (__bridge_transfer id)(void *)(uintptr_t)handle;
        (void)object;
    }
}
