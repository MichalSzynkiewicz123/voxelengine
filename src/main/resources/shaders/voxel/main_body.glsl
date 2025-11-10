// ---------------------------------------------------------------------------------------------
// Voxel space heightmap renderer implemented as a compute shader.
// Each invocation renders a single screen column by sweeping depth slices across the terrain
// and drawing vertical spans similar to the classic voxel space algorithm.
// ---------------------------------------------------------------------------------------------

struct ColumnSample {
    float height;
    uint blockId;
};

int wrapCoord(int value, int size) {
    if (size <= 0) {
        return 0;
    }
    int m = value % size;
    return m < 0 ? m + size : m;
}

int voxelIndex(int x, int y, int z) {
    return x + uWorldSize.x * (y + uWorldSize.y * z);
}

uint loadVoxelColumn(int x, int y, int z) {
    if (x < 0 || x >= uWorldSize.x || y < 0 || y >= uWorldSize.y || z < 0 || z >= uWorldSize.z) {
        return 0u;
    }
    return data[voxelIndex(x, y, z)];
}

ColumnSample sampleColumn(int mapX, int mapZ) {
    ColumnSample sample;
    sample.height = float(uRegionOrigin.y);
    sample.blockId = 0u;
    if (mapX < 0 || mapX >= uWorldSize.x || mapZ < 0 || mapZ >= uWorldSize.z) {
        return sample;
    }
    for (int y = uWorldSize.y - 1; y >= 0; --y) {
        uint id = loadVoxelColumn(mapX, y, mapZ);
        if (id != 0u) {
            sample.height = float(uRegionOrigin.y + y + 1);
            sample.blockId = id;
            return sample;
        }
    }
    return sample;
}

float columnHeight(int mapX, int mapZ) {
    if (mapX < 0 || mapX >= uWorldSize.x || mapZ < 0 || mapZ >= uWorldSize.z) {
        return float(uRegionOrigin.y);
    }
    for (int y = uWorldSize.y - 1; y >= 0; --y) {
        uint id = loadVoxelColumn(mapX, y, mapZ);
        if (id != 0u) {
            return float(uRegionOrigin.y + y + 1);
        }
    }
    return float(uRegionOrigin.y);
}

vec3 blockColor(uint id) {
    if (id == 1u) return vec3(0.27, 0.62, 0.23);
    if (id == 2u) return vec3(0.35, 0.22, 0.12);
    if (id == 3u) return vec3(0.50, 0.50, 0.50);
    if (id == 4u) return vec3(0.82, 0.76, 0.52);
    if (id == 5u) return vec3(0.95, 0.95, 0.97);
    if (id == 6u) return vec3(0.43, 0.29, 0.18);
    if (id == 7u) return vec3(0.21, 0.45, 0.15);
    if (id == 8u) return vec3(0.29, 0.46, 0.28);
    return vec3(0.0);
}

float computeShading(int mapX, int mapZ, float height, float z, float maxDistance) {
    int eastX = wrapCoord(mapX + 1, uWorldSize.x);
    int northZ = wrapCoord(mapZ + 1, uWorldSize.z);
    float eastHeight = columnHeight(eastX, mapZ);
    float northHeight = columnHeight(mapX, northZ);
    float slope = ((height - eastHeight) + (height - northHeight)) * 0.5;
    float slopeTerm = clamp(0.75 + slope * 0.02, 0.0, 1.0);
    float fog = clamp(1.0 - z / maxDistance, 0.0, 1.0);
    return clamp(0.35 + slopeTerm * 0.65 * fog, 0.0, 1.0);
}

void drawVerticalSpan(int column, float top, float bottom, vec3 color) {
    if (bottom <= 0.0) {
        return;
    }
    int screenHeight = uResolution.y;
    int yStart = int(ceil(max(0.0, top)));
    int yEnd = int(floor(min(bottom, float(screenHeight - 1))));
    if (yStart > yEnd) {
        return;
    }
    vec4 rgba = vec4(clamp(color, 0.0, 1.0), 1.0);
    for (int y = yStart; y <= yEnd; ++y) {
        imageStore(uOutput, ivec2(column, y), rgba);
    }
}

