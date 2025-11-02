package com.example.voxelrt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Represents the dense block data around the player that is uploaded to the GPU.
 * <p>
 * The region maintains a CPU-side copy of the voxel data so that edits can be immediately reflected
 * in the compute shader storage buffer without having to rebuild the entire chunk set.
 */
public class ActiveRegion {
    public static class LodLevel {
        final int scale;
        final int sizeX, sizeY, sizeZ;
        final int[] data;
        int originX, originY, originZ;
        int bufferOffset;

        LodLevel(int scale, int sizeX, int sizeY, int sizeZ) {
            this.scale = scale;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.data = new int[sizeX * sizeY * sizeZ];
        }

        int idx(int x, int y, int z) {
            return x + y * sizeX + z * sizeX * sizeY;
        }

        public int scale() { return scale; }
        public int sizeX() { return sizeX; }
        public int sizeY() { return sizeY; }
        public int sizeZ() { return sizeZ; }
        public int originX() { return originX; }
        public int originY() { return originY; }
        public int originZ() { return originZ; }
        public int bufferOffset() { return bufferOffset; }
    }

    public final int rx, ry, rz;
    public int originX, originY, originZ;
    private final LodLevel[] levels;
    private int ssbo = 0;
    private final ChunkManager cm;

    public ActiveRegion(ChunkManager cm, int rx, int ry, int rz) {
        this.cm = cm;
        this.levels = new LodLevel[]{
                new LodLevel(1, rx, ry, rz),
                new LodLevel(4, Math.max(1, rx / 2), Math.max(1, ry / 2), Math.max(1, rz / 2))
        };
        this.rx = levels[0].sizeX;
        this.ry = levels[0].sizeY;
        this.rz = levels[0].sizeZ;
    }

    public int lodCount() {
        return levels.length;
    }

    public LodLevel getLevel(int index) {
        return levels[index];
    }

    private int spanX(LodLevel level) { return level.sizeX * level.scale; }
    private int spanY(LodLevel level) { return level.sizeY * level.scale; }
    private int spanZ(LodLevel level) { return level.sizeZ * level.scale; }

    /**
     * Rebuilds the entire region centered around a world coordinate.
     * <p>
     * The method clamps the vertical origin so that it never leaves the chunk column height while
     * allowing the horizontal origin to follow the player freely. After filling the CPU buffer the
     * entire data set is uploaded to the GPU via {@link #uploadAll()}.
     */
    public void rebuildAround(int cx, int cy, int cz) {
        rebuildAround(cx, cy, cz, null);
    }

    /**
     * Rebuilds the region and optionally applies frustum culling to skip chunks that are outside
     * of the camera's view volume.
     */
    public void rebuildAround(int cx, int cy, int cz, Frustum frustum) {
        rebuildInternal(cx, cy, cz, frustum);
    }

    private void rebuildInternal(int cx, int cy, int cz, Frustum frustum) {
        LodLevel base = levels[0];
        int baseSpanX = spanX(base);
        int baseSpanY = spanY(base);
        int baseSpanZ = spanZ(base);
        originX = cx - baseSpanX / 2;
        originY = java.lang.Math.max(0, java.lang.Math.min(Chunk.SY - baseSpanY, cy - baseSpanY / 2));
        originZ = cz - baseSpanZ / 2;
        base.originX = originX;
        base.originY = originY;
        base.originZ = originZ;
        rebuildFineLevel(base, frustum);

        for (int i = 1; i < levels.length; i++) {
            LodLevel level = levels[i];
            int spanX = spanX(level);
            int spanY = spanY(level);
            int spanZ = spanZ(level);
            int ox = cx - spanX / 2;
            int oy = cy - spanY / 2;
            int oz = cz - spanZ / 2;
            if (level.scale > 1) {
                ox = java.lang.Math.floorDiv(ox, level.scale) * level.scale;
                oz = java.lang.Math.floorDiv(oz, level.scale) * level.scale;
            }
            int maxYOrigin = java.lang.Math.max(0, Chunk.SY - spanY);
            oy = java.lang.Math.max(0, java.lang.Math.min(maxYOrigin, oy));
            if (level.scale > 1) {
                oy = java.lang.Math.floorDiv(oy, level.scale) * level.scale;
            }
            level.originX = ox;
            level.originY = oy;
            level.originZ = oz;
            rebuildCoarseLevel(level);
        }

        uploadAll();
    }

