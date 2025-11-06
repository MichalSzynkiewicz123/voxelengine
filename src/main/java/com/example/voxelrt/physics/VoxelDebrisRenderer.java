package com.example.voxelrt.physics;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL46C.*;

public final class VoxelDebrisRenderer implements AutoCloseable {
    private static final int FLOATS_PER_VERTEX = 6;
    private static final int VERTEX_COUNT = 36;
    private static final float[] CUBE_VERTICES = {
            // front
            -0.5f, -0.5f,  0.5f, 0f, 0f, 1f,
             0.5f, -0.5f,  0.5f, 0f, 0f, 1f,
             0.5f,  0.5f,  0.5f, 0f, 0f, 1f,
            -0.5f, -0.5f,  0.5f, 0f, 0f, 1f,
             0.5f,  0.5f,  0.5f, 0f, 0f, 1f,
            -0.5f,  0.5f,  0.5f, 0f, 0f, 1f,
            // back
            -0.5f, -0.5f, -0.5f, 0f, 0f, -1f,
            -0.5f,  0.5f, -0.5f, 0f, 0f, -1f,
             0.5f,  0.5f, -0.5f, 0f, 0f, -1f,
            -0.5f, -0.5f, -0.5f, 0f, 0f, -1f,
             0.5f,  0.5f, -0.5f, 0f, 0f, -1f,
             0.5f, -0.5f, -0.5f, 0f, 0f, -1f,
            // left
            -0.5f, -0.5f, -0.5f, -1f, 0f, 0f,
            -0.5f, -0.5f,  0.5f, -1f, 0f, 0f,
            -0.5f,  0.5f,  0.5f, -1f, 0f, 0f,
            -0.5f, -0.5f, -0.5f, -1f, 0f, 0f,
            -0.5f,  0.5f,  0.5f, -1f, 0f, 0f,
            -0.5f,  0.5f, -0.5f, -1f, 0f, 0f,
            // right
             0.5f, -0.5f, -0.5f, 1f, 0f, 0f,
             0.5f,  0.5f,  0.5f, 1f, 0f, 0f,
             0.5f, -0.5f,  0.5f, 1f, 0f, 0f,
             0.5f, -0.5f, -0.5f, 1f, 0f, 0f,
             0.5f,  0.5f, -0.5f, 1f, 0f, 0f,
             0.5f,  0.5f,  0.5f, 1f, 0f, 0f,
            // top
            -0.5f,  0.5f, -0.5f, 0f, 1f, 0f,
            -0.5f,  0.5f,  0.5f, 0f, 1f, 0f,
             0.5f,  0.5f,  0.5f, 0f, 1f, 0f,
            -0.5f,  0.5f, -0.5f, 0f, 1f, 0f,
             0.5f,  0.5f,  0.5f, 0f, 1f, 0f,
             0.5f,  0.5f, -0.5f, 0f, 1f, 0f,
            // bottom
            -0.5f, -0.5f, -0.5f, 0f, -1f, 0f,
             0.5f, -0.5f,  0.5f, 0f, -1f, 0f,
            -0.5f, -0.5f,  0.5f, 0f, -1f, 0f,
            -0.5f, -0.5f, -0.5f, 0f, -1f, 0f,
             0.5f, -0.5f, -0.5f, 0f, -1f, 0f,
             0.5f, -0.5f,  0.5f, 0f, -1f, 0f
    };

    private static final int FLOATS_PER_INSTANCE = 18;

    private final int vao;
    private final int vertexVbo;
    private final int instanceVbo;
    private int instanceCapacity = 0;
    private FloatBuffer stagingBuffer = BufferUtils.createFloatBuffer(0);
    private final float[] matrixScratch = new float[16];

    public VoxelDebrisRenderer() {
        vao = glGenVertexArrays();
        vertexVbo = glGenBuffers();
        instanceVbo = glGenBuffers();

        glBindVertexArray(vao);

        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(CUBE_VERTICES.length);
        vertexBuffer.put(CUBE_VERTICES).flip();
        glBindBuffer(GL_ARRAY_BUFFER, vertexVbo);
        glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);

        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, FLOATS_PER_VERTEX * Float.BYTES, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, FLOATS_PER_VERTEX * Float.BYTES, 3L * Float.BYTES);

        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        glBufferData(GL_ARRAY_BUFFER, 0L, GL_STREAM_DRAW);
        int stride = FLOATS_PER_INSTANCE * Float.BYTES;

        for (int i = 0; i < 4; i++) {
            glEnableVertexAttribArray(2 + i);
            glVertexAttribPointer(2 + i, 4, GL_FLOAT, false, stride, (long) i * 4L * Float.BYTES);
            glVertexAttribDivisor(2 + i, 1);
        }

        glEnableVertexAttribArray(6);
        glVertexAttribPointer(6, 1, GL_FLOAT, false, stride, 16L * Float.BYTES);
        glVertexAttribDivisor(6, 1);

        glEnableVertexAttribArray(7);
        glVertexAttribPointer(7, 1, GL_FLOAT, false, stride, 17L * Float.BYTES);
        glVertexAttribDivisor(7, 1);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    public void drawInstances(List<PhysicsSystem.DebrisInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return;
        }
        int count = instances.size();
        ensureCapacity(count * FLOATS_PER_INSTANCE);
        stagingBuffer.clear();
        for (PhysicsSystem.DebrisInstance instance : instances) {
            instance.transform().get(matrixScratch);
            stagingBuffer.put(matrixScratch);
            stagingBuffer.put((float) instance.blockId());
            stagingBuffer.put(instance.alpha());
        }
        stagingBuffer.flip();

        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, stagingBuffer);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        glBindVertexArray(vao);
        glDrawArraysInstanced(GL_TRIANGLES, 0, VERTEX_COUNT, count);
        glBindVertexArray(0);
    }

    private void ensureCapacity(int floatsNeeded) {
        if (floatsNeeded <= instanceCapacity) {
            return;
        }
        instanceCapacity = Math.max(floatsNeeded, instanceCapacity * 2);
        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) instanceCapacity * Float.BYTES, GL_STREAM_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        stagingBuffer = BufferUtils.createFloatBuffer(instanceCapacity);
    }

    @Override
    public void close() {
        glDeleteBuffers(instanceVbo);
        glDeleteBuffers(vertexVbo);
        glDeleteVertexArrays(vao);
    }
}
