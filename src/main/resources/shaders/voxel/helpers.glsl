// -------- voxel helpers --------
bool inBounds(ivec3 p){
    return all(greaterThanEqual(p, ivec3(0))) && all(lessThan(p, uWorldSize));
}
uint loadVoxel(ivec3 local){
    if (!inBounds(local)) return 0u;
    int idx = local.x + uWorldSize.x * (local.y + uWorldSize.y * local.z);
    return data[idx];
}

bool inBoundsCoarse(ivec3 p){
    return all(greaterThanEqual(p, ivec3(0))) && all(lessThan(p, uWorldSizeCoarse));
}
uint loadVoxelCoarse(ivec3 local){
    if (!inBoundsCoarse(local)) return 0u;
    int idx = local.x + uWorldSizeCoarse.x * (local.y + uWorldSizeCoarse.y * local.z);
    return dataCoarse[idx];
}

bool inBoundsFar(ivec3 p){
    return all(greaterThanEqual(p, ivec3(0))) && all(lessThan(p, uWorldSizeFar));
}
uint loadVoxelFar(ivec3 local){
    if (!inBoundsFar(local)) return 0u;
    int idx = local.x + uWorldSizeFar.x * (local.y + uWorldSizeFar.y * local.z);
    return dataFar[idx];
}

// DDA traverse: returns true if any solid voxel is hit before maxDistance (negative = infinite)
bool traverseHitAnyDistance(vec3 startP, vec3 dir, float maxDistance){
    vec3 rd = normalize(dir);
    vec3 p  = startP;
    ivec3 v = ivec3(floor(p));

    vec3  dirStep = sign(rd);
    ivec3 stepI   = ivec3(dirStep);
    ivec3 off     = ivec3(stepI.x > 0 ? 1 : 0,
                          stepI.y > 0 ? 1 : 0,
                          stepI.z > 0 ? 1 : 0);

    vec3 nextBoundary = vec3(v + off);
    vec3 tMax3  = (nextBoundary - p) / rd;
    vec3 tDelta = abs(1.0 / rd);

    int maxSteps = max(uShadowTraceMaxSteps, 1);
    int occupancyScale = max(uShadowOccupancyScale, 1);
    float t = 0.0;

    for (int i=0; i<maxSteps; ++i){
        if (maxDistance > 0.0 && t > maxDistance) return false;
        if (!inBounds(v)) return false;
        if (loadVoxel(v) != 0u) return true;

        bool skipped = false;
        if (occupancyScale > 1){
            ivec3 coarse = ivec3(floor(vec3(v) / float(occupancyScale)));
            if (inBoundsCoarse(coarse)){
                if (loadVoxelCoarse(coarse) == 0u){
                    for (int s=0; s<occupancyScale; ++s){
                        if (tMax3.x < tMax3.y){
                            if (tMax3.x < tMax3.z){
                                v.x += stepI.x;
                                t = tMax3.x;
                                tMax3.x += tDelta.x;
                            } else {
                                v.z += stepI.z;
                                t = tMax3.z;
                                tMax3.z += tDelta.z;
                            }
                        } else {
                            if (tMax3.y < tMax3.z){
                                v.y += stepI.y;
                                t = tMax3.y;
                                tMax3.y += tDelta.y;
                            } else {
                                v.z += stepI.z;
                                t = tMax3.z;
                                tMax3.z += tDelta.z;
                            }
                        }
                        if (!inBounds(v)) return false;
                        if (maxDistance > 0.0 && t > maxDistance) return false;
                    }
                    skipped = true;
                }
            }
        }
        if (skipped) continue;

        if (tMax3.x < tMax3.y){
            if (tMax3.x < tMax3.z){ v.x += stepI.x; t = tMax3.x; tMax3.x += tDelta.x; }
            else { v.z += stepI.z; t = tMax3.z; tMax3.z += tDelta.z; }
        } else {
            if (tMax3.y < tMax3.z){ v.y += stepI.y; t = tMax3.y; tMax3.y += tDelta.y; }
            else { v.z += stepI.z; t = tMax3.z; tMax3.z += tDelta.z; }
        }
    }
    return false;
}

bool traverseHitAny(vec3 startP, vec3 dir){
    return traverseHitAnyDistance(startP, dir, -1.0);
}