    private void rebuildFineLevel(LodLevel level, Frustum frustum) {
        int chunkCountX = (level.sizeX + Chunk.SX - 1) / Chunk.SX;
        int chunkCountZ = (level.sizeZ + Chunk.SZ - 1) / Chunk.SZ;

        boolean[][] chunkVisible = null;
        if (frustum != null) {
            chunkVisible = new boolean[chunkCountX][chunkCountZ];
            float minY = level.originY;
            float maxY = level.originY + level.sizeY;
            float pad = 0.5f;
            for (int czLocal = 0; czLocal < chunkCountZ; czLocal++) {
                int z0 = czLocal * Chunk.SZ;
                int z1 = java.lang.Math.min(z0 + Chunk.SZ, level.sizeZ);
                float minZ = level.originZ + z0 * level.scale;
                float maxZ = level.originZ + z1 * level.scale;
                for (int cxLocal = 0; cxLocal < chunkCountX; cxLocal++) {
                    int x0 = cxLocal * Chunk.SX;
                    int x1 = java.lang.Math.min(x0 + Chunk.SX, level.sizeX);
                    float minX = level.originX + x0 * level.scale;
                    float maxX = level.originX + x1 * level.scale;
                    chunkVisible[cxLocal][czLocal] = frustum.intersectsAABB(
                            minX - pad, minY - pad, minZ - pad,
                            maxX + pad, maxY + pad, maxZ + pad);
                }
            }
        }

        for (int czLocal = 0; czLocal < chunkCountZ; czLocal++) {
            int z0 = czLocal * Chunk.SZ;
            int z1 = java.lang.Math.min(z0 + Chunk.SZ, level.sizeZ);
            for (int cxLocal = 0; cxLocal < chunkCountX; cxLocal++) {
                int x0 = cxLocal * Chunk.SX;
                int x1 = java.lang.Math.min(x0 + Chunk.SX, level.sizeX);
                boolean visible = chunkVisible == null || chunkVisible[cxLocal][czLocal];
                if (!visible) {
                    int width = x1 - x0;
                    for (int z = z0; z < z1; z++) {
                        int zOffset = z * level.sizeX * level.sizeY;
                        for (int y = 0; y < level.sizeY; y++) {
                            int start = x0 + y * level.sizeX + zOffset;
                            Arrays.fill(level.data, start, start + width, Blocks.AIR);
                        }
                    }
                    continue;
                }
                for (int z = z0; z < z1; z++) {
                    int wz = level.originZ + z * level.scale;
                    for (int x = x0; x < x1; x++) {
                        int wx = level.originX + x * level.scale;
                        for (int y = 0; y < level.sizeY; y++) {
                            int wy = level.originY + y * level.scale;
                            int b = cm.sample(wx, wy, wz);
                            level.data[level.idx(x, y, z)] = b;
                        }
                    }
                }
            }
        }
    }

    private void rebuildCoarseLevel(LodLevel level) {
        int scale = level.scale;
        if (scale <= 1) {
            rebuildFineLevel(level, null);
            return;
        }
        for (int z = 0; z < level.sizeZ; z++) {
            int wz0 = level.originZ + z * scale;
            for (int x = 0; x < level.sizeX; x++) {
                int wx0 = level.originX + x * scale;
                for (int y = 0; y < level.sizeY; y++) {
                    int wy0 = level.originY + y * scale;
                    int block = sampleAggregated(wx0, wy0, wz0, scale);
                    level.data[level.idx(x, y, z)] = block;
                }
            }
        }
    }

    private int sampleAggregated(int wx0, int wy0, int wz0, int scale) {
        int x1 = wx0 + scale;
        int z1 = wz0 + scale;
        int yStart = java.lang.Math.max(0, wy0);
        int yEnd = java.lang.Math.min(Chunk.SY, wy0 + scale);
        if (yEnd <= yStart) {
            return Blocks.AIR;
        }
        for (int y = yEnd - 1; y >= yStart; y--) {
            for (int z = wz0; z < z1; z++) {
                for (int x = wx0; x < x1; x++) {
                    int b = cm.sample(x, y, z);
                    if (b != Blocks.AIR) {
                        return b;
                    }
                }
            }
        }
        return Blocks.AIR;
    }

    /**
     * Updates a single voxel in world space and mirrors the change into the GPU buffer.
     */
    public void setVoxelWorld(int wx, int wy, int wz, int b) {
        for (LodLevel level : levels) {
            int scale = level.scale;
            int lx = wx - level.originX;
            int ly = wy - level.originY;
            int lz = wz - level.originZ;
            if (lx < 0 || ly < 0 || lz < 0) continue;
            if (lx >= level.sizeX * scale || ly >= level.sizeY * scale || lz >= level.sizeZ * scale) continue;
            int ix = scale == 1 ? lx : lx / scale;
            int iy = scale == 1 ? ly : ly / scale;
            int iz = scale == 1 ? lz : lz / scale;
            if (ix < 0 || iy < 0 || iz < 0 || ix >= level.sizeX || iy >= level.sizeY || iz >= level.sizeZ) continue;
            int idx = level.idx(ix, iy, iz);
            int value = (scale == 1) ? b : sampleAggregated(level.originX + ix * scale,
                                                            level.originY + iy * scale,
                                                            level.originZ + iz * scale,
                                                            scale);
            level.data[idx] = value;
            updateGpu(level, idx, value);
        }
    }

    public int ssbo() {
        return ssbo;
    }

    private void uploadAll() {
        if (ssbo == 0) ssbo = glGenBuffers();
        int total = 0;
        for (LodLevel level : levels) {
            level.bufferOffset = total;
            total += level.data.length;
        }
        IntBuffer ib = ByteBuffer.allocateDirect(total * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        for (LodLevel level : levels) {
            ib.put(level.data);
        }
        ib.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        glBufferData(GL_SHADER_STORAGE_BUFFER, ib, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssbo);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    private void updateGpu(LodLevel level, int idx, int value) {
        if (ssbo == 0) return;
        IntBuffer ib = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        ib.put(0, value);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        long offset = (long) (level.bufferOffset + idx) * 4L;
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, offset, ib);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }
}
