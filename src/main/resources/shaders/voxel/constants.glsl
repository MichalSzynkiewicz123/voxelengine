// ---------------------------------------------------------------------------------------------
// Mathematical helpers and small utility functions shared across voxel shader stages.
// ---------------------------------------------------------------------------------------------
// -------- constants / utils --------
const float PI  = 3.14159265359;
const float TAU = 6.28318530718;
const float GOLDEN_ANGLE = 2.39996322973;
const int   MAX_GI_SAMPLES = 32;
const int   MAX_AO_SAMPLES = 32;

float saturate(float x){ return clamp(x, 0.0, 1.0); }

vec3 skyGradient(vec3 dir){
    float t = 0.5*(dir.y*0.5 + 0.5);
    return mix(uSkyZenith, uSkyHorizon, 1.0 - t);
}

void makeONB(in vec3 n, out vec3 t, out vec3 b){
    if (abs(n.z) < 0.999) t = normalize(cross(n, vec3(0, 0, 1)));
    else t = normalize(cross(n, vec3(0, 1, 0)));
    b = cross(n, t);
}