void paintBackground(int column, int screenHeight, float horizon) {
    float horizonClamped = clamp(horizon, 0.0, float(screenHeight));
    vec3 skyTop = vec3(0.415, 0.663, 1.0);
    vec3 skyBottom = vec3(0.709, 0.831, 1.0);
    vec3 groundTop = vec3(0.494, 0.356, 0.235);
    vec3 groundBottom = vec3(0.176, 0.122, 0.071);
    for (int y = 0; y < screenHeight; ++y) {
        vec3 color;
        if (float(y) < horizonClamped) {
            float t = horizonClamped <= 1.0 ? 0.0 : float(y) / horizonClamped;
            color = mix(skyTop, skyBottom, clamp(t, 0.0, 1.0));
        } else {
            float groundHeight = float(screenHeight) - horizonClamped;
            float denom = max(groundHeight, 1.0);
            float t = (float(y) - horizonClamped) / denom;
            color = mix(groundTop, groundBottom, clamp(t, 0.0, 1.0));
        }
        imageStore(uOutput, ivec2(column, y), vec4(color, 1.0));
    }
}

ivec2 worldToMap(vec2 worldXZ) {
    int worldX = int(floor(worldXZ.x));
    int worldZ = int(floor(worldXZ.y));
    int localX = worldX - uRegionOrigin.x;
    int localZ = worldZ - uRegionOrigin.z;
    int width = uWorldSize.x;
    int depth = uWorldSize.z;
    if (width <= 0 || depth <= 0) {
        return ivec2(0);
    }
    int mapX = wrapCoord(localX, width);
    int mapZ = wrapCoord(localZ, depth);
    return ivec2(mapX, mapZ);
}

void main() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    if (gid.x >= uResolution.x || gid.y >= uResolution.y) {
        return;
    }
    if (gid.y != 0) {
        return;
    }

    int column = gid.x;
    int screenHeight = uResolution.y;
    float cameraHeight = uCamPos.y;
    float horizon = uVoxelSpaceHorizon;
    float scaleHeight = max(uVoxelSpaceScale, 1.0);
    float maxDistance = max(uVoxelSpaceDistance, 1.0);
    float sinPhi = sin(uVoxelSpaceYaw);
    float cosPhi = cos(uVoxelSpaceYaw);

    paintBackground(column, screenHeight, horizon);

    float yBuffer = float(screenHeight);
    float dz = 1.0;
    float z = 1.0;
    int screenWidth = uResolution.x;

    while (z < maxDistance) {
        vec2 camPosXZ = vec2(uCamPos.x, uCamPos.z);
        vec2 start = vec2(-cosPhi * z - sinPhi * z, sinPhi * z - cosPhi * z) + camPosXZ;
        vec2 end = vec2(cosPhi * z - sinPhi * z, -sinPhi * z - cosPhi * z) + camPosXZ;
        vec2 delta = (end - start) / float(screenWidth);
        vec2 samplePos = start + delta * (float(column) + 0.5);
        ivec2 mapCoord = worldToMap(samplePos);

        ColumnSample sample = sampleColumn(mapCoord.x, mapCoord.y);
        if (sample.blockId != 0u) {
            float projectedHeight = ((cameraHeight - sample.height) / z) * scaleHeight + horizon;
            if (projectedHeight < yBuffer) {
                float shading = computeShading(mapCoord.x, mapCoord.y, sample.height, z, maxDistance);
                vec3 color = blockColor(sample.blockId) * shading;
                drawVerticalSpan(column, projectedHeight, yBuffer, color);
                yBuffer = projectedHeight;
            }
        }

        z += dz;
        dz += 0.2;
    }
}
