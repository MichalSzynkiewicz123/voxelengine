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
    public final int rx, ry, rz;
    public int originX, originY, originZ;
    private final int[] buf;
    private final int[] bufCoarse;
    private int ssbo = 0;
    private int ssboCoarse = 0;
    private final ChunkManager cm;
    private final int lodScale = 2;
    private final int rxCoarse;
    private final int ryCoarse;
    private final int rzCoarse;

    public ActiveRegion(ChunkManager cm, int rx, int ry, int rz) {
        this.cm = cm;
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        this.buf = new int[rx * ry * rz];
        this.rxCoarse = (rx + lodScale - 1) / lodScale;
        this.ryCoarse = (ry + lodScale - 1) / lodScale;
        this.rzCoarse = (rz + lodScale - 1) / lodScale;
        this.bufCoarse = new int[rxCoarse * ryCoarse * rzCoarse];
    }

    private int ridx(int x, int y, int z) {
        return x + y * rx + z * rx * ry;
    }

    private int ridxCoarse(int x, int y, int z) {
        return x + y * rxCoarse + z * rxCoarse * ryCoarse;
    }

    private int rebuildCoarseCell(int cx, int cy, int cz) {
        int x0 = cx * lodScale;
        int y0 = cy * lodScale;
        int z0 = cz * lodScale;
        int countGrass = 0;
        int countDirt = 0;
        int countStone = 0;
        int countSand = 0;
        int countSnow = 0;
        for (int dz = 0; dz < lodScale && z0 + dz < rz; dz++) {
            for (int dy = 0; dy < lodScale && y0 + dy < ry; dy++) {
                for (int dx = 0; dx < lodScale && x0 + dx < rx; dx++) {
                    int id = buf[ridx(x0 + dx, y0 + dy, z0 + dz)];
                    switch (id) {
                        case Blocks.GRASS -> countGrass++;
                        case Blocks.DIRT -> countDirt++;
                        case Blocks.STONE -> countStone++;
                        case Blocks.SAND -> countSand++;
                        case Blocks.SNOW -> countSnow++;
                        default -> {
                        }
                    }
                }
            }
        }
        int bestId = Blocks.AIR;
        int bestCount = 0;
        if (countGrass > bestCount) {
            bestCount = countGrass;
            bestId = Blocks.GRASS;
        }
        if (countDirt > bestCount) {
            bestCount = countDirt;
            bestId = Blocks.DIRT;
        }
        if (countStone > bestCount) {
            bestCount = countStone;
            bestId = Blocks.STONE;
        }
        if (countSand > bestCount) {
            bestCount = countSand;
            bestId = Blocks.SAND;
        }
        if (countSnow > bestCount) {
            bestCount = countSnow;
            bestId = Blocks.SNOW;
        }
        return bestCount == 0 ? Blocks.AIR : bestId;
    }

    private void rebuildCoarseBuffer() {
        for (int cz = 0; cz < rzCoarse; cz++) {
            for (int cy = 0; cy < ryCoarse; cy++) {
                for (int cx = 0; cx < rxCoarse; cx++) {
                    bufCoarse[ridxCoarse(cx, cy, cz)] = rebuildCoarseCell(cx, cy, cz);
                }
            }
        }
    }

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
        originX = cx - rx / 2;
        originY = java.lang.Math.max(0, java.lang.Math.min(Chunk.SY - ry, cy - ry / 2));
        originZ = cz - rz / 2;
        int chunkCountX = (rx + Chunk.SX - 1) / Chunk.SX;
        int chunkCountZ = (rz + Chunk.SZ - 1) / Chunk.SZ;

        boolean[][] chunkVisible = null;
        if (frustum != null) {
            chunkVisible = new boolean[chunkCountX][chunkCountZ];
            float minY = originY;
            float maxY = originY + ry;
            float pad = 0.5f;
            for (int czLocal = 0; czLocal < chunkCountZ; czLocal++) {
                int z0 = czLocal * Chunk.SZ;
                int z1 = java.lang.Math.min(z0 + Chunk.SZ, rz);
                float minZ = originZ + z0;
                float maxZ = originZ + z1;
                for (int cxLocal = 0; cxLocal < chunkCountX; cxLocal++) {
                    int x0 = cxLocal * Chunk.SX;
                    int x1 = java.lang.Math.min(x0 + Chunk.SX, rx);
                    float minX = originX + x0;
                    float maxX = originX + x1;
                    chunkVisible[cxLocal][czLocal] = frustum.intersectsAABB(
                            minX - pad, minY - pad, minZ - pad,
                            maxX + pad, maxY + pad, maxZ + pad);
                }
            }
        }

        for (int czLocal = 0; czLocal < chunkCountZ; czLocal++) {
            int z0 = czLocal * Chunk.SZ;
            int z1 = java.lang.Math.min(z0 + Chunk.SZ, rz);
            for (int cxLocal = 0; cxLocal < chunkCountX; cxLocal++) {
                int x0 = cxLocal * Chunk.SX;
                int x1 = java.lang.Math.min(x0 + Chunk.SX, rx);
                boolean visible = chunkVisible == null || chunkVisible[cxLocal][czLocal];
                if (!visible) {
                    int width = x1 - x0;
                    for (int z = z0; z < z1; z++) {
                        int zOffset = z * rx * ry;
                        for (int y = 0; y < ry; y++) {
                            int start = x0 + y * rx + zOffset;
                            Arrays.fill(buf, start, start + width, Blocks.AIR);
                        }
                    }
                    continue;
                }
                for (int z = z0; z < z1; z++) {
                    int wz = originZ + z;
                    for (int x = x0; x < x1; x++) {
                        int wx = originX + x;
                        for (int y = 0; y < ry; y++) {
                            int wy = originY + y;
                            int b = cm.sample(wx, wy, wz);
                            buf[ridx(x, y, z)] = b;
                        }
                    }
                }
            }
        }
        rebuildCoarseBuffer();
        uploadAll();
    }

    /**
     * Updates a single voxel in world space and mirrors the change into the GPU buffer.
     */
    public void setVoxelWorld(int wx, int wy, int wz, int b) {
        if (wy < originY || wy >= originY + ry) return;
        int x = wx - originX;
        int y = wy - originY;
        int z = wz - originZ;
        if (x < 0 || y < 0 || z < 0 || x >= rx || y >= ry || z >= rz) return;
        buf[ridx(x, y, z)] = b;
        if (ssbo != 0) {
            IntBuffer ib = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
            ib.put(0, b);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, (long) ridx(x, y, z) * 4L, ib);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        }

        int cx = x / lodScale;
        int cy = y / lodScale;
        int cz = z / lodScale;
        if (cx >= 0 && cy >= 0 && cz >= 0 && cx < rxCoarse && cy < ryCoarse && cz < rzCoarse) {
            int coarseId = rebuildCoarseCell(cx, cy, cz);
            bufCoarse[ridxCoarse(cx, cy, cz)] = coarseId;
            if (ssboCoarse != 0) {
                IntBuffer cb = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
                cb.put(0, coarseId);
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboCoarse);
                glBufferSubData(GL_SHADER_STORAGE_BUFFER, (long) ridxCoarse(cx, cy, cz) * 4L, cb);
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
            }
        }
    }

    public int ssbo() {
        return ssbo;
    }

    public int ssboCoarse() {
        return ssboCoarse;
    }

    public int rxCoarse() {
        return rxCoarse;
    }

    public int ryCoarse() {
        return ryCoarse;
    }

    public int rzCoarse() {
        return rzCoarse;
    }

    public int lodScale() {
        return lodScale;
    }

    private void uploadAll() {
        if (ssbo == 0) ssbo = glGenBuffers();
        IntBuffer ib = ByteBuffer.allocateDirect(buf.length * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        ib.put(buf).flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        glBufferData(GL_SHADER_STORAGE_BUFFER, ib, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssbo);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        if (ssboCoarse == 0) ssboCoarse = glGenBuffers();
        IntBuffer cb = ByteBuffer.allocateDirect(bufCoarse.length * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        cb.put(bufCoarse).flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboCoarse);
        glBufferData(GL_SHADER_STORAGE_BUFFER, cb, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssboCoarse);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }
}
