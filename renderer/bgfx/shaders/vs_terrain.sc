$input a_position, a_color0, a_color1, a_normal, a_tangent, a_texcoord0, a_texcoord1
$output v_color0, v_uv, v_lightmap, v_eye

#include <bgfx_shader.sh>

uniform vec4 u_terrain[29];
uniform vec4 u_draw;

vec4 lightColor(vec4 color, vec3 normal, vec3 eye)
{
    vec4 lighting = u_terrain[16];
    if (lighting.y != 0.0 && length(normal) > 0.0) normal = normalize(normal);
    vec4 ambient = lighting.z != 0.0 ? color : u_terrain[19];
    vec4 diffuse = lighting.w != 0.0 ? color : u_terrain[20];
    vec3 result = ambient.rgb * u_terrain[18].rgb;
    int activeLights = int(u_terrain[17].w);
    for (int i = 0; i < 2; i++)
    {
        if ((activeLights & (1 << i)) != 0)
        {
            int at = 21 + 3 * i;
            vec4 light = u_terrain[at];
            vec3 direction = light.xyz - eye * light.w;
            if (length(direction) > 0.0) direction = normalize(direction);
            result += u_terrain[at + 1].rgb * ambient.rgb
                    + u_terrain[at + 2].rgb * diffuse.rgb * max(dot(normal, direction), 0.0);
        }
    }
    return vec4(clamp(result, 0.0, 1.0), diffuse.a);
}

void main()
{
    int mask = int(u_draw.x);
    int flags = int(u_terrain[28].x);
    vec4 eye = mul(u_model[0], vec4(a_position, 1.0));
    mat4 projection = mtxFromCols(u_terrain[0], u_terrain[1], u_terrain[2], u_terrain[3]);
    mat4 uvMatrix = mtxFromCols(u_terrain[4], u_terrain[5], u_terrain[6], u_terrain[7]);
    mat4 lightMatrix = mtxFromCols(u_terrain[8], u_terrain[9], u_terrain[10], u_terrain[11]);
    vec2 uv = (mask & 4) != 0 ? a_texcoord0 : u_terrain[15].xy;
    vec2 light = (mask & 8) != 0 ? a_texcoord1 : u_terrain[15].zw;
    gl_Position = mul(projection, eye);
    v_eye = eye.xyz;
    v_uv = mul(uvMatrix, vec4(uv, 0.0, 1.0)).xyw;
    v_lightmap = mul(lightMatrix, vec4(light, 0.0, 1.0)).xyw;
    // OpenGL framebuffer textures start at the bottom; Metal render targets start at the top.
    if (u_draw.y != 0.0) v_uv.y = v_uv.z - v_uv.y;
    if (u_draw.z != 0.0) v_lightmap.y = v_lightmap.z - v_lightmap.y;
    vec4 color = (mask & 2) != 0 ? a_color0 : u_terrain[12];
    // The original quad converter writes the provoking color to every vertex of the face.
    bool flatShade = (flags & 4) != 0;
    v_color0 = flatShade && (mask & 34) == 34 ? a_color1 : color;
    if (u_terrain[16].x != 0.0)
    {
        mat4 model = u_model[0];
        vec3 a = mul(model, vec4(1.0, 0.0, 0.0, 0.0)).xyz;
        vec3 b = mul(model, vec4(0.0, 1.0, 0.0, 0.0)).xyz;
        vec3 c = mul(model, vec4(0.0, 0.0, 1.0, 0.0)).xyz;
        vec3 x = cross(b, c), y = cross(c, a), z = cross(a, b);
        float determinant = dot(a, x);
        vec3 normal = (mask & 16) != 0 ? a_normal.xyz : u_terrain[17].xyz;
        if (flatShade && (mask & 80) == 80) normal = a_tangent.xyz;
        vec3 transformed = determinant == 0.0 ? vec3(0.0) : (x * normal.x + y * normal.y + z * normal.z) / determinant;
        v_color0 = lightColor(v_color0, transformed, eye.xyz);
    }
}
