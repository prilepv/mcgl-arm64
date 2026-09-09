// Preamble is the shared, explicit game input contract, not driver built-ins.
uniform mat4 uTextureMatrix, uLightmapMatrix;
uniform int uLighting, uNormalize, uColorMaterial, uLightEnabled0, uLightEnabled1;
layout(location=5) in vec4 aFlatColor;
layout(location=6) in vec3 aFlatNormal;
layout(location=8) in float aFlatMask;
out vec4 vColor;
flat out vec4 vFlatColor;
out vec3 vEyePosition, vUv, vLightmap;

vec3 lightContribution(GameLight light, vec3 normal, vec3 eye, vec3 ambient, vec3 diffuse) {
    vec3 direction = light.position.xyz - eye * light.position.w;
    float distance = length(direction);
    if (distance > 0.0) direction /= distance;
    return light.ambient.rgb * ambient + light.diffuse.rgb * diffuse * max(dot(normal, direction), 0.0);
}
vec4 vertexColor(vec4 color, vec3 inputNormal, vec3 eye) {
    if (uLighting != 0) {
        vec3 normal = mcglNormalMatrix() * inputNormal;
        float len = length(normal);
        if (uNormalize != 0 && len > 0.0) normal /= len;
        vec4 ambient = uColorMaterial == 4608 || uColorMaterial == 5634 ? color : uGameMaterial.ambient;
        vec4 diffuse = uColorMaterial == 4609 || uColorMaterial == 5634 ? color : uGameMaterial.diffuse;
        vec3 lit = ambient.rgb * uGameLightModel.ambient.rgb;
        if (uLightEnabled0 != 0) lit += lightContribution(uGameLight0, normal, eye, ambient.rgb, diffuse.rgb);
        if (uLightEnabled1 != 0) lit += lightContribution(uGameLight1, normal, eye, ambient.rgb, diffuse.rgb);
        color = vec4(clamp(lit, 0.0, 1.0), diffuse.a);
    }
    return color;
}
void main() {
    mcglChunkInputs();
    vec4 eye = mcglModelViewMatrix() * vec4(aPosition, 1.0);
    gl_Position = uProjection * eye;
    vEyePosition = eye.xyz;
    vec4 uv = uTextureMatrix * vec4(mcglVertexUv(), 0.0, 1.0);
    vec4 lm = uLightmapMatrix * vec4(mcglVertexLightmap(), 0.0, 1.0);
    vUv = vec3(uv.xy, uv.w);
    vLightmap = vec3(lm.xy, lm.w);
    vColor = vertexColor(mcglVertexColor(), mcglVertexNormal(), eye.xyz);
    int flatMask = (uGameAttributeMask & 256) != 0 ? int(aFlatMask) : uGameAttributeMask;
    vec4 flatColor = (uGameAttributeMask & 32) != 0 ? ((flatMask & 2) != 0 ? aFlatColor : uGameColor) : mcglVertexColor();
    vec3 flatNormal = (uGameAttributeMask & 64) != 0 ? ((flatMask & 16) != 0 ? aFlatNormal : uGameNormal) : mcglVertexNormal();
    vFlatColor = vertexColor(flatColor, flatNormal, eye.xyz);
    if ((uGameAttributeMask & 512) != 0) gl_Position = mcglExpandLine(gl_Position, uProjection * uModelView * vec4(aLineOther, 1.0));
    gl_PointSize = 1.0;
}