bool traceVoxel(vec3 origin, vec3 dir, float maxDistance, int maxSteps, out vec3 hitPos, out vec3 hitNormal, out uint hitId){
    vec3 rd = normalize(dir);
    vec3 p = origin;
    ivec3 v = ivec3(floor(p));

    vec3 dirStep = sign(rd);
    ivec3 stepI = ivec3(dirStep);
    ivec3 off = ivec3(stepI.x > 0 ? 1 : 0,
                      stepI.y > 0 ? 1 : 0,
                      stepI.z > 0 ? 1 : 0);

    vec3 nextBoundary = vec3(v + off);
    vec3 tMax3 = (nextBoundary - p) / rd;
    vec3 tDelta = abs(1.0 / rd);
    vec3 lastN = vec3(0.0);

    hitPos = vec3(0.0);
    hitNormal = vec3(0.0);
    hitId = 0u;

    float t = 0.0;
    int steps = max(maxSteps, 1);
    for (int i=0; i<steps; ++i){
        if (maxDistance > 0.0 && t > maxDistance) break;

        if (tMax3.x < tMax3.y){
            if (tMax3.x < tMax3.z){
                v.x += stepI.x;
                lastN = vec3(-dirStep.x, 0.0, 0.0);
                t = tMax3.x;
                tMax3.x += tDelta.x;
            } else {
                v.z += stepI.z;
                lastN = vec3(0.0, 0.0, -dirStep.z);
                t = tMax3.z;
                tMax3.z += tDelta.z;
            }
        } else {
            if (tMax3.y < tMax3.z){
                v.y += stepI.y;
                lastN = vec3(0.0, -dirStep.y, 0.0);
                t = tMax3.y;
                tMax3.y += tDelta.y;
            } else {
                v.z += stepI.z;
                lastN = vec3(0.0, 0.0, -dirStep.z);
                t = tMax3.z;
                tMax3.z += tDelta.z;
            }
        }

        if (!inBounds(v)) break;
        if (maxDistance > 0.0 && t > maxDistance) break;

        uint id = loadVoxel(v);
        if (id != 0u){
            hitId = id;
            if (any(notEqual(lastN, vec3(0.0)))){
                hitNormal = lastN;
            } else {
                hitNormal = normalize(-rd);
            }
            hitPos = vec3(v) + vec3(0.5) + 0.5 * hitNormal;
            return true;
        }
    }
    return false;
}

// Soft sun visibility (cone samples around uSunDir)
float sunVisibility(vec3 pHit, vec3 n){
    vec3 S = normalize(uSunDir);
    float ndl = max(dot(n, S), 0.0);
    if (ndl <= 0.0) return 0.0;

    vec3 T, B; makeONB(S, T, B);
    float halfAngle = uSunAngularRadius;
    int   N = max(uSunSoftSamples, 1);

    float vis = 0.0;
    for (int i=0; i<N; ++i){
        float u = (float(i)+0.5)/float(N);
        float a = halfAngle * sqrt(u);// concentrate samples near center
        float ang = GOLDEN_ANGLE * float(i);
        vec2  d  = vec2(cos(ang), sin(ang)) * tan(a);// offset on tangent disk
        vec3  L  = normalize(S + d.x*T + d.y*B);
        bool  oc = traverseHitAny(pHit + L*1e-3, L);
        vis += oc ? 0.0 : 1.0;
    }
    return vis / float(N);
}

