// Identical lighting for ordinary geometry and lit cached text. All matrices stay on the GPU.
uniform int uLighting, uNormalize, uColorMaterial, uLightEnabled0, uLightEnabled1;
vec3 mcglLightContribution(GameLight light, vec3 normal, vec3 eye, vec3 ambient, vec3 diffuse) {
    vec3 direction = light.position.xyz - eye * light.position.w;
    float distance = length(direction);
    if (distance > 0.0) direction /= distance;
    return light.ambient.rgb * ambient + light.diffuse.rgb * diffuse * max(dot(normal, direction), 0.0);
}
vec4 mcglLitColor(vec4 color, vec3 inputNormal, vec3 eye, mat3 normalMatrix) {
    if (uLighting != 0) {
        vec3 normal = normalMatrix * inputNormal;
        float len = length(normal);
        if (uNormalize != 0 && len > 0.0) normal /= len;
        vec4 ambient = uColorMaterial == 4608 || uColorMaterial == 5634 ? color : uGameMaterial.ambient;
        vec4 diffuse = uColorMaterial == 4609 || uColorMaterial == 5634 ? color : uGameMaterial.diffuse;
        vec3 lit = ambient.rgb * uGameLightModel.ambient.rgb;
        if (uLightEnabled0 != 0) lit += mcglLightContribution(uGameLight0, normal, eye, ambient.rgb, diffuse.rgb);
        if (uLightEnabled1 != 0) lit += mcglLightContribution(uGameLight1, normal, eye, ambient.rgb, diffuse.rgb);
        color = vec4(clamp(lit, 0.0, 1.0), diffuse.a);
    }
    return color;
}
