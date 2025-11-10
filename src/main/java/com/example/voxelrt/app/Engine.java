package com.example.voxelrt.app;

import com.example.voxelrt.util.ResourceUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Minimal application harness that renders a single full-screen ray marching shader.
 */
public final class Engine {
    private static final String VERTEX_SHADER = """
            #version 330 core\n
            const vec2 verts[3] = vec2[3](
                vec2(-1.0, -1.0),
                vec2(3.0, -1.0),
                vec2(-1.0, 3.0)
            );

            void main() {
                gl_Position = vec4(verts[gl_VertexID], 0.0, 1.0);
            }
            """;

    private long window;
    private int program;
    private int vao;
    private long startTime;

    public void run() {
        initWindow();
        initOpenGL();
        initShader();
        loop();
        cleanup();
    }

    private void initWindow() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Failed to initialise GLFW");
        }
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(1280, 720, "Ray Marching", NULL, NULL);
        if (window == NULL) {
            throw new IllegalStateException("Failed to create GLFW window");
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
                glfwSetWindowShouldClose(win, true);
            }
        });
        startTime = System.nanoTime();
    }

    private void initOpenGL() {
        GL.createCapabilities();
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
    }

    private void initShader() {
        int vs = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER);
        String fragmentSource = ResourceUtils.loadTextResource("shaders/raymarch.frag");
        int fs = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
        program = linkProgram(vs, fs);
    }

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            int[] width = new int[1];
            int[] height = new int[1];
            glfwGetFramebufferSize(window, width, height);
            glViewport(0, 0, width[0], height[0]);
            glClear(GL_COLOR_BUFFER_BIT);

            glUseProgram(program);
            glUniform2f(glGetUniformLocation(program, "u_resolution"), width[0], height[0]);
            float timeSeconds = (System.nanoTime() - startTime) / 1_000_000_000.0f;
            glUniform1f(glGetUniformLocation(program, "u_time"), timeSeconds);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glfwSwapBuffers(window);
        }
    }

    private void cleanup() {
        glDeleteVertexArrays(vao);
        glDeleteProgram(program);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private static int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("Shader compilation failed: " + log);
        }
        return shader;
    }

    private static int linkProgram(int vs, int fs) {
        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            glDeleteShader(vs);
            glDeleteShader(fs);
            throw new IllegalStateException("Program link failed: " + log);
        }
        glDetachShader(program, vs);
        glDetachShader(program, fs);
        glDeleteShader(vs);
        glDeleteShader(fs);
        return program;
    }
}
