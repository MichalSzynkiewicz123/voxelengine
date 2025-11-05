package com.example.voxelrt.mesh;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Collects chunk meshes into a single instanced draw call.
 */
public final class ChunkBatcher implements AutoCloseable {
    private static final int FLOATS_PER_INSTANCE = 9;
    private static final int BASE_VERTEX_COUNT = 6;

    private final int vao;
    private final int instanceVbo;
    private int capacityFloats = 0;
    private FloatBuffer stagingBuffer = BufferUtils.createFloatBuffer(0);

    public ChunkBatcher() {
        ChunkMesh.ensureSharedGeometry();
        int shared = ChunkMesh.sharedVertexBuffer();
        vao = glGenVertexArrays();
        instanceVbo = glGenBuffers();

        glBindVertexArray(vao);

        glBindBuffer(GL_ARRAY_BUFFER, shared);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
        glVertexAttribDivisor(0, 0);

        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        glBufferData(GL_ARRAY_BUFFER, 0L, GL_STREAM_DRAW);
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
    }

    public void drawBatched(List<ChunkMesh> meshes) {
        if (meshes == null || meshes.isEmpty()) {
            return;
        }
        int totalInstances = 0;
        for (ChunkMesh mesh : meshes) {
            if (mesh != null) {
                totalInstances += mesh.instanceCount();
            }
        }
        if (totalInstances == 0) {
            return;
        }

        int floatsNeeded = totalInstances * FLOATS_PER_INSTANCE;
        ensureCapacity(floatsNeeded);

        stagingBuffer.clear();
        for (ChunkMesh mesh : meshes) {
            if (mesh == null || mesh.instanceCount() == 0) {
                continue;
            }
            float[] data = mesh.instanceData();
            stagingBuffer.put(data, 0, mesh.instanceCount() * FLOATS_PER_INSTANCE);
        }
        stagingBuffer.flip();

        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, stagingBuffer);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        glBindVertexArray(vao);
        glDrawArraysInstanced(GL_TRIANGLES, 0, BASE_VERTEX_COUNT, totalInstances);
        glBindVertexArray(0);
    }

    private void ensureCapacity(int floatsNeeded) {
        if (floatsNeeded <= capacityFloats) {
            return;
        }
        capacityFloats = Math.max(floatsNeeded, capacityFloats * 2);
        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) capacityFloats * Float.BYTES, GL_STREAM_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        stagingBuffer = BufferUtils.createFloatBuffer(capacityFloats);
    }

    @Override
    public void close() {
        glDeleteBuffers(instanceVbo);
        glDeleteVertexArrays(vao);
    }
}
