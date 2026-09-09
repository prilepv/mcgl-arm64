in vec4 vColor;
flat in vec4 vFlatColor;
in vec3 vEyePosition, vUv, vLightmap;
uniform int uTextured, uLightmapped, uFlat, uFogMode, uFogDistance;
uniform sampler2D uTexture, uLightmap;

float fogFactor() {
    float distance = uFogDistance == 1 ? length(vEyePosition) : abs(vEyePosition.z);
    float value = 1.0;
    if (uFogMode == 1) {
        float range = uGameFog.end - uGameFog.start;
        value = range == 0.0 ? (distance < uGameFog.end ? 1.0 : 0.0) : (uGameFog.end - distance) / range;
    } else if (uFogMode == 2) value = exp(-uGameFog.density * distance);
    else if (uFogMode == 3) {
        float densityDistance = uGameFog.density * distance;
        value = exp(-densityDistance * densityDistance);
    }
    return clamp(value, 0.0, 1.0);
}
void main() {
    vec4 color = uFlat != 0 ? vFlatColor : vColor;
    if (uTextured != 0) color *= mcglTextureProj(uTexture, vUv);
    if (uLightmapped != 0) color *= textureProj(uLightmap, vLightmap);
    if (!mcglAlphaPass(color.a)) discard;
    if (uFogMode != 0) color.rgb = mix(uGameFog.color.rgb, color.rgb, fogFactor());
    mcglOutColor = color;
}
