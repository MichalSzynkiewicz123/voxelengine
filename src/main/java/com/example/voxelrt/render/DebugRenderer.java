package com.example.voxelrt.render;

import org.joml.Matrix4f;
import org.lwjgl.stb.STBEasyFont;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Objects;
import java.util.function.Function;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Utility renderer used for debug overlays such as wireframe volumes and on-screen text.
 */
public final class DebugRenderer implements AutoCloseable {
    private static final int LINE_FLOATS_PER_VERTEX = 7;
    private static final int TEXT_FLOATS_PER_VERTEX = 6;

    private final int lineProgram;
    private final int textProgram;
    private final int lineVao;
    private final int lineVbo;
    private final int textVao;
    private final int textVbo;
    private final int lineProjLoc;
    private final int lineViewLoc;
    private final int textOrthoLoc;

    private float[] lineData = new float[1024];
    private int lineFloats = 0;
    private int lineVertices = 0;

    private float[] textData = new float[2048];
    private int textFloats = 0;
    private int textVertices = 0;

    private ByteBuffer stbBuffer = createStbBuffer(4096);

    public DebugRenderer(Function<String, String> resourceLoader) {
        Objects.requireNonNull(resourceLoader, "resourceLoader");
        lineProgram = linkProgram(
                compileShader(GL_VERTEX_SHADER, resourceLoader.apply("shaders/debug_line.vert")),
                compileShader(GL_FRAGMENT_SHADER, resourceLoader.apply("shaders/debug_line.frag"))
        );
        textProgram = linkProgram(
                compileShader(GL_VERTEX_SHADER, resourceLoader.apply("shaders/debug_text.vert")),
                compileShader(GL_FRAGMENT_SHADER, resourceLoader.apply("shaders/debug_text.frag"))
        );

        lineProjLoc = glGetUniformLocation(lineProgram, "uProj");
        lineViewLoc = glGetUniformLocation(lineProgram, "uView");
        textOrthoLoc = glGetUniformLocation(textProgram, "uOrtho");

        lineVao = glGenVertexArrays();
        lineVbo = glGenBuffers();
        glBindVertexArray(lineVao);
        glBindBuffer(GL_ARRAY_BUFFER, lineVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) LINE_FLOATS_PER_VERTEX * Float.BYTES * 64, GL_DYNAMIC_DRAW);
        int lineStride = LINE_FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, lineStride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, lineStride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);

        textVao = glGenVertexArrays();
        textVbo = glGenBuffers();
        glBindVertexArray(textVao);
        glBindBuffer(GL_ARRAY_BUFFER, textVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) TEXT_FLOATS_PER_VERTEX * Float.BYTES * 64, GL_DYNAMIC_DRAW);
        int textStride = TEXT_FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, textStride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, textStride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
    }

    public void beginFrame() {
        lineFloats = 0;
        lineVertices = 0;
        textFloats = 0;
        textVertices = 0;
    }

    public void addLine(float x0, float y0, float z0, float x1, float y1, float z1,
                         float r, float g, float b, float a) {
        ensureLineCapacity(LINE_FLOATS_PER_VERTEX * 2);
        lineData[lineFloats++] = x0;
        lineData[lineFloats++] = y0;
        lineData[lineFloats++] = z0;
        lineData[lineFloats++] = r;
        lineData[lineFloats++] = g;
        lineData[lineFloats++] = b;
        lineData[lineFloats++] = a;
        lineData[lineFloats++] = x1;
        lineData[lineFloats++] = y1;
        lineData[lineFloats++] = z1;
        lineData[lineFloats++] = r;
        lineData[lineFloats++] = g;
        lineData[lineFloats++] = b;
        lineData[lineFloats++] = a;
        lineVertices += 2;
    }

    public void addWireBox(float minX, float minY, float minZ,
                           float maxX, float maxY, float maxZ,
                           float r, float g, float b, float a) {
        // Bottom rectangle
        addLine(minX, minY, minZ, maxX, minY, minZ, r, g, b, a);
        addLine(maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a);
        addLine(maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a);
        addLine(minX, minY, maxZ, minX, minY, minZ, r, g, b, a);
        // Top rectangle
        addLine(minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a);
        addLine(maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a);
        addLine(maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a);
        addLine(minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a);
        // Vertical edges
        addLine(minX, minY, minZ, minX, maxY, minZ, r, g, b, a);
        addLine(maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a);
        addLine(maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a);
        addLine(minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a);
    }

    public void addVoxelOutline(int x, int y, int z, float r, float g, float b, float a) {
        addWireBox(x, y, z, x + 1f, y + 1f, z + 1f, r, g, b, a);
    }

    public void addText(float x, float y, float scale, String text,
                        float r, float g, float b, float a) {
        if (text == null || text.isEmpty() || scale <= 0f) {
            return;
        }
        ensureStbCapacity(text.length() * 64);
        stbBuffer.clear();
        int quads = STBEasyFont.stb_easy_font_print(0f, 0f, text, null, stbBuffer);
        if (quads <= 0) {
            return;
        }
        if (scale != 1f) {
            // nothing to do here, scaling is handled below per vertex
        }
        for (int i = 0; i < quads; i++) {
            int base = i * 64;
            float x0 = stbBuffer.getFloat(base) * scale + x;
            float y0 = stbBuffer.getFloat(base + 4) * scale + y;
            float x1 = stbBuffer.getFloat(base + 16) * scale + x;
            float y1 = stbBuffer.getFloat(base + 20) * scale + y;
            float x2 = stbBuffer.getFloat(base + 32) * scale + x;
            float y2 = stbBuffer.getFloat(base + 36) * scale + y;
            float x3 = stbBuffer.getFloat(base + 48) * scale + x;
            float y3 = stbBuffer.getFloat(base + 52) * scale + y;
            ensureTextCapacity(TEXT_FLOATS_PER_VERTEX * 6);
            putTextVertex(x0, y0, r, g, b, a);
            putTextVertex(x1, y1, r, g, b, a);
            putTextVertex(x2, y2, r, g, b, a);
            putTextVertex(x0, y0, r, g, b, a);
            putTextVertex(x2, y2, r, g, b, a);
            putTextVertex(x3, y3, r, g, b, a);
            textVertices += 6;
        }
    }

    public void renderLines(Matrix4f proj, Matrix4f view) {
        if (lineVertices == 0) {
            return;
        }
        FloatBuffer vertexBuf = org.lwjgl.system.MemoryUtil.memAllocFloat(lineFloats);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer projBuf = stack.mallocFloat(16);
            proj.get(projBuf);
            FloatBuffer viewBuf = stack.mallocFloat(16);
            view.get(viewBuf);
            vertexBuf.put(lineData, 0, lineFloats).flip();

            glUseProgram(lineProgram);
            glUniformMatrix4fv(lineProjLoc, false, projBuf);
            glUniformMatrix4fv(lineViewLoc, false, viewBuf);
            glBindVertexArray(lineVao);
            glBindBuffer(GL_ARRAY_BUFFER, lineVbo);
            glBufferData(GL_ARRAY_BUFFER, vertexBuf, GL_DYNAMIC_DRAW);
            glDisable(GL_DEPTH_TEST);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDrawArrays(GL_LINES, 0, lineVertices);
            glDisable(GL_BLEND);
            glEnable(GL_DEPTH_TEST);
            glBindVertexArray(0);
            glUseProgram(0);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(vertexBuf);
        }
        lineFloats = 0;
        lineVertices = 0;
    }

    public void renderText(int width, int height) {
        if (textVertices == 0) {
            return;
        }
        FloatBuffer vertexBuf = org.lwjgl.system.MemoryUtil.memAllocFloat(textFloats);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Matrix4f ortho = new Matrix4f().ortho(0f, width, height, 0f, -1f, 1f);
            FloatBuffer orthoBuf = stack.mallocFloat(16);
            ortho.get(orthoBuf);
            vertexBuf.put(textData, 0, textFloats).flip();

            glUseProgram(textProgram);
            glUniformMatrix4fv(textOrthoLoc, false, orthoBuf);
            glBindVertexArray(textVao);
            glBindBuffer(GL_ARRAY_BUFFER, textVbo);
            glBufferData(GL_ARRAY_BUFFER, vertexBuf, GL_DYNAMIC_DRAW);
            glDisable(GL_DEPTH_TEST);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDrawArrays(GL_TRIANGLES, 0, textVertices);
            glDisable(GL_BLEND);
            glEnable(GL_DEPTH_TEST);
            glBindVertexArray(0);
            glUseProgram(0);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(vertexBuf);
        }
        textFloats = 0;
        textVertices = 0;
    }

    @Override
    public void close() {
        glDeleteProgram(lineProgram);
        glDeleteProgram(textProgram);
        glDeleteBuffers(lineVbo);
        glDeleteVertexArrays(lineVao);
        glDeleteBuffers(textVbo);
        glDeleteVertexArrays(textVao);
        if (stbBuffer != null) {
            org.lwjgl.system.MemoryUtil.memFree(stbBuffer);
            stbBuffer = null;
        }
    }

    private void ensureLineCapacity(int additionalFloats) {
        int required = lineFloats + additionalFloats;
        if (required <= lineData.length) {
            return;
        }
        int newCapacity = Math.max(required, lineData.length * 2);
        lineData = java.util.Arrays.copyOf(lineData, newCapacity);
    }

    private void ensureTextCapacity(int additionalFloats) {
        int required = textFloats + additionalFloats;
        if (required <= textData.length) {
            return;
        }
        int newCapacity = Math.max(required, textData.length * 2);
        textData = java.util.Arrays.copyOf(textData, newCapacity);
    }

    private void ensureStbCapacity(int requiredBytes) {
        if (requiredBytes <= stbBuffer.capacity()) {
            return;
        }
        int newCapacity = Math.max(requiredBytes, stbBuffer.capacity() * 2);
        ByteBuffer old = stbBuffer;
        stbBuffer = createStbBuffer(newCapacity);
        org.lwjgl.system.MemoryUtil.memFree(old);
    }

    private static ByteBuffer createStbBuffer(int capacity) {
        ByteBuffer buffer = org.lwjgl.system.MemoryUtil.memAlloc(capacity);
        buffer.order(ByteOrder.nativeOrder());
        return buffer;
    }

    private void putTextVertex(float x, float y, float r, float g, float b, float a) {
        textData[textFloats++] = x;
        textData[textFloats++] = y;
        textData[textFloats++] = r;
        textData[textFloats++] = g;
        textData[textFloats++] = b;
        textData[textFloats++] = a;
    }

    private static int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new RuntimeException("Debug shader compile failed: " + log);
        }
        return shader;
    }

    private static int linkProgram(int... shaders) {
        int program = glCreateProgram();
        for (int shader : shaders) {
            glAttachShader(program, shader);
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new RuntimeException("Debug shader link failed: " + log);
        }
        for (int shader : shaders) {
            glDetachShader(program, shader);
            glDeleteShader(shader);
        }
        return program;
    }
}
