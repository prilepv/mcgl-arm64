#version 410 core

// Attribute locations are the ShaderLibrary contract for the next mesh layer.
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec4 aColor;
uniform mat4 uModelView;
uniform mat4 uProjection;
uniform vec4 uColorModulator;
out vec4 vColor;
out vec3 vEyePosition;

#if MCGL_TEXTURED
layout(location = 2) in vec2 aTexCoord;
uniform mat4 uTextureMatrix;
out vec3 vTexCoord;
#endif
#if MCGL_LIGHTMAP
layout(location = 3) in vec2 aLightmap;
uniform mat4 uLightmapMatrix;
out vec3 vLightmap;
#endif
#if MCGL_LIT
layout(location = 4) in vec3 aNormal;
uniform mat3 uNormalMatrix;
uniform vec3 uAmbientLight;
uniform vec3 uLightDirection0;
uniform vec3 uLightDirection1;
uniform vec3 uLightColor0;
uniform vec3 uLightColor1;
#endif

void main() {
    vec4 eye = uModelView * vec4(aPosition, 1.0);
    gl_Position = uProjection * eye;
    vEyePosition = eye.xyz;
    vColor = aColor * uColorModulator;
#if MCGL_TEXTURED
    vec4 uv = uTextureMatrix * vec4(aTexCoord, 0.0, 1.0);
    vTexCoord = vec3(uv.xy, uv.w);
#endif
#if MCGL_LIGHTMAP
    vec4 lightUv = uLightmapMatrix * vec4(aLightmap, 0.0, 1.0);
    vLightmap = vec3(lightUv.xy, lightUv.w);
#endif
#if MCGL_LIT
    vec3 normal = uNormalMatrix * aNormal;
    float normalLength = length(normal);
    if (normalLength > 0.0) normal /= normalLength;
    // Directions are supplied normalized in eye space. No implicit GL light/material state.
    vec3 lighting = uAmbientLight
        + uLightColor0 * max(dot(normal, uLightDirection0), 0.0)
        + uLightColor1 * max(dot(normal, uLightDirection1), 0.0);
    vColor.rgb *= clamp(lighting, 0.0, 1.0);
#endif
}
