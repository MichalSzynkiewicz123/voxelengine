#version 330 core

uniform vec2 u_resolution;
uniform float u_time;

layout(location = 0) out vec4 fragColor;

#define PI 3.1415925359
#define TWO_PI 6.2831852
#define MAX_STEPS 100
#define MAX_DIST 100.0
#define SURFACE_DIST 0.01

float hash31(vec3 p) {
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453);
}

float boxSDF(vec3 p, vec3 b) {
    vec3 q = abs(p) - b;
    return length(max(q, vec3(0.0))) + min(max(q.x, max(q.y, q.z)), 0.0);
}

bool voxelOccupied(vec3 cell) {
    return hash31(cell) > 0.65;
}

float GetDist(vec3 p) {
    vec3 cell = floor(p);
    float minDist = MAX_DIST;
    for (int dz = -1; dz <= 1; ++dz) {
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dx = -1; dx <= 1; ++dx) {
                vec3 neighbor = cell + vec3(dx, dy, dz);
                if (voxelOccupied(neighbor)) {
                    vec3 local = p - (neighbor + vec3(0.5));
                    float d = boxSDF(local, vec3(0.5));
                    minDist = min(minDist, d);
                }
            }
        }
    }
    return minDist;
}

float RayMarch(vec3 ro, vec3 rd) {
    float dO = 0.0;
    for (int i = 0; i < MAX_STEPS; i++) {
        vec3 p = ro + rd * dO;
        float ds = GetDist(p);
        dO += ds;
        if (dO > MAX_DIST || ds < SURFACE_DIST) {
            break;
        }
    }
    return dO;
}

vec3 estimateNormal(vec3 p) {
    const vec2 e = vec2(1.0, -1.0) * 0.5773 * SURFACE_DIST;
    return normalize(e.xyy * GetDist(p + e.xyy) +
                     e.yyx * GetDist(p + e.yyx) +
                     e.yxy * GetDist(p + e.yxy) +
                     e.xxx * GetDist(p + e.xxx));
}

void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution.xy) / u_resolution.y;
    vec3 ro = vec3(0.0, 2.5, u_time * 0.5);
    vec3 target = vec3(0.0, 1.5, u_time * 0.5 + 1.5);
    vec3 forward = normalize(target - ro);
    vec3 right = normalize(cross(forward, vec3(0.0, 1.0, 0.0)));
    vec3 up = cross(right, forward);
    vec3 rd = normalize(forward + uv.x * right + uv.y * up);

    float d = RayMarch(ro, rd);
    vec3 color = vec3(0.0);

    if (d < MAX_DIST) {
        vec3 p = ro + rd * d;
        vec3 normal = estimateNormal(p);
        vec3 lightDir = normalize(vec3(0.6, 1.0, 0.4));
        float diffuse = max(dot(normal, lightDir), 0.0);
        float ambient = 0.2;
        color = vec3(0.4, 0.7, 0.9) * (ambient + diffuse);
    }

    fragColor = vec4(color, 1.0);
}