float sampleAreaLightVisibility(vec3 pHit, vec3 lightPos, float radius, float range, int samples, vec3 fallbackDir){
    int N = max(samples, 1);
    vec3 toCenter = lightPos - pHit;
    float centerDist = length(toCenter);
    vec3 dir = (centerDist > 1e-5) ? (toCenter / centerDist) : fallbackDir;
    float maxTravel = centerDist;
    if (range > 0.0) {
        maxTravel = min(maxTravel, range);
    }
    if (radius <= 1e-5 || N == 1){
        vec3 rayDir = (radius <= 1e-5) ? dir : fallbackDir;
        float limit = max(0.0, maxTravel - 1e-3);
        bool oc = traverseHitAnyDistance(pHit + rayDir*1e-3, rayDir, limit);
        return oc ? 0.0 : 1.0;
    }

    vec3 center = lightPos;
    vec3 W = (length(dir) > 1e-5) ? dir : fallbackDir;
    if (length(W) < 1e-5) W = vec3(0.0, 1.0, 0.0);
    W = normalize(W);
    vec3 T, B; makeONB(W, T, B);

    float sum = 0.0;
    for (int i=0; i<N; ++i){
        float u = (float(i)+0.5)/float(N);
        float ang = GOLDEN_ANGLE * float(i);
        float r = radius * sqrt(u);
        vec3 target = center + r*(cos(ang)*T + sin(ang)*B);
        vec3 L = normalize(target - pHit);
        float sampleDist = length(target - pHit);
        float limit = sampleDist;
        if (range > 0.0) {
            limit = min(limit, range);
        }
        bool oc = traverseHitAnyDistance(pHit + L*1e-3, L, max(0.0, limit - 1e-3));
        sum += oc ? 0.0 : 1.0;
    }
    return sum / float(N);
}

vec3 evaluateAreaLight(vec3 albedo, vec3 pHit, vec3 n, vec3 lightPos, vec3 lightColor, float intensity, float radius, float range, int samples){
    vec3 toLight = lightPos - pHit;
    float dist = length(toLight);
    if (dist <= 1e-4) return vec3(0.0);
    if (range > 0.0 && dist > range) return vec3(0.0);
    vec3 Ldir = toLight / dist;
    float ndl = max(dot(n, Ldir), 0.0);
    if (ndl <= 0.0) return vec3(0.0);
    float vis = sampleAreaLightVisibility(pHit, lightPos, radius, range, samples, Ldir);
    float atten = intensity / max(1.0, dist * dist);
    return albedo * lightColor * ndl * vis * atten;
}

vec3 blockColor(uint id){
    if (id==1u) return vec3(0.27, 0.62, 0.23);// GRASS
    if (id==2u) return vec3(0.35, 0.22, 0.12);// DIRT
    if (id==3u) return vec3(0.50, 0.50, 0.50);// STONE
    if (id==4u) return vec3(0.82, 0.76, 0.52);// SAND
    if (id==5u) return vec3(0.95, 0.95, 0.97);// SNOW
    if (id==6u) return vec3(0.43, 0.29, 0.18);// LOG
    if (id==7u) return vec3(0.21, 0.45, 0.15);// LEAVES
    if (id==8u) return vec3(0.29, 0.46, 0.28);// CACTUS
    return vec3(0.0);
}

float blockReflectivity(uint id){
    if (id==5u) return 0.25;// snow/ice
    if (id==3u) return 0.12;// stone
    if (id==4u) return 0.08;// sand
    if (id==1u) return 0.06;// grass
    if (id==2u) return 0.04;// dirt
    if (id==6u) return 0.07;// log
    if (id==7u) return 0.03;// leaves
    if (id==8u) return 0.05;// cactus
    return 0.05;
}

// Skylight: cosine-weighted hemisphere sample of envSky with occlusion
vec3 computeSkyIrradiance(vec3 pHit, vec3 n){
    int skySamples = 12;
    if (uAOEnabled != 0 || (uGIEnabled != 0 && uGISampleCount > 0)){
        skySamples = 2;
    }
    if (skySamples <= 0) return vec3(0.0);

    vec3 sum = vec3(0.0);
    float W  = 0.0;
    float sampleCount = float(skySamples);

    for (int i=0; i<skySamples; ++i){
        float u = (float(i) + 0.5) / sampleCount;
        float phi = GOLDEN_ANGLE * float(i);
        float y = u;
        float r = sqrt(max(0.0, 1.0 - y*y));
        vec3  L = vec3(cos(phi)*r, y, sin(phi)*r);// world-up oriented
        float k = max(dot(n, L), 0.0);
        if (k <= 0.0) continue;
        bool oc = traverseHitAny(pHit + L*1e-3, L);
        if (!oc) sum += envSky(L) * k;
        W += k;
    }
    return (W > 1e-5) ? (sum / W) : vec3(0.0);
}

