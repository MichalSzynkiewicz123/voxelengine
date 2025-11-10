package com.example.voxelrt.app;

import com.example.voxelrt.util.ResourceUtils;
import org.joml.Vector3f;
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
    private double lastFrameTime;

    private int locResolution;
    private int locTime;
    private int locCameraPos;
    private int locCameraForward;
    private int locCameraRight;
    private int locCameraUp;

    private final boolean[] keyDown = new boolean[GLFW_KEY_LAST + 1];
    private boolean mouseCaptured = true;
    private boolean firstMouseInput = true;
    private double lastMouseX;
    private double lastMouseY;
    private float yaw = (float) Math.toRadians(-90.0);
    private float pitch = 0.0f;

    private final Vector3f cameraPos = new Vector3f(0.0f, 1.5f, 5.0f);
    private final Vector3f cameraForward = new Vector3f(0.0f, 0.0f, -1.0f);
    private final Vector3f cameraRight = new Vector3f(1.0f, 0.0f, 0.0f);
    private final Vector3f cameraUp = new Vector3f(0.0f, 1.0f, 0.0f);

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
                if (mouseCaptured) {
                    mouseCaptured = false;
                    glfwSetInputMode(win, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                } else {
                    glfwSetWindowShouldClose(win, true);
                }
            }
            if (key == GLFW_KEY_TAB && action == GLFW_PRESS && !mouseCaptured) {
                mouseCaptured = true;
                firstMouseInput = true;
                glfwSetInputMode(win, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            }
            if (key >= 0 && key < keyDown.length) {
                if (action == GLFW_PRESS) {
                    keyDown[key] = true;
                } else if (action == GLFW_RELEASE) {
                    keyDown[key] = false;
                }
            }
        });
        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            if (!mouseCaptured) {
                return;
            }
            if (firstMouseInput) {
                lastMouseX = xpos;
                lastMouseY = ypos;
                firstMouseInput = false;
                return;
            }
            double dx = xpos - lastMouseX;
            double dy = ypos - lastMouseY;
            lastMouseX = xpos;
            lastMouseY = ypos;

            float sensitivity = 0.0025f;
            yaw += dx * sensitivity;
            pitch -= dy * sensitivity;
            float maxPitch = (float) Math.toRadians(89.0);
            if (pitch > maxPitch) {
                pitch = maxPitch;
            } else if (pitch < -maxPitch) {
                pitch = -maxPitch;
            }
        });
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS && !mouseCaptured) {
                mouseCaptured = true;
                firstMouseInput = true;
                glfwSetInputMode(win, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            }
        });
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        startTime = System.nanoTime();
        glfwSetTime(0.0);
        lastFrameTime = glfwGetTime();
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
        locResolution = glGetUniformLocation(program, "u_resolution");
        locTime = glGetUniformLocation(program, "u_time");
        locCameraPos = glGetUniformLocation(program, "u_cameraPos");
        locCameraForward = glGetUniformLocation(program, "u_cameraForward");
        locCameraRight = glGetUniformLocation(program, "u_cameraRight");
        locCameraUp = glGetUniformLocation(program, "u_cameraUp");
    }

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            double now = glfwGetTime();
            float deltaTime = (float) (now - lastFrameTime);
            lastFrameTime = now;
            updateCamera(deltaTime);
            int[] width = new int[1];
            int[] height = new int[1];
            glfwGetFramebufferSize(window, width, height);
            glViewport(0, 0, width[0], height[0]);
            glClear(GL_COLOR_BUFFER_BIT);

            glUseProgram(program);
            glUniform2f(locResolution, width[0], height[0]);
            float timeSeconds = (System.nanoTime() - startTime) / 1_000_000_000.0f;
            glUniform1f(locTime, timeSeconds);
            glUniform3f(locCameraPos, cameraPos.x, cameraPos.y, cameraPos.z);
            glUniform3f(locCameraForward, cameraForward.x, cameraForward.y, cameraForward.z);
            glUniform3f(locCameraRight, cameraRight.x, cameraRight.y, cameraRight.z);
            glUniform3f(locCameraUp, cameraUp.x, cameraUp.y, cameraUp.z);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glfwSwapBuffers(window);
        }
    }

    private void updateCamera(float deltaTime) {
        Vector3f worldUp = new Vector3f(0.0f, 1.0f, 0.0f);
        cameraForward.set(
                (float) (Math.cos(pitch) * Math.sin(yaw)),
                (float) Math.sin(pitch),
                (float) (Math.cos(pitch) * Math.cos(yaw))
        ).normalize();
        cameraRight.set(cameraForward).cross(worldUp).normalize();
        cameraUp.set(cameraRight).cross(cameraForward).normalize();

        Vector3f move = new Vector3f();
        Vector3f forwardXZ = new Vector3f(cameraForward.x, 0.0f, cameraForward.z);
        if (forwardXZ.lengthSquared() > 0.0f) {
            forwardXZ.normalize();
        }
        Vector3f rightXZ = new Vector3f(cameraRight.x, 0.0f, cameraRight.z);
        if (rightXZ.lengthSquared() > 0.0f) {
            rightXZ.normalize();
        }

        if (keyDown[GLFW_KEY_W]) {
            move.add(forwardXZ);
        }
        if (keyDown[GLFW_KEY_S]) {
            move.sub(forwardXZ);
        }
        if (keyDown[GLFW_KEY_A]) {
            move.sub(rightXZ);
        }
        if (keyDown[GLFW_KEY_D]) {
            move.add(rightXZ);
        }
        if (keyDown[GLFW_KEY_SPACE]) {
            move.y += 1.0f;
        }
        if (keyDown[GLFW_KEY_LEFT_CONTROL] || keyDown[GLFW_KEY_C]) {
            move.y -= 1.0f;
        }

        if (move.lengthSquared() > 0.0f) {
            move.normalize();
            float speed = keyDown[GLFW_KEY_LEFT_SHIFT] ? 6.0f : 3.0f;
            move.mul(speed * deltaTime);
            cameraPos.add(move);
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
