#include <metal_stdlib>
using namespace metal;

struct Material {
    float4x4 projection, textureMatrix, lightmapMatrix;
    float4 color, fogColor, fogAndAlpha, currentCoordinates;
};

struct Light {
    float4 position, ambient, diffuse;
};

struct DirectMaterial {
    Material base;
    float4 lighting, normalAndLights, ambient, materialAmbient, materialDiffuse;
    Light lights[2];
    float4 textureConstant;
};

struct Input {
    float3 position [[attribute(0)]];
    float4 color [[attribute(1)]];
    float2 uv [[attribute(2)]];
    short2 lightmap [[attribute(3)]];
    float4 normal [[attribute(4)]];
    float4 flatColor [[attribute(5)]];
    float4 flatNormal [[attribute(6)]];
};

struct Output {
    float4 position [[position]];
    float3 eye, uv, lightmap;
    float4 color;
    float4 flatColor [[flat]];
};

static Output terrainVertexOutput(Input input, constant Material &material, uint mask, float4 eye, float4 position) {
    float4 uv = material.textureMatrix * float4((mask & 4) ? input.uv : material.currentCoordinates.xy, 0, 1);
    float4 lightmap = material.lightmapMatrix * float4((mask & 8) ? float2(input.lightmap) : material.currentCoordinates.zw, 0, 1);
    Output output;
    output.position = position;
    output.eye = eye.xyz;
    output.uv = uv.xyw;
    output.lightmap = lightmap.xyw;
    output.color = (mask & 2) ? input.color : material.color;
    output.flatColor = (mask & 34) == 34 ? input.flatColor : output.color;
    return output;
}

vertex Output terrainVertex(Input input [[stage_in]], constant Material &material [[buffer(1)]],
        constant float4x4 &model [[buffer(2)]], constant uint &mask [[buffer(3)]]) {
    float4 eye = model * float4(input.position, 1);
    float4 position = material.projection * eye;
    position.z = (position.z + position.w) * 0.5f;
    return terrainVertexOutput(input, material, mask, eye, position);
}

static float4 lightColor(float4 color, float3 normal, float3 eye, constant DirectMaterial &material) {
    if (material.lighting.y != 0 && length(normal) > 0) normal = normalize(normal);
    float4 ambient = material.lighting.z != 0 ? color : material.materialAmbient;
    float4 diffuse = material.lighting.w != 0 ? color : material.materialDiffuse;
    float3 result = ambient.rgb * material.ambient.rgb;
    uint active = uint(material.normalAndLights.w);
    for (uint i = 0; i < 2; i++) {
        if (!(active & (1u << i))) continue;
        constant Light &light = material.lights[i];
        float3 direction = light.position.xyz - eye * light.position.w;
        if (length(direction) > 0) direction = normalize(direction);
        result += light.ambient.rgb * ambient.rgb + light.diffuse.rgb * diffuse.rgb * max(dot(normal, direction), 0.0f);
    }
    return float4(saturate(result), diffuse.a);
}

vertex Output terrainVertexReversedDepth(Input input [[stage_in]], constant DirectMaterial &material [[buffer(1)]],
        constant float4x4 &model [[buffer(2)]], constant uint &mask [[buffer(3)]]) {
    float4 eye = model * float4(input.position, 1);
    Output output = terrainVertexOutput(input, material.base, mask, eye, material.base.projection * eye);
    if (material.lighting.x != 0) {
        float3 a = model[0].xyz, b = model[1].xyz, c = model[2].xyz;
        float3 x = cross(b, c), y = cross(c, a), z = cross(a, b);
        float det = dot(a, x);
        float3x3 transform = det == 0 ? float3x3(float3(0), float3(0), float3(0)) : float3x3(x / det, y / det, z / det);
        float3 normal = (mask & 16) ? input.normal.xyz : material.normalAndLights.xyz;
        float3 flat = (mask & 80) == 80 ? input.flatNormal.xyz : normal;
        output.color = lightColor(output.color, transform * normal, eye.xyz, material);
        output.flatColor = lightColor(output.flatColor, transform * flat, eye.xyz, material);
    }
    return output;
}

