#include <bgfx_shader.sh>

SAMPLER2D(s_atlas, 0);
SAMPLER2D(s_lightmap, 1);
uniform vec4 u_terrain[29];
#if MCGL_POLYGON_OFFSET
uniform vec4 u_polygonOffset;
#endif

bool alphaPass(float a, float reference, int function)
{
    if (function == 1) return false;
    if (function == 2) return a < reference;
    if (function == 3) return a == reference;
    if (function == 4) return a <= reference;
    if (function == 5) return a > reference;
    if (function == 6) return a != reference;
    if (function == 7) return a >= reference;
    return true;
}

void main()
{
    vec4 color = v_color0;
    vec4 flags = u_terrain[28];
    vec4 fog = u_terrain[14];
    if ((int(flags.x) & 1) != 0)
    {
        vec4 texel = texture2D(s_atlas, v_uv.xy / v_uv.z);
        color *= (int(flags.x) & 8) != 0 ? vec4(u_terrain[27].rgb, texel.a) : texel;
    }
    if ((int(flags.x) & 2) != 0) color *= texture2D(s_lightmap, v_lightmap.xy / v_lightmap.z);
    if (!alphaPass(color.a, fog.w, int(flags.y))) discard;
    int mode = int(flags.z);
    if (mode != 0)
    {
        float distance = flags.w == 1.0 ? length(v_eye) : abs(v_eye.z);
        float densityDistance = fog.z * distance;
        float weight;
        if (mode == 1)
        {
            float range = fog.y - fog.x;
            weight = range == 0.0 ? (distance < fog.y ? 1.0 : 0.0) : (fog.y - distance) / range;
        }
        else if (mode == 2) weight = exp(-densityDistance);
        else weight = exp(-densityDistance * densityDistance);
        color.rgb = mix(u_terrain[13].rgb, color.rgb, clamp(weight, 0.0, 1.0));
    }
#if MCGL_POLYGON_OFFSET
    // Legacy bias adds to depth; this renderer stores reversed depth.
    float depth = gl_FragCoord.z;
    float slope = max(abs(dFdx(depth)), abs(dFdy(depth)));
    float step = exp2(floor(log2(max(depth, 1.17549435e-38))) - 23.0);
    gl_FragDepth = clamp(depth - slope * u_polygonOffset.x - step * u_polygonOffset.y, 0.0, 1.0);
#endif
    gl_FragColor = color;
}
