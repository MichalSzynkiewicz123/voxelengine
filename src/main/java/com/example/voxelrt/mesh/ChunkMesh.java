package com.example.voxelrt.mesh;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL46C.*;

public final class ChunkMesh {
    private static final int FLOATS_PER_INSTANCE = 9;
    private static final int BASE_VERTEX_COUNT = 6;
    private static final float[] BASE_CORNERS = {
            0f, 0f,
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 1f,
            1f, 0f
    };

    private static int sharedVertexBuffer = 0;

    private final int vao;
    private final int instanceVbo;
    private final int instanceCount;
    private final float[] instanceData;

    private ChunkMesh(int vao, int instanceVbo, int instanceCount, float[] instanceData) {
        this.vao = vao;
        this.instanceVbo = instanceVbo;
        this.instanceCount = instanceCount;
        this.instanceData = instanceData;
    }

    public static ChunkMesh create(float[] instances, int instanceCount) {
        if (instanceCount <= 0 || instances.length == 0) {
            return new ChunkMesh(0, 0, 0, new float[0]);
        }
        ensureSharedGeometry();
        int vao = glGenVertexArrays();
        int instanceVbo = glGenBuffers();

        glBindVertexArray(vao);

        glBindBuffer(GL_ARRAY_BUFFER, sharedVertexBuffer);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
        glVertexAttribDivisor(0, 0);

        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        FloatBuffer buffer = BufferUtils.createFloatBuffer(instances.length);
        buffer.put(instances).flip();
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW);
        int stride = FLOATS_PER_INSTANCE * Float.BYTES;

        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 0L);
        glVertexAttribDivisor(1, 1);

        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glVertexAttribDivisor(2, 1);

        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3, 3, GL_FLOAT, false, stride, 6L * Float.BYTES);
        glVertexAttribDivisor(3, 1);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        return new ChunkMesh(vao, instanceVbo, instanceCount, instances);
    }

    static void ensureSharedGeometry() {
        if (sharedVertexBuffer != 0) {
            return;
        }
        sharedVertexBuffer = glGenBuffers();
        FloatBuffer buffer = BufferUtils.createFloatBuffer(BASE_CORNERS.length);
        buffer.put(BASE_CORNERS).flip();
        glBindBuffer(GL_ARRAY_BUFFER, sharedVertexBuffer);
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    static int sharedVertexBuffer() {
        ensureSharedGeometry();
        return sharedVertexBuffer;
    }

    public void draw() {
        if (instanceCount == 0) {
            return;
        }
        glBindVertexArray(vao);
        glDrawArraysInstanced(GL_TRIANGLES, 0, BASE_VERTEX_COUNT, instanceCount);
        glBindVertexArray(0);
    }

    public void destroy() {
        if (instanceVbo != 0) {
            glDeleteBuffers(instanceVbo);
        }
        if (vao != 0) {
            glDeleteVertexArrays(vao);
        }
    }

    public int instanceCount() {
        return instanceCount;
    }

    public float[] instanceData() {
        return instanceData;
    }
}
