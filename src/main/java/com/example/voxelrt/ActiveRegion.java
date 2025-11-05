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
    private final int[] bufFar;
    private int ssbo = 0;
    private int ssboCoarse = 0;
    private int ssboFar = 0;
    private final ChunkManager cm;
    private final int lodScale = 2;
    private final int lodScaleFar;
    private final int rxCoarse;
    private final int ryCoarse;
    private final int rzCoarse;
    private final int rxFar;
    private final int ryFar;
    private final int rzFar;
    private final IntBuffer uploadIntBuffer;
    private final IntBuffer uploadIntBufferCoarse;
    private final IntBuffer uploadIntBufferFar;
    private final IntBuffer singleIntView;

    public ActiveRegion(ChunkManager cm, int rx, int ry, int rz) {
        this.cm = cm;
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        this.buf = new int[rx * ry * rz];
        this.rxCoarse = (rx + lodScale - 1) / lodScale;
        this.ryCoarse = (ry + lodScale - 1) / lodScale;
        this.rzCoarse = (rz + lodScale - 1) / lodScale;
        this.lodScaleFar = lodScale * 2;
        this.rxFar = (rx + lodScaleFar - 1) / lodScaleFar;
        this.ryFar = (ry + lodScaleFar - 1) / lodScaleFar;
        this.rzFar = (rz + lodScaleFar - 1) / lodScaleFar;
        this.bufCoarse = new int[rxCoarse * ryCoarse * rzCoarse];
        this.bufFar = new int[rxFar * ryFar * rzFar];
        this.uploadIntBuffer = ByteBuffer.allocateDirect(this.buf.length * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
        this.uploadIntBufferCoarse = ByteBuffer.allocateDirect(this.bufCoarse.length * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
        this.uploadIntBufferFar = ByteBuffer.allocateDirect(this.bufFar.length * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
        this.singleIntView = ByteBuffer.allocateDirect(Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    private int ridx(int x, int y, int z) {
        return x + y * rx + z * rx * ry;
    }

    private int ridxCoarse(int x, int y, int z) {
        return x + y * rxCoarse + z * rxCoarse * ryCoarse;
    }

    private int ridxFar(int x, int y, int z) {
        return x + y * rxFar + z * rxFar * ryFar;
    }

    private int rebuildLodCell(int scale, int cx, int cy, int cz) {
        int x0 = cx * scale;
        int y0 = cy * scale;
        int z0 = cz * scale;
        int countGrass = 0;
        int countDirt = 0;
        int countStone = 0;
        int countSand = 0;
        int countSnow = 0;
        for (int dz = 0; dz < scale && z0 + dz < rz; dz++) {
            for (int dy = 0; dy < scale && y0 + dy < ry; dy++) {
                for (int dx = 0; dx < scale && x0 + dx < rx; dx++) {
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

    private int rebuildCoarseCell(int cx, int cy, int cz) {
        return rebuildLodCell(lodScale, cx, cy, cz);
    }

    private int rebuildFarCell(int cx, int cy, int cz) {
        return rebuildLodCell(lodScaleFar, cx, cy, cz);
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

    private void rebuildFarBuffer() {
        for (int cz = 0; cz < rzFar; cz++) {
            for (int cy = 0; cy < ryFar; cy++) {
                for (int cx = 0; cx < rxFar; cx++) {
                    bufFar[ridxFar(cx, cy, cz)] = rebuildFarCell(cx, cy, cz);
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
        cm.update();
        originX = cx - rx / 2;
        originY = java.lang.Math.max(0, java.lang.Math.min(Chunk.SY - ry, cy - ry / 2));
        originZ = cz - rz / 2;
        Arrays.fill(buf, Blocks.AIR);

        int minChunkX = java.lang.Math.floorDiv(originX, Chunk.SX);
        int maxChunkX = java.lang.Math.floorDiv(originX + rx - 1, Chunk.SX);
        int minChunkZ = java.lang.Math.floorDiv(originZ, Chunk.SZ);
        int maxChunkZ = java.lang.Math.floorDiv(originZ + rz - 1, Chunk.SZ);
        int yStart = originY;
        int yEnd = originY + ry;
        float pad = 0.5f;

        for (int czWorld = minChunkZ; czWorld <= maxChunkZ; czWorld++) {
            int chunkWorldZ0 = czWorld * Chunk.SZ;
            int zStart = java.lang.Math.max(originZ, chunkWorldZ0);
            int zEnd = java.lang.Math.min(originZ + rz, chunkWorldZ0 + Chunk.SZ);
            if (zStart >= zEnd) continue;
            for (int cxWorld = minChunkX; cxWorld <= maxChunkX; cxWorld++) {
                int chunkWorldX0 = cxWorld * Chunk.SX;
                int xStart = java.lang.Math.max(originX, chunkWorldX0);
                int xEnd = java.lang.Math.min(originX + rx, chunkWorldX0 + Chunk.SX);
                if (xStart >= xEnd) continue;

                ChunkPos pos = new ChunkPos(cxWorld, czWorld);
                cm.requestChunk(pos);

                boolean visible = true;
                if (frustum != null) {
                    float minX = xStart;
                    float maxX = xEnd;
                    float minZ = zStart;
                    float maxZ = zEnd;
                    float minY = originY;
                    float maxY = originY + ry;
                    visible = frustum.intersectsAABB(
                            minX - pad, minY - pad, minZ - pad,
                            maxX + pad, maxY + pad, maxZ + pad);
                }
                if (!visible) {
                    continue;
                }

                Chunk chunk = cm.getIfLoaded(pos);
                if (chunk == null) {
                    continue;
                }

                for (int wz = zStart; wz < zEnd; wz++) {
                    int regionZ = wz - originZ;
                    int chunkZLocal = wz - chunkWorldZ0;
                    for (int wx = xStart; wx < xEnd; wx++) {
                        int regionX = wx - originX;
                        int chunkXLocal = wx - chunkWorldX0;
                        for (int wy = yStart; wy < yEnd; wy++) {
                            int regionY = wy - originY;
                            buf[ridx(regionX, regionY, regionZ)] = chunk.get(chunkXLocal, wy, chunkZLocal);
                        }
                    }
                }
            }
        }
        rebuildCoarseBuffer();
        rebuildFarBuffer();
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
            singleIntView.clear();
            singleIntView.put(b);
            singleIntView.flip();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, (long) ridx(x, y, z) * 4L, singleIntView);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        }

        int cx = x / lodScale;
        int cy = y / lodScale;
        int cz = z / lodScale;
        if (cx >= 0 && cy >= 0 && cz >= 0 && cx < rxCoarse && cy < ryCoarse && cz < rzCoarse) {
            int coarseId = rebuildCoarseCell(cx, cy, cz);
            bufCoarse[ridxCoarse(cx, cy, cz)] = coarseId;
            if (ssboCoarse != 0) {
                singleIntView.clear();
                singleIntView.put(coarseId);
                singleIntView.flip();
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboCoarse);
                glBufferSubData(GL_SHADER_STORAGE_BUFFER, (long) ridxCoarse(cx, cy, cz) * 4L, singleIntView);
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
            }
        }

        int fx = x / lodScaleFar;
        int fy = y / lodScaleFar;
        int fz = z / lodScaleFar;
        if (fx >= 0 && fy >= 0 && fz >= 0 && fx < rxFar && fy < ryFar && fz < rzFar) {
            int farId = rebuildFarCell(fx, fy, fz);
            bufFar[ridxFar(fx, fy, fz)] = farId;
            if (ssboFar != 0) {
                singleIntView.clear();
                singleIntView.put(farId);
                singleIntView.flip();
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboFar);
                glBufferSubData(GL_SHADER_STORAGE_BUFFER, (long) ridxFar(fx, fy, fz) * 4L, singleIntView);
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

    public int ssboFar() {
        return ssboFar;
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

    public int rxFar() {
        return rxFar;
    }

    public int ryFar() {
        return ryFar;
    }

    public int rzFar() {
        return rzFar;
    }

    public int lodScale() {
        return lodScale;
    }

    public int lodScaleFar() {
        return lodScaleFar;
    }

    private void uploadAll() {
        if (ssbo == 0) ssbo = glGenBuffers();
        uploadIntBuffer.clear();
        uploadIntBuffer.put(buf);
        uploadIntBuffer.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        glBufferData(GL_SHADER_STORAGE_BUFFER, uploadIntBuffer, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssbo);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        if (ssboCoarse == 0) ssboCoarse = glGenBuffers();
        uploadIntBufferCoarse.clear();
        uploadIntBufferCoarse.put(bufCoarse);
        uploadIntBufferCoarse.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboCoarse);
        glBufferData(GL_SHADER_STORAGE_BUFFER, uploadIntBufferCoarse, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssboCoarse);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        if (ssboFar == 0) ssboFar = glGenBuffers();
        uploadIntBufferFar.clear();
        uploadIntBufferFar.put(bufFar);
        uploadIntBufferFar.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboFar);
        glBufferData(GL_SHADER_STORAGE_BUFFER, uploadIntBufferFar, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ssboFar);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }
}
