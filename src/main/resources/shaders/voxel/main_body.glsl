// ---------------------------------------------------------------------------------------------
// Primary compute shader body that ray marches the voxel world and shades visible surfaces.
// Handles LOD transitions, dynamic lighting and secondary effects before writing to uOutput.
// ---------------------------------------------------------------------------------------------
// --------------------------- main ---------------------------
void main(){
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    if (gid.x >= uResolution.x || gid.y >= uResolution.y) return;

    // Primary ray
    vec2 uv = (vec2(gid) + vec2(0.5)) / vec2(uResolution) * 2.0 - 1.0;
    vec4 pNear = uInvProj * vec4(uv, -1.0, 1.0);
    vec4 pFar  = uInvProj * vec4(uv, 1.0, 1.0);
    pNear /= pNear.w; pFar /= pFar.w;

    vec3 ro = uCamPos;
    vec3 rd = normalize((uInvView * vec4(normalize(pFar.xyz - pNear.xyz), 0.0)).xyz);
    vec3 roLocal = ro - vec3(uRegionOrigin);

    // Region AABB
    vec3 tMin = (vec3(0.0) - roLocal) / rd;
    vec3 tMax = (vec3(uWorldSize) - roLocal) / rd;
    vec3 t1 = min(tMin, tMax);
    vec3 t2 = max(tMin, tMax);
    float tEnter = max(max(t1.x, t1.y), t1.z);
    float tExit  = min(min(t2.x, t2.y), t2.z);
    if (tExit < max(tEnter, 0.0)){
        imageStore(uOutput, gid, vec4(envSky(rd), 1.0));
        return;
    }

    // DDA setup with multi-tier distance-based LOD
    float tEnterClamped = max(tEnter, 0.0);
    vec3  dirStep = sign(rd);
    ivec3 stepI   = ivec3(dirStep);
    ivec3 off     = ivec3(stepI.x > 0 ? 1 : 0,
    stepI.y > 0 ? 1 : 0,
    stepI.z > 0 ? 1 : 0);

    bool hit = false;
    uint blockId = 0u;
    vec3 color = vec3(0.0);

    float lodScaleNear = max(uLodScale, 1.0);
    float lodScaleFar = max(max(uLodScaleFar, lodScaleNear), 1.0);
    float lodSwitchNear = max(uLodSwitchDistance, 0.0);
    float lodSwitchFar = max(uLodSwitchDistanceFar, lodSwitchNear);
    float lodBand = max(uLodTransitionBand, 0.0);

    bool useNear = (lodScaleNear > 1.0) && (lodSwitchNear > 0.0);
    bool useFar = (lodScaleFar > lodScaleNear) && (lodSwitchFar > lodSwitchNear);

    float accumulatedT = 0.0;
    bool continueTracing = true;

    if (useFar && continueTracing){
        vec3 pStartFar = roLocal + rd * tEnterClamped;
        float switchFarStart = max(lodSwitchFar + lodBand, 0.0);
        float switchFarStart2 = switchFarStart * switchFarStart;
        if (dot(pStartFar, pStartFar) > switchFarStart2){
            ivec3 vFar = ivec3(floor(pStartFar / lodScaleFar));
            vec3 nextBoundaryFar = vec3(vFar + off) * lodScaleFar;
            vec3 tMaxFar  = (nextBoundaryFar - pStartFar) / rd;
            vec3 tDeltaFar = abs(lodScaleFar / rd);
            vec3 lastNFar = vec3(0.0);
            float farT = 0.0;
            float switchFarExit = max(lodSwitchFar - lodBand, 0.0);
            float switchFarExit2 = switchFarExit * switchFarExit;

            for (int iter=0; iter<1024; ++iter){
                if (!inBoundsFar(vFar)){ continueTracing = false; break; }

                vec3 cellCenter = vec3(vFar) * lodScaleFar + vec3(0.5 * lodScaleFar);
                vec3 offsetFar = cellCenter - roLocal;
                float distFar2 = dot(offsetFar, offsetFar);
                if (distFar2 <= switchFarExit2){
                    break;
                }

                uint farId = loadVoxelFar(vFar);
                if (farId != 0u){
                    vec3 n = any(notEqual(lastNFar, vec3(0.0))) ? lastNFar : normalize(-rd);
                    vec3 pHit = cellCenter + n * (0.5 * lodScaleFar);
                    color = shadeVoxel(farId, pHit, n, rd);
                    blockId = farId;
                    hit = true;
                    continueTracing = false;
                    break;
                }

                if (tMaxFar.x < tMaxFar.y){
                    if (tMaxFar.x < tMaxFar.z){
                        farT = tMaxFar.x;
                        vFar.x += stepI.x;
                        tMaxFar.x += tDeltaFar.x;
                        lastNFar = vec3(-dirStep.x, 0.0, 0.0);
                    } else {
                        farT = tMaxFar.z;
                        vFar.z += stepI.z;
                        tMaxFar.z += tDeltaFar.z;
                        lastNFar = vec3(0.0, 0.0, -dirStep.z);
                    }
                } else {
                    if (tMaxFar.y < tMaxFar.z){
                        farT = tMaxFar.y;
                        vFar.y += stepI.y;
                        tMaxFar.y += tDeltaFar.y;
                        lastNFar = vec3(0.0, -dirStep.y, 0.0);
                    } else {
                        farT = tMaxFar.z;
                        vFar.z += stepI.z;
                        tMaxFar.z += tDeltaFar.z;
                        lastNFar = vec3(0.0, 0.0, -dirStep.z);
                    }
                }
            }

            if (continueTracing && !hit){
                accumulatedT += farT;
            }
        }
    }

    if (continueTracing && !hit && useNear){
        float stageStartT = tEnterClamped + accumulatedT;
        if (stageStartT > tExit){
            continueTracing = false;
        } else {
            vec3 pStartNear = roLocal + rd * stageStartT;
            float switchNearStart = max(lodSwitchNear + lodBand, 0.0);
            float switchNearStart2 = switchNearStart * switchNearStart;
            if (dot(pStartNear, pStartNear) > switchNearStart2){
                ivec3 vCoarse = ivec3(floor(pStartNear / lodScaleNear));
                vec3 nextBoundaryCoarse = vec3(vCoarse + off) * lodScaleNear;
                vec3 tMaxCoarse  = (nextBoundaryCoarse - pStartNear) / rd;
                vec3 tDeltaCoarse = abs(lodScaleNear / rd);
                vec3 lastNNear = vec3(0.0);
                float coarseT = 0.0;
                float switchNearExit = max(lodSwitchNear - lodBand, 0.0);
                float switchNearExit2 = switchNearExit * switchNearExit;

                for (int iter=0; iter<2048; ++iter){
                    if (!inBoundsCoarse(vCoarse)){ continueTracing = false; break; }

                    vec3 cellCenter = vec3(vCoarse) * lodScaleNear + vec3(0.5 * lodScaleNear);
                    vec3 offsetCoarse = cellCenter - roLocal;
                    float distCoarse2 = dot(offsetCoarse, offsetCoarse);
                    if (distCoarse2 <= switchNearExit2){
                        break;
                    }

                    uint coarseId = loadVoxelCoarse(vCoarse);
                    if (coarseId != 0u){
                        vec3 n = any(notEqual(lastNNear, vec3(0.0))) ? lastNNear : normalize(-rd);
                        vec3 pHit = cellCenter + n * (0.5 * lodScaleNear);
                        color = shadeVoxel(coarseId, pHit, n, rd);
                        blockId = coarseId;
                        hit = true;
                        continueTracing = false;
                        break;
                    }

                    if (tMaxCoarse.x < tMaxCoarse.y){
                        if (tMaxCoarse.x < tMaxCoarse.z){
                            coarseT = tMaxCoarse.x;
                            vCoarse.x += stepI.x;
                            tMaxCoarse.x += tDeltaCoarse.x;
                            lastNNear = vec3(-dirStep.x, 0.0, 0.0);
                        } else {
                            coarseT = tMaxCoarse.z;
                            vCoarse.z += stepI.z;
                            tMaxCoarse.z += tDeltaCoarse.z;
                            lastNNear = vec3(0.0, 0.0, -dirStep.z);
                        }
                    } else {
                        if (tMaxCoarse.y < tMaxCoarse.z){
                            coarseT = tMaxCoarse.y;
                            vCoarse.y += stepI.y;
                            tMaxCoarse.y += tDeltaCoarse.y;
                            lastNNear = vec3(0.0, -dirStep.y, 0.0);
                        } else {
                            coarseT = tMaxCoarse.z;
                            vCoarse.z += stepI.z;
                            tMaxCoarse.z += tDeltaCoarse.z;
                            lastNNear = vec3(0.0, 0.0, -dirStep.z);
                        }
                    }
                }

                if (continueTracing && !hit){
                    accumulatedT += coarseT;
                }
            }
        }
    }

    if (continueTracing && !hit){
        float fineStartT = tEnterClamped + accumulatedT;
        if (fineStartT <= tExit){
            float tFineParam = min(fineStartT + 1e-4, tExit);
            vec3 pFine = roLocal + rd * tFineParam;
            ivec3 v = ivec3(floor(pFine));
            vec3 nextBoundary = vec3(v + off);
            vec3 tMax3  = (nextBoundary - pFine) / rd;
            vec3 tDelta = abs(1.0 / rd);
            vec3 lastN = vec3(0.0);

            for (int iter=0; iter<4096; ++iter){
                if (!inBounds(v)) break;

                blockId = loadVoxel(v);
                if (blockId != 0u){
                    vec3 n = any(notEqual(lastN, vec3(0.0))) ? lastN : normalize(-rd);
                    vec3 pHit = vec3(v) + vec3(0.5) + 0.5 * n;
                    color = shadeVoxel(blockId, pHit, n, rd);
                    hit = true;
                    break;
                }

                if (tMax3.x < tMax3.y){
                    if (tMax3.x < tMax3.z){
                        v.x += stepI.x;
                        tMax3.x += tDelta.x;
                        lastN = vec3(-dirStep.x, 0.0, 0.0);
                    } else {
                        v.z += stepI.z;
                        tMax3.z += tDelta.z;
                        lastN = vec3(0.0, 0.0, -dirStep.z);
                    }
                } else {
                    if (tMax3.y < tMax3.z){
                        v.y += stepI.y;
                        tMax3.y += tDelta.y;
                        lastN = vec3(0.0, -dirStep.y, 0.0);
                    } else {
                        v.z += stepI.z;
                        tMax3.z += tDelta.z;
                        lastN = vec3(0.0, 0.0, -dirStep.z);
                    }
                }
            }
        }
    }

    imageStore(uOutput, gid, vec4(hit ? color : envSky(rd), 1.0));
}