float computeAmbientOcclusion(vec3 pHit, vec3 n){
    if (uAOEnabled == 0) return 1.0;
    int samples = clamp(uAOSampleCount, 0, MAX_AO_SAMPLES);
    if (samples <= 0) return 1.0;
    float radius = max(uAORadius, 0.0);
    if (radius <= 1e-4) return 1.0;

    vec3 T, B; makeONB(n, T, B);
    float occlusion = 0.0;
    float weightSum = 0.0;

    for (int i=0; i<samples; ++i){
        float u = (float(i) + 0.5) / float(samples);
        float v = fract(float(i) * 0.75487766);
        float cosTheta = sqrt(max(0.0, 1.0 - u));
        float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
        float phi = TAU * v;
        vec3 hemi = vec3(cos(phi) * sinTheta, cosTheta, sin(phi) * sinTheta);
        vec3 sampleDir = normalize(hemi.x * T + hemi.y * n + hemi.z * B);
        float weight = max(dot(n, sampleDir), 0.0);
        if (weight <= 1e-3) continue;
        bool blocked = traverseHitAnyDistance(pHit + sampleDir * 1e-3, sampleDir, radius);
        if (blocked) occlusion += weight;
        weightSum += weight;
    }

    if (weightSum <= 1e-4) return 1.0;
    float occ = occlusion / weightSum;
    float intensity = max(uAOIntensity, 0.0);
    float scaled = clamp(occ * intensity, 0.0, 1.0);
    return saturate(1.0 - scaled);
}

vec3 computeDirectLighting(vec3 albedo, vec3 pHit, vec3 n){
    vec3 Ls = normalize(uSunDir);
    float ndl = max(dot(n, Ls), 0.0);
    float sunVis = (ndl > 0.0) ? sunVisibility(pHit, n) : 0.0;
    vec3 result = albedo * (ndl * sunVis);

    if (uTorchEnabled != 0){
        result += evaluateAreaLight(albedo, pHit, n, uTorchPos, vec3(1.0), uTorchIntensity, uTorchRadius, 0.0, max(uTorchSoftSamples, 1));
    }

    int count = clamp(uLightCount, 0, MAX_DYNAMIC_LIGHTS);
    int samples = max(uLightSoftSamples, 1);
    for (int i=0; i<count; ++i){
        vec4 lp = uLightPositions[i];
        vec4 lc = uLightColors[i];
        if (lc.a <= 0.0) continue;
        float radius = uLightRadii[i];
        float range = lp.w;
        result += evaluateAreaLight(albedo, pHit, n, lp.xyz, lc.rgb, lc.a, radius, range, samples);
    }
    return result;
}

vec3 sampleGIVolume(vec3 worldPos){
    if (uGIVolumeEnabled == 0) return vec3(0.0);
    vec3 size = vec3(uGIVolumeSize);
    if (size.x <= 0.0 || size.y <= 0.0 || size.z <= 0.0) return vec3(0.0);
    float cellSize = max(uGIVolumeCellSize, 1e-4);
    vec3 coord = (worldPos - uGIVolumeOrigin) / cellSize;
    vec3 uvw = (coord + vec3(0.5)) / size;
    if (any(lessThan(uvw, vec3(0.0))) || any(greaterThan(uvw, vec3(1.0)))) return vec3(0.0);
    return texture(uGIVolume, uvw).rgb;
}

vec3 computeVolumeGI(vec3 albedo, vec3 pHit, vec3 n){
    if (uGIVolumeEnabled == 0) return vec3(0.0);
    vec3 worldPos = pHit + vec3(uRegionOrigin);
    vec3 sum = vec3(0.0);
    float weightSum = 0.0;

    vec3 baseSample = sampleGIVolume(worldPos);
    if (any(greaterThan(baseSample, vec3(1e-5)))){
        sum += baseSample * 0.35;
        weightSum += 0.35;
    }

    vec3 T, B; makeONB(n, T, B);
    int cones = clamp(uGISampleCount, 1, 6);
    float stepSize = max(uGIVolumeCellSize, 1e-3);
    for (int i=0; i<cones; ++i){
        vec3 dir;
        if (i == 0) dir = n;
        else if (i == 1) dir = normalize(n + T);
        else if (i == 2) dir = normalize(n - T);
        else if (i == 3) dir = normalize(n + B);
        else if (i == 4) dir = normalize(n - B);
        else dir = normalize(n + T + B);
        float distance = stepSize * (1.0 + float(i) * 0.75);
        vec3 samplePos = worldPos + dir * distance;
        vec3 sampleValue = sampleGIVolume(samplePos);
        float weight = max(dot(n, dir), 0.0);
        if (weight <= 1e-3) continue;
        sum += sampleValue * weight;
        weightSum += weight;
    }

    if (weightSum <= 1e-4) return vec3(0.0);
    return albedo * (sum / weightSum) * uGIIntensity;
}

