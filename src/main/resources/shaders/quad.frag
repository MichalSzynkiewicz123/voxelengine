#version 430 core
uniform sampler2D uTex;
uniform ivec2 uScreenSize;
uniform int uPresentTest;
out vec4 fragColor;
void main(){
    if (uPresentTest==1){
        vec2 uv = gl_FragCoord.xy / vec2(uScreenSize);
        fragColor = vec4(1.0-uv.x, uv.y, 0.2, 1.0);
        return;
    }
    vec2 uv = (gl_FragCoord.xy + vec2(0.5)) / vec2(uScreenSize);
    vec3 col = texture(uTex, uv).rgb;
    col = pow(col, vec3(1.0/2.2));
    fragColor = vec4(col, 1.0);
}
