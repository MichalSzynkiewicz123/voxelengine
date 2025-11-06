package com.example.voxelrt.render;

import com.example.voxelrt.ActiveRegion;
import com.example.voxelrt.Blocks;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;

/**
 * Coarse light propagation volume used to approximate global illumination.
 * <p>
 * The volume divides the active region into larger cells, injects direct light
 * from visible block faces and dynamic light sources, then propagates the
 * accumulated energy over a few iterations. The result is uploaded to a 3D
 * texture that can be sampled by the compute shader to gather soft bounced
 * lighting without performing expensive secondary ray traces.
 */
public final class LightPropagationVolume {
    private static final float[][] BLOCK_COLORS = {
            {0f, 0f, 0f},                    // Air
            {0.32f, 0.55f, 0.18f},           // Grass
            {0.38f, 0.27f, 0.17f},           // Dirt
            {0.50f, 0.50f, 0.50f},           // Stone
            {0.82f, 0.75f, 0.52f},           // Sand
            {0.90f, 0.92f, 0.95f},           // Snow
            {0.43f, 0.29f, 0.18f},           // Log
            {0.21f, 0.45f, 0.15f},           // Leaves
            {0.29f, 0.46f, 0.28f}            // Cactus
    };

    private static final int[][] FACE_NORMALS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private static final int[][] NEIGHBOR_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private final int cellSize;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private float[] emission;
    private float[] radiance;
    private float[] ping;
    private float[] occupancy;
    private FloatBuffer uploadBuffer;
    private final Vector3f origin = new Vector3f();
    private boolean dirty;

    public LightPropagationVolume(int cellSize) {
        this.cellSize = Math.max(1, cellSize);
    }

