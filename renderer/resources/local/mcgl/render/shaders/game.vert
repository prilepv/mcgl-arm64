// Preamble is the shared, explicit game input contract, not driver built-ins.
uniform mat4 uTextureMatrix, uLightmapMatrix;
layout(location=5) in vec4 aFlatColor;
layout(location=6) in vec3 aFlatNormal;
layout(location=8) in float aFlatMask;
out vec4 vColor;
flat out vec4 vFlatColor;
out vec3 vEyePosition, vUv, vLightmap;

void main() {
    mcglChunkInputs();
    vec4 eye = mcglModelViewMatrix() * vec4(aPosition, 1.0);
    gl_Position = uProjection * eye;
    vEyePosition = eye.xyz;
    vec4 uv = uTextureMatrix * vec4(mcglVertexUv(), 0.0, 1.0);
    vec4 lm = uLightmapMatrix * vec4(mcglVertexLightmap(), 0.0, 1.0);
    vUv = vec3(uv.xy, uv.w);
    vLightmap = vec3(lm.xy, lm.w);
    vColor = mcglLitColor(mcglVertexColor(), mcglVertexNormal(), eye.xyz, mcglNormalMatrix());
    int flatMask = (uGameAttributeMask & 256) != 0 ? int(aFlatMask) : uGameAttributeMask;
    vec4 flatColor = (uGameAttributeMask & 32) != 0 ? ((flatMask & 2) != 0 ? aFlatColor : uGameColor) : mcglVertexColor();
    vec3 flatNormal = (uGameAttributeMask & 64) != 0 ? ((flatMask & 16) != 0 ? aFlatNormal : uGameNormal) : mcglVertexNormal();
    vFlatColor = mcglLitColor(flatColor, flatNormal, eye.xyz, mcglNormalMatrix());
    if ((uGameAttributeMask & 512) != 0) gl_Position = mcglExpandLine(gl_Position, uProjection * uModelView * vec4(aLineOther, 1.0));
    gl_PointSize = 1.0;
}
