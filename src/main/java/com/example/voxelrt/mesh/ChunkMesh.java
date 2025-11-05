package com.example.voxelrt.mesh;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL46C.*;

public final class ChunkMesh {
    private final int vao;
    private final int vbo;
    private final int vertexCount;

    private ChunkMesh(int vao, int vbo, int vertexCount) {
        this.vao = vao;
        this.vbo = vbo;
        this.vertexCount = vertexCount;
    }

    public static ChunkMesh create(float[] vertices, int vertexCount) {
        if (vertexCount <= 0 || vertices.length == 0) {
            return new ChunkMesh(0, 0, 0);
        }
        int vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW);
        int stride = 7 * Float.BYTES;
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 6L * Float.BYTES);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        return new ChunkMesh(vao, vbo, vertexCount);
    }

    public void draw() {
        if (vertexCount == 0) {
            return;
        }
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);
    }

    public void destroy() {
        if (vbo != 0) {
            glDeleteBuffers(vbo);
        }
        if (vao != 0) {
            glDeleteVertexArrays(vao);
        }
    }

    public int vertexCount() {
        return vertexCount;
    }
}
