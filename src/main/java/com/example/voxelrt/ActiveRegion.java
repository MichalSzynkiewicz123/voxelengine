package com.example.voxelrt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

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
    private int ssbo = 0;
    private final ChunkManager cm;

    public ActiveRegion(ChunkManager cm, int rx, int ry, int rz) {
        this.cm = cm;
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        this.buf = new int[rx * ry * rz];
    }

    private int ridx(int x, int y, int z) {
        return x + y * rx + z * rx * ry;
    }

    /**
     * Rebuilds the entire region centered around a world coordinate.
     * <p>
     * The method clamps the vertical origin so that it never leaves the chunk column height while
     * allowing the horizontal origin to follow the player freely. After filling the CPU buffer the
     * entire data set is uploaded to the GPU via {@link #uploadAll()}.
     */
    public void rebuildAround(int cx, int cy, int cz) {
        originX = cx - rx / 2;
        originY = java.lang.Math.max(0, java.lang.Math.min(Chunk.SY - ry, cy - ry / 2));
        originZ = cz - rz / 2;
        for (int z = 0; z < rz; z++) {
            for (int y = 0; y < ry; y++) {
                for (int x = 0; x < rx; x++) {
                    int wx = originX + x;
                    int wy = originY + y;
                    int wz = originZ + z;
                    int b = (wy < 0 || wy >= Chunk.SY) ? Blocks.AIR : cm.sample(wx, wy, wz);
                    buf[ridx(x, y, z)] = b;
                }
            }
        }
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
    }

    public int ssbo() {
        return ssbo;
    }

    private void uploadAll() {
        if (ssbo == 0) ssbo = glGenBuffers();
        IntBuffer ib = ByteBuffer.allocateDirect(buf.length * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        ib.put(buf).flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        glBufferData(GL_SHADER_STORAGE_BUFFER, ib, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssbo);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }
}
