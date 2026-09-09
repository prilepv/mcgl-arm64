#version 410 core

in vec4 vColor;
in vec3 vEyePosition;
layout(location = 0) out vec4 outColor;
#if MCGL_TEXTURED
in vec3 vTexCoord;
uniform sampler2D uTexture;
#endif
#if MCGL_LIGHTMAP
in vec3 vLightmap;
uniform sampler2D uLightmap;
#endif

// IDs match ShaderLibrary.AlphaTest, FogMode and FogDistance.
uniform int uAlphaFunction;
uniform float uAlphaReference;
uniform int uFogMode;
uniform int uFogDistance;
uniform float uFogStart;
uniform float uFogEnd;
uniform float uFogDensity;
uniform vec4 uFogColor;

bool alphaPass(float alpha) {
    float reference = clamp(uAlphaReference, 0.0, 1.0);
    if (uAlphaFunction == 0 || uAlphaFunction == 8) return true;
    if (uAlphaFunction == 1) return false;
    if (uAlphaFunction == 2) return alpha < reference;
    if (uAlphaFunction == 3) return alpha == reference;
    if (uAlphaFunction == 4) return alpha <= reference;
    if (uAlphaFunction == 5) return alpha > reference;
    if (uAlphaFunction == 6) return alpha != reference;
    if (uAlphaFunction == 7) return alpha >= reference;
    return false;
}
float fogFactor() {
    float distance = uFogDistance == 1 ? length(vEyePosition) : abs(vEyePosition.z);
    float factor = 1.0;
    if (uFogMode == 1) {
        float range = uFogEnd - uFogStart;
        factor = range == 0.0 ? (distance < uFogEnd ? 1.0 : 0.0) : (uFogEnd - distance) / range;
    } else if (uFogMode == 2) {
        factor = exp(-max(uFogDensity, 0.0) * distance);
    } else if (uFogMode == 3) {
        float densityDistance = max(uFogDensity, 0.0) * distance;
        factor = exp(-densityDistance * densityDistance);
    }
    return clamp(factor, 0.0, 1.0);
}
void main() {
    vec4 color = vColor;
#if MCGL_TEXTURED
    color *= textureProj(uTexture, vTexCoord);
#endif
#if MCGL_LIGHTMAP
    color *= textureProj(uLightmap, vLightmap);
#endif
    if (!alphaPass(color.a)) discard;
    if (uFogMode != 0) color.rgb = mix(uFogColor.rgb, color.rgb, fogFactor());
    outColor = color;
}