static bool alphaPass(float a, float reference, int function) {
    switch (function) {
        case 1: return false;
        case 2: return a < reference;
        case 3: return a == reference;
        case 4: return a <= reference;
        case 5: return a > reference;
        case 6: return a != reference;
        case 7: return a >= reference;
        default: return true;
    }
}

static float4 shade(Output input, constant Material &material, constant int4 &flags, texture2d<float> atlas,
        texture2d<float> lightmap, sampler atlasSampler, sampler lightmapSampler, float4 textureConstant) {
    float4 color = (flags.x & 4) ? input.flatColor : input.color;
    if (flags.x & 1) {
        float4 texel = atlas.sample(atlasSampler, input.uv.xy / input.uv.z);
        color *= (flags.x & 8) ? float4(textureConstant.rgb, texel.a) : texel;
    }
    if (flags.x & 2) color *= lightmap.sample(lightmapSampler, input.lightmap.xy / input.lightmap.z);
    if (!alphaPass(color.a, material.fogAndAlpha.w, flags.y)) discard_fragment();
    if (flags.z != 0) {
        float distance = flags.w == 1 ? length(input.eye) : abs(input.eye.z);
        float fog = 1;
        float densityDistance = material.fogAndAlpha.z * distance;
        if (flags.z == 1) {
            float range = material.fogAndAlpha.y - material.fogAndAlpha.x;
            fog = range == 0 ? float(distance < material.fogAndAlpha.y) : (material.fogAndAlpha.y - distance) / range;
        } else if (flags.z == 2) fog = exp(-densityDistance);
        else fog = exp(-densityDistance * densityDistance);
        color.rgb = mix(material.fogColor.rgb, color.rgb, saturate(fog));
    }
    return color;
}

fragment float4 terrainFragment(Output input [[stage_in]], constant Material &material [[buffer(1)]],
        constant int4 &flags [[buffer(2)]], texture2d<float> atlas [[texture(0)]],
        texture2d<float> lightmap [[texture(1)]], sampler atlasSampler [[sampler(0)]],
        sampler lightmapSampler [[sampler(1)]]) {
    return shade(input, material, flags, atlas, lightmap, atlasSampler, lightmapSampler, float4(1));
}

fragment float4 terrainDirectFragment(Output input [[stage_in]], constant DirectMaterial &material [[buffer(1)]],
        constant int4 &flags [[buffer(2)]], texture2d<float> atlas [[texture(0)]],
        texture2d<float> lightmap [[texture(1)]], sampler atlasSampler [[sampler(0)]],
        sampler lightmapSampler [[sampler(1)]]) {
    return shade(input, material.base, flags, atlas, lightmap, atlasSampler, lightmapSampler, material.textureConstant);
}

struct InteropFragment {
    float4 color [[color(0)]];
    float4 depth [[color(1)]];
};

fragment InteropFragment terrainInteropFragment(Output input [[stage_in]],
        constant Material &material [[buffer(1)]], constant int4 &flags [[buffer(2)]],
        texture2d<float> atlas [[texture(0)]], texture2d<float> lightmap [[texture(1)]],
        sampler atlasSampler [[sampler(0)]], sampler lightmapSampler [[sampler(1)]]) {
    InteropFragment result;
    result.color = shade(input, material, flags, atlas, lightmap, atlasSampler, lightmapSampler, float4(1));
    // Store float bits losslessly in four UNORM channels. Zero means no fragment.
    uint bits = as_type<uint>(input.position.z) + 1u;
    result.depth = float4(uint4(bits, bits >> 8, bits >> 16, bits >> 24) & 255u) / 255.0f;
    return result;
}