    public int cellSize() {
        return cellSize;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public Vector3fc origin() {
        return origin;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirtyFlag() {
        dirty = false;
    }

    public FloatBuffer uploadBuffer() {
        return uploadBuffer;
    }

    public void rebuild(ActiveRegion region, Vector3f sunDir, List<DynamicLight> lights) {
        if (region == null) {
            return;
        }
        ensureCapacity(region);
        origin.set(region.originX, region.originY, region.originZ);
        Arrays.fill(emission, 0f);
        Arrays.fill(radiance, 0f);
        Arrays.fill(occupancy, 0f);

        float sunScalar = 1.25f;
        float skyAmbient = 0.2f;

        for (int cz = 0; cz < sizeZ; cz++) {
            for (int cy = 0; cy < sizeY; cy++) {
                for (int cx = 0; cx < sizeX; cx++) {
                    int cellIndex = index(cx, cy, cz);
                    int base = cellIndex * 3;
                    float occlusionSum = 0f;
                    int sampleCount = 0;
                    float r = 0f, g = 0f, b = 0f;

                    for (int lz = 0; lz < cellSize; lz++) {
                        int vz = cz * cellSize + lz;
                        if (vz >= region.rz) {
                            break;
                        }
                        for (int ly = 0; ly < cellSize; ly++) {
                            int vy = cy * cellSize + ly;
                            if (vy >= region.ry) {
                                break;
                            }
                            for (int lx = 0; lx < cellSize; lx++) {
                                int vx = cx * cellSize + lx;
                                if (vx >= region.rx) {
                                    break;
                                }
                                sampleCount++;
                                int blockId = region.getLocal(vx, vy, vz);
                                if (blockId == Blocks.AIR) {
                                    continue;
                                }
                                float opacity = Blocks.giOpacity(blockId);
                                occlusionSum += opacity;
                                float[] color = blockColor(blockId);
                                for (int[] normal : FACE_NORMALS) {
                                    int nx = vx + normal[0];
                                    int ny = vy + normal[1];
                                    int nz = vz + normal[2];
                                    if (region.getLocal(nx, ny, nz) != Blocks.AIR) {
                                        continue;
                                    }
                                    float ndl = Math.max(0f,
                                            normal[0] * sunDir.x + normal[1] * sunDir.y + normal[2] * sunDir.z);
                                    if (ndl > 0f) {
                                        float scale = ndl * sunScalar;
                                        r += color[0] * scale;
                                        g += color[1] * scale;
                                        b += color[2] * scale;
                                    }
                                    if (normal[1] > 0) {
                                        float scale = normal[1] * skyAmbient;
                                        r += color[0] * scale;
                                        g += color[1] * scale;
                                        b += color[2] * scale;
                                    }
                                }
                            }
                        }
                    }

                    float occupancyRatio = sampleCount == 0
                            ? 0f
                            : occlusionSum / (float) sampleCount;
                    occupancy[cellIndex] = Math.min(1f, Math.max(0f, occupancyRatio));
                    float openness = 1f - occupancy[cellIndex];
                    if (openness <= 0f) {
                        continue;
                    }

                    float centerX = origin.x + (cx + 0.5f) * cellSize;
                    float centerY = origin.y + (cy + 0.5f) * cellSize;
                    float centerZ = origin.z + (cz + 0.5f) * cellSize;

                    if (lights != null && !lights.isEmpty()) {
                        for (DynamicLight light : lights) {
                            if (light == null || !light.isEnabled() || light.intensity() <= 0f) {
                                continue;
                            }
                            float dx = light.position().x - centerX;
                            float dy = light.position().y - centerY;
                            float dz = light.position().z - centerZ;
                            float distSq = dx * dx + dy * dy + dz * dz;
                            float range = light.range() > 0f ? light.range() : 12f;
                            if (distSq > range * range) {
                                continue;
                            }
                            float dist = (float) Math.sqrt(distSq);
                            float falloff = 1f - Math.min(1f, dist / Math.max(1e-3f, range));
                            float intensity = light.intensity() * falloff * falloff * openness;
                            r += light.color().x * intensity;
                            g += light.color().y * intensity;
                            b += light.color().z * intensity;
                        }
                    }

                    float ambientFloor = 0.015f * openness;
                    emission[base] = Math.max(0f, r * openness + ambientFloor);
                    emission[base + 1] = Math.max(0f, g * openness + ambientFloor);
                    emission[base + 2] = Math.max(0f, b * openness + ambientFloor);
                }
            }
        }

        propagateLight();
        uploadBuffer.clear();
        uploadBuffer.put(radiance);
        uploadBuffer.flip();
        dirty = true;
    }

    private void propagateLight() {
        System.arraycopy(emission, 0, radiance, 0, emission.length);
        int iterations = 4;
        float decay = 0.92f;
        float transfer = 0.18f;

        for (int iter = 0; iter < iterations; iter++) {
            Arrays.fill(ping, 0f);
            for (int z = 0; z < sizeZ; z++) {
                for (int y = 0; y < sizeY; y++) {
                    for (int x = 0; x < sizeX; x++) {
                        int idx = index(x, y, z);
                        float openness = 1f - occupancy[idx];
                        if (openness <= 0f) {
                            continue;
                        }
                        int base = idx * 3;
                        float ar = emission[base];
                        float ag = emission[base + 1];
                        float ab = emission[base + 2];
                        float rr = ar;
                        float rg = ag;
                        float rb = ab;

                        for (int[] off : NEIGHBOR_OFFSETS) {
                            int nx = x + off[0];
                            int ny = y + off[1];
                            int nz = z + off[2];
                            if (nx < 0 || ny < 0 || nz < 0 || nx >= sizeX || ny >= sizeY || nz >= sizeZ) {
                                continue;
                            }
                            int nIdx = index(nx, ny, nz);
                            float neighborOpenness = 1f - occupancy[nIdx];
                            if (neighborOpenness <= 0f) {
                                continue;
                            }
                            int nBase = nIdx * 3;
                            float weight = transfer * openness * neighborOpenness;
                            rr += radiance[nBase] * weight;
                            rg += radiance[nBase + 1] * weight;
                            rb += radiance[nBase + 2] * weight;
                        }

                        ping[base] = Math.max(0f, rr * decay);
                        ping[base + 1] = Math.max(0f, rg * decay);
                        ping[base + 2] = Math.max(0f, rb * decay);
                    }
                }
            }
            float[] tmp = radiance;
            radiance = ping;
            ping = tmp;
        }

        if (radiance != ping) {
            System.arraycopy(radiance, 0, ping, 0, radiance.length);
        }
    }

    private void ensureCapacity(ActiveRegion region) {
        int desiredX = (region.rx + cellSize - 1) / cellSize;
        int desiredY = (region.ry + cellSize - 1) / cellSize;
        int desiredZ = (region.rz + cellSize - 1) / cellSize;
        int cellCount = desiredX * desiredY * desiredZ;
        if (cellCount <= 0) {
            return;
        }
        if (desiredX == sizeX && desiredY == sizeY && desiredZ == sizeZ && emission != null) {
            return;
        }
        sizeX = desiredX;
        sizeY = desiredY;
        sizeZ = desiredZ;
        emission = new float[cellCount * 3];
        radiance = new float[cellCount * 3];
        ping = new float[cellCount * 3];
        occupancy = new float[cellCount];
        uploadBuffer = BufferUtils.createFloatBuffer(cellCount * 3);
    }

    private int index(int x, int y, int z) {
        return x + sizeX * (y + sizeY * z);
    }

    private float[] blockColor(int blockId) {
        if (blockId >= 0 && blockId < BLOCK_COLORS.length) {
            return BLOCK_COLORS[blockId];
        }
        return BLOCK_COLORS[0];
    }
}
