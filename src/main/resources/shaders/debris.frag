#version 430 core

layout(location = 0) out vec4 fragColor;

in vec3 vNormal;
flat in int vBlockId;
in float vAlpha;

uniform vec3 uSunDir;

vec3 blockColor(int id) {
    if (id == 1) {
        return vec3(0.32, 0.55, 0.18);
    } else if (id == 2) {
        return vec3(0.38, 0.27, 0.17);
    } else if (id == 3) {
        return vec3(0.50, 0.50, 0.50);
    } else if (id == 4) {
        return vec3(0.82, 0.75, 0.52);
    } else if (id == 5) {
        return vec3(0.90, 0.92, 0.95);
    }
    return vec3(0.0);
}

void main() {
    vec3 baseColor = blockColor(vBlockId);
    vec3 normal = normalize(vNormal);
    vec3 lightDir = normalize(-uSunDir);
    float diffuse = max(dot(normal, lightDir), 0.0);
    float lighting = 0.25 + diffuse * 0.75;
    fragColor = vec4(baseColor * lighting, clamp(vAlpha, 0.0, 1.0));
}