vec3 computeIndirectGIPathTrace(vec3 albedo, vec3 pHit, vec3 n){
    int samples = clamp(uGISampleCount, 0, MAX_GI_SAMPLES);
    if (samples <= 0) return vec3(0.0);

    vec3 T, B; makeONB(n, T, B);
    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    int traceSteps = max(uSecondaryTraceMaxSteps, 1);

    for (int i=0; i<samples; ++i){
        float u = (float(i) + 0.5) / float(samples);
        float v = fract(float(i) * 0.75487766);
        float cosTheta = sqrt(max(0.0, 1.0 - u));
        float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
        float phi = TAU * v;
        vec3 hemi = vec3(cos(phi) * sinTheta, cosTheta, sin(phi) * sinTheta);
        vec3 sampleDir = normalize(hemi.x * T + hemi.y * n + hemi.z * B);
        float weight = max(dot(n, sampleDir), 0.0);
        if (weight <= 1e-3) continue;

        vec3 start = pHit + n * 1e-3;
        vec3 hitPos, hitNormal;
        uint hitId;
        if (traceVoxel(start, sampleDir, uGIMaxDistance, traceSteps, hitPos, hitNormal, hitId)){
            vec3 bounceAlbedo = blockColor(hitId);
            vec3 bounce = computeDirectLighting(bounceAlbedo, hitPos, hitNormal)
                        + bounceAlbedo * computeSkyIrradiance(hitPos, hitNormal);
            sum += bounce * weight;
        } else {
            sum += envSky(sampleDir) * weight;
        }
        weightSum += weight;
    }

    if (weightSum > 0.0) sum /= weightSum;
    return albedo * sum * uGIIntensity;
}

vec3 computeIndirectGI(vec3 albedo, vec3 pHit, vec3 n){
    if (uGIEnabled == 0) return vec3(0.0);
    if (uGIVolumeEnabled != 0){
        vec3 volumeGi = computeVolumeGI(albedo, pHit, n);
        float peak = max(max(volumeGi.r, volumeGi.g), volumeGi.b);
        if (peak > 1e-4) return volumeGi;
    }
    return computeIndirectGIPathTrace(albedo, pHit, n);
}

vec3 computeReflections(uint blockId, vec3 albedo, vec3 pHit, vec3 n, vec3 viewDir){
    if (uReflectionEnabled == 0) return vec3(0.0);
    float reflectivity = blockReflectivity(blockId) * uReflectionIntensity;
    if (reflectivity <= 0.0) return vec3(0.0);

    vec3 reflectDir = normalize(reflect(-viewDir, n));
    vec3 start = pHit + reflectDir * 1e-3;
    vec3 hitPos, hitNormal;
    uint hitId;
    bool hit = traceVoxel(start, reflectDir, uReflectionMaxDistance, uSecondaryTraceMaxSteps, hitPos, hitNormal, hitId);
    vec3 reflectedColor = envSky(reflectDir);
    if (hit){
        vec3 hitAlbedo = blockColor(hitId);
        reflectedColor = computeDirectLighting(hitAlbedo, hitPos, hitNormal)
                       + hitAlbedo * computeSkyIrradiance(hitPos, hitNormal);
    }
    return reflectedColor * reflectivity;
}

vec3 shadeVoxel(uint blockId, vec3 pHit, vec3 n, vec3 viewDir){
    vec3 albedo = blockColor(blockId);
    vec3 direct = computeDirectLighting(albedo, pHit, n);
    vec3 skyl = albedo * computeSkyIrradiance(pHit, n);
    vec3 gi = computeIndirectGI(albedo, pHit, n);
    vec3 refl = computeReflections(blockId, albedo, pHit, n, viewDir);
    float ao = computeAmbientOcclusion(pHit, n);
    vec3 ambient = (skyl + gi + refl) * ao;
    return direct + ambient;
}

