#version 330 core

out vec4 FragColor;

uniform vec2 u_resolution;
uniform float u_time;

const float PI = 3.1415925359;
const float TWO_PI = 6.2831852;
const int MAX_STEPS = 100;
const float MAX_DIST = 100.0;
const float SURFACE_DIST = 0.01;

float GetDist(vec3 p)
{
    vec4 s = vec4(0.0, 1.0, 6.0 + sin(u_time) * 3.0, 1.0);
    float sphereDist = length(p - s.xyz) - s.w;
    float planeDist = p.y;
    return min(sphereDist, planeDist);
}

float RayMarch(vec3 ro, vec3 rd)
{
    float dO = 0.0;
    for (int i = 0; i < MAX_STEPS; i++)
    {
        vec3 p = ro + rd * dO;
        float ds = GetDist(p);
        dO += ds;
        if (dO > MAX_DIST || ds < SURFACE_DIST)
        {
            break;
        }
    }
    return dO;
}

void main()
{
    vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution.xy) / u_resolution.y;
    vec3 ro = vec3(0.0, 1.0, 0.0);
    vec3 rd = normalize(vec3(uv.x, uv.y, 1.0));
    float d = RayMarch(ro, rd);
    d /= 10.0;
    vec3 color = vec3(d);
    FragColor = vec4(color, 1.0);
}
