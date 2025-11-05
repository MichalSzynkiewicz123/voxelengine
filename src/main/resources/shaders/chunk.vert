#version 430 core

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in float aBlockId;

uniform mat4 uProj;
uniform mat4 uView;

out vec3 vNormal;
flat out int vBlockId;

void main() {
    vNormal = aNormal;
    vBlockId = int(aBlockId + 0.5);
    gl_Position = uProj * uView * vec4(aPosition, 1.0);
}
