// Separate text programs: ordinary game/effect shaders and unlit UI keep their existing input cost.
uniform mat4 uProjection, uTextureMatrix, uLightmapMatrix;
layout(location=0) in vec3 aPosition;
layout(location=1) in vec4 aColor;
layout(location=2) in vec2 aTexCoord;
layout(location=3) in vec2 aLightmap;
layout(location=5) in vec4 aFlatColor;
layout(location=8) in vec4 aModel0;
layout(location=9) in vec4 aModel1;
layout(location=10) in vec4 aModel2;
layout(location=11) in vec4 aModel3;
#ifdef MCGL_TEXT_LIGHTING
layout(location=4) in vec3 aNormal;
layout(location=12) in vec3 aNormal0;
layout(location=13) in vec3 aNormal1;
layout(location=14) in vec3 aNormal2;
#endif
out vec4 vColor;
flat out vec4 vFlatColor;
out vec3 vEyePosition, vUv, vLightmap;
void main() {
    vec4 eye=mat4(aModel0,aModel1,aModel2,aModel3)*vec4(aPosition,1.0);
    gl_Position=uProjection*eye;
    vEyePosition=eye.xyz;
    vec4 uv=uTextureMatrix*vec4(aTexCoord,0.0,1.0);
    vec4 lm=uLightmapMatrix*vec4(aLightmap,0.0,1.0);
    vUv=vec3(uv.xy,uv.w);
    vLightmap=vec3(lm.xy,lm.w);
#ifdef MCGL_TEXT_LIGHTING
    mat3 normalMatrix=mat3(aNormal0,aNormal1,aNormal2);
    vColor=mcglLitColor(aColor,aNormal,eye.xyz,normalMatrix);
    vFlatColor=mcglLitColor(aFlatColor,aNormal,eye.xyz,normalMatrix);
#else
    vColor=aColor;
    vFlatColor=aFlatColor;
#endif
    gl_PointSize=1.0;
}
