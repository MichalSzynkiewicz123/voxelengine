#version 430 core

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in mat4 iTransform;
layout(location = 6) in float iBlockId;
layout(location = 7) in float iAlpha;

uniform mat4 uProj;
uniform mat4 uView;

out vec3 vNormal;
flat out int vBlockId;
out float vAlpha;

void main() {
    mat3 normalMatrix = mat3(iTransform);
    vNormal = normalMatrix * aNormal;
    vBlockId = int(iBlockId + 0.5);
    vAlpha = iAlpha;
    gl_Position = uProj * uView * iTransform * vec4(aPosition, 1.0);
}
