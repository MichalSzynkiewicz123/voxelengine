#version 430 core

layout(location = 0) in vec2 aCorner;
layout(location = 1) in vec3 iMinCorner;
layout(location = 2) in vec3 iMaxCorner;
layout(location = 3) in vec3 iMeta;

uniform mat4 uProj;
uniform mat4 uView;

out vec3 vNormal;
flat out int vBlockId;

void main() {
    int axis = int(iMeta.x + 0.5);
    bool positive = iMeta.y > 0.5;
    vBlockId = int(iMeta.z + 0.5);

    vec3 minCorner = iMinCorner;
    vec3 maxCorner = iMaxCorner;
    vec3 size = maxCorner - minCorner;

    vec3 basePos = minCorner;
    vec3 tangentU;
    vec3 tangentV;
    vec3 normal;

    if (axis == 0) {
        tangentU = vec3(0.0, size.y, 0.0);
        tangentV = vec3(0.0, 0.0, size.z);
        normal = vec3(positive ? 1.0 : -1.0, 0.0, 0.0);
        if (!positive) {
            basePos.z = maxCorner.z;
            tangentV = -tangentV;
        }
    } else if (axis == 1) {
        tangentU = vec3(size.x, 0.0, 0.0);
        tangentV = vec3(0.0, 0.0, size.z);
        normal = vec3(0.0, positive ? 1.0 : -1.0, 0.0);
        if (!positive) {
            basePos.x = maxCorner.x;
            tangentU = -tangentU;
        }
    } else {
        tangentU = vec3(size.x, 0.0, 0.0);
        tangentV = vec3(0.0, size.y, 0.0);
        normal = vec3(0.0, 0.0, positive ? 1.0 : -1.0);
        if (!positive) {
            basePos.y = maxCorner.y;
            tangentV = -tangentV;
        }
    }

    vNormal = normal;
    vec3 worldPos = basePos + aCorner.x * tangentU + aCorner.y * tangentV;
    gl_Position = uProj * uView * vec4(worldPos, 1.0);
}
