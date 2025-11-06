// -------- uniforms --------
uniform ivec3 uWorldSize;
uniform ivec3 uWorldSizeCoarse;
uniform ivec3 uWorldSizeFar;
uniform ivec3 uRegionOrigin;
uniform vec3  uCamPos;
uniform mat4  uInvProj;
uniform mat4  uInvView;
uniform vec3  uSunDir;// normalized
uniform ivec2 uResolution;
uniform float uVoxelScale;
uniform float uLodScale;
uniform float uLodScaleFar;
uniform float uLodSwitchDistance;
uniform float uLodSwitchDistanceFar;
uniform float uLodTransitionBand;
uniform int   uDebugGradient;

// Sky model / Preetham
uniform int   uSkyModel;// 0=gradient, 1=Preetham
uniform float uTurbidity;// ~2–3 clear, 6–8 hazy
uniform vec3  uSkyZenith;// fallback gradient top
uniform vec3  uSkyHorizon;// fallback gradient bottom
uniform float uSkyIntensity;// global sky multiplier

// Soft sun shadow params
uniform float uSunAngularRadius;// radians (~0.00465)
uniform int   uSunSoftSamples;// 1–2 recommended

// Player torch (soft point/area)
uniform int   uTorchEnabled;
uniform vec3  uTorchPos;// world voxel coords
uniform float uTorchIntensity;
uniform float uTorchRadius;// area radius
uniform int   uTorchSoftSamples;

// Secondary lighting & effects
uniform int   uGIEnabled;
uniform int   uGISampleCount;
uniform float uGIMaxDistance;
uniform float uGIIntensity;
uniform int   uGIVolumeEnabled;
uniform sampler3D uGIVolume;
uniform ivec3 uGIVolumeSize;
uniform vec3  uGIVolumeOrigin;
uniform float uGIVolumeCellSize;
uniform int   uSecondaryTraceMaxSteps;

uniform int   uAOEnabled;
uniform int   uAOSampleCount;
uniform float uAORadius;
uniform float uAOIntensity;

uniform int   uReflectionEnabled;
uniform float uReflectionMaxDistance;
uniform float uReflectionIntensity;

const int MAX_DYNAMIC_LIGHTS = 8;
uniform int   uLightCount;
uniform vec4  uLightPositions[MAX_DYNAMIC_LIGHTS]; // xyz=position, w=range
uniform vec4  uLightColors[MAX_DYNAMIC_LIGHTS];    // rgb=color,  a=intensity
uniform float uLightRadii[MAX_DYNAMIC_LIGHTS];     // emitter radius
uniform int   uLightSoftSamples;

uniform int   uShadowTraceMaxSteps;
uniform int   uShadowOccupancyScale;

