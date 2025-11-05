package com.example.voxelrt;

import com.example.voxelrt.mesh.ChunkMesh;
import com.example.voxelrt.mesh.MeshBuilder;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Main application driver that owns the GLFW window, OpenGL resources and game loop.
 * <p>
 * The engine sets up LWJGL bindings, feeds camera input, manages compute shaders and keeps the CPU
 * and GPU representations of the world in sync.
 */
public class Engine {
    private long window;
    private int width = 1280, height = 720;
    private float resolutionScale = 1.0f;

    private int computeProgram, quadProgram, meshProgram;
    private int locComputeSkyModel = -1;
    private int locComputeTurbidity = -1;
    private int locComputeSkyIntensity = -1;
    private int locComputeSkyZenith = -1;
    private int locComputeSkyHorizon = -1;
    private int locComputeSunAngularRadius = -1;
    private int locComputeSunSoftSamples = -1;
    private int locComputeTorchEnabled = -1;
    private int locComputeTorchPos = -1;
    private int locComputeTorchIntensity = -1;
    private int locComputeTorchRadius = -1;
    private int locComputeTorchSoftSamples = -1;
    private int locComputeInvProj = -1;
    private int locComputeInvView = -1;
    private int locComputeWorldSize = -1;
    private int locComputeWorldSizeCoarse = -1;
    private int locComputeRegionOrigin = -1;
    private int locComputeVoxelScale = -1;
    private int locComputeLodScale = -1;
    private int locComputeLodSwitchDistance = -1;
    private int locComputeCamPos = -1;
    private int locComputeSunDir = -1;
    private int locComputeResolution = -1;
    private int locComputeDebugGradient = -1;
    private int locComputeUseGPUWorld = -1;
    private int locQuadTex = -1;
    private int locQuadScreenSize = -1;
    private int locQuadPresentTest = -1;
    private int locMeshProj = -1;
    private int locMeshView = -1;
    private int locMeshSunDir = -1;
    private int outputTex, vaoQuad;
    private int ssboVoxels;
    private int ssboVoxelsCoarse;

    private Camera camera = new Camera(new Vector3f(64, 120, 64));
    private boolean mouseCaptured = true;
    private double lastMouseX = Double.NaN, lastMouseY = Double.NaN;

    private WorldGenerator generator;
    private ChunkManager chunkManager;
    private ActiveRegion region;
    private int placeBlock = Blocks.GRASS;

    private boolean debugGradient = false;
    private boolean presentTest = false;
    private boolean computeEnabled = true;
    private boolean rasterEnabled = false;
    private boolean useGPUWorld = false; // start with GPU fallback visible
    private float lodSwitchDistance = 72.0f;
    private float lastRegionYaw = Float.NaN;
    private float lastRegionPitch = Float.NaN;
    private static final float REGION_REBUILD_ANGLE_THRESHOLD = 5f;
    private static final int PREFETCH_LOOKAHEAD_CHUNKS = 2;
    private static final float PREFETCH_DIRECTION_THRESHOLD = 0.15f;
    private static final int PREFETCH_MARGIN = 48;
    private final Vector3f lastPrefetchPosition = new Vector3f();
    private int prefetchedEast = Integer.MIN_VALUE;
    private int prefetchedWest = Integer.MAX_VALUE;
    private int prefetchedSouth = Integer.MIN_VALUE;
    private int prefetchedNorth = Integer.MAX_VALUE;

    /**
     * Starts the engine and tears down the native resources when the loop exits.
     */
    public void run() {
        initWindow();
        initGL();
        initResources();
        loop();
        cleanup();
    }

    /**
     * Configures GLFW, opens the window and installs the input callbacks that feed the camera.
     */
    private void initWindow() {
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        window = glfwCreateWindow(width, height, "Voxel RT ", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Create window failed");
        GLFWVidMode vm = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vm.width() - width) / 2, (vm.height() - height) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);

        glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            width = Math.max(1, w);
            height = Math.max(1, h);
            recreateOutputTexture();
        });

        glfwSetCursorPosCallback(window, (win, mx, my) -> {
            if (!mouseCaptured) return;
            if (Double.isNaN(lastMouseX)) {
                lastMouseX = mx;
                lastMouseY = my;
            }
            double dx = mx - lastMouseX, dy = my - lastMouseY;
            lastMouseX = mx;
            lastMouseY = my;
            camera.addYawPitch((float) dx, (float) dy);
        });

        glfwSetKeyCallback(window, (win, key, sc, action, mods) -> {
            if (action == GLFW_PRESS) {
                if (key == GLFW_KEY_ESCAPE) {
                    mouseCaptured = !mouseCaptured;
                    glfwSetInputMode(window, GLFW_CURSOR, mouseCaptured ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
                }
                if (key == GLFW_KEY_E) {
                    placeBlock = switch (placeBlock) {
                        case Blocks.GRASS -> Blocks.DIRT;
                        case Blocks.DIRT -> Blocks.STONE;
                        case Blocks.STONE -> Blocks.SAND;
                        case Blocks.SAND -> Blocks.SNOW;
                        default -> Blocks.GRASS;
                    };
                }
                if (key == GLFW_KEY_R) {
                    int rw = Math.max(1, (int) (width * resolutionScale));
                    int rh = Math.max(1, (int) (height * resolutionScale));
                    Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(75.0), (float) rw / rh, 0.1f, 800.0f);
                    Matrix4f view = camera.viewMatrix();
                    Frustum frustum = buildFrustum(proj, view);
                    region.rebuildAround((int) Math.floor(camera.position.x),
                            (int) Math.floor(camera.position.y),
                            (int) Math.floor(camera.position.z),
                            frustum);
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboVoxels);
                }
                if (key == GLFW_KEY_G) {
                    debugGradient = !debugGradient;
                    System.out.println("[DEBUG] debugGradient=" + debugGradient);
                }
                if (key == GLFW_KEY_P) {
                    presentTest = !presentTest;
                    System.out.println("[DEBUG] presentTest=" + presentTest);
                }
                if (key == GLFW_KEY_C) {
                    computeEnabled = !computeEnabled;
                    System.out.println("[DEBUG] computeEnabled=" + computeEnabled);
                }
                if (key == GLFW_KEY_M) {
                    rasterEnabled = !rasterEnabled;
                    System.out.println("[DEBUG] rasterEnabled=" + rasterEnabled);
                }
                if (key == GLFW_KEY_H) {
                    useGPUWorld = !useGPUWorld;
                    System.out.println("[DEBUG] useGPUWorld=" + useGPUWorld);
                }
                if (key == GLFW_KEY_KP_ADD || key == GLFW_KEY_EQUAL) {
                    resolutionScale = Math.min(2.0f, resolutionScale + 0.1f);
                    recreateOutputTexture();
                }
                if (key == GLFW_KEY_KP_SUBTRACT || key == GLFW_KEY_MINUS) {
                    resolutionScale = Math.max(0.5f, resolutionScale - 0.1f);
                    recreateOutputTexture();
                }
            }
        });

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (!mouseCaptured || action != GLFW_PRESS) return;
            Vector3f origin = new Vector3f(camera.position);
            Vector3f dir = camera.getForward();
            Raycast.Hit hit = Raycast.raycast(chunkManager, origin, dir, 8f);
            if (hit != null) {
                if (button == GLFW_MOUSE_BUTTON_LEFT) {
                    chunkManager.setEdit(hit.x, hit.y, hit.z, Blocks.AIR);
                    region.setVoxelWorld(hit.x, hit.y, hit.z, Blocks.AIR);
                } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                    int px = hit.x + hit.nx, py = hit.y + hit.ny, pz = hit.z + hit.nz;
                    chunkManager.setEdit(px, py, pz, placeBlock);
                    region.setVoxelWorld(px, py, pz, placeBlock);
                }
            }
        });

        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        if (glfwRawMouseMotionSupported()) glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
    }

    /**
     * Performs basic OpenGL state setup after the GLFW context has been created.
     */
    private void initGL() {
        GL.createCapabilities();
        System.out.println("OpenGL: " + glGetString(GL_VERSION));
        System.out.println("GPU: " + glGetString(GL_RENDERER));
        glClearColor(0.12f, 0.14f, 0.18f, 1.0f);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
    }

    /**
     * Loads a text resource either from the classpath or directly from the resources directory.
     */
    private String loadResource(String path) {
        try {
            var is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
            if (is != null) return new String(is.readAllBytes());
            return Files.readString(Path.of("src/main/resources/" + path));
        } catch (IOException e) {
            throw new RuntimeException("load " + path, e);
        }
    }

    /**
     * Compiles a shader of the given type and prints a detailed log when compilation fails.
     */
    private int compileShader(int type, String src) {
        int id = glCreateShader(type);
        glShaderSource(id, src);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) != GL_TRUE) {
            String log = glGetShaderInfoLog(id);
            System.err.println("=== SHADER COMPILE FAILED ===");
            System.err.println(log);
            String[] lines = src.split("\r?\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String n = String.format("%03d", i + 1);
                System.err.println(n + ": " + lines[i]);
            }
            throw new RuntimeException("Shader compile error: " + log);
        }
        return id;
    }

    private int linkProgram(int... shaders) {
        int prog = glCreateProgram();
        for (int s : shaders) glAttachShader(prog, s);
        glLinkProgram(prog);
        if (glGetProgrami(prog, GL_LINK_STATUS) != GL_TRUE)
            throw new RuntimeException("Link error: " + glGetProgramInfoLog(prog));
        for (int s : shaders) glDeleteShader(s);
        return prog;
    }

    private static int findTopSolidY(ChunkManager cm, int x, int z) {
        for (int y = Chunk.SY - 2; y >= 0; y--) {
            int b = cm.sample(x, y, z);
            if (b != Blocks.AIR) {
                boolean headFree = (y + 1 < Chunk.SY && cm.sample(x, y + 1, z) == Blocks.AIR)
                        && (y + 2 >= Chunk.SY || cm.sample(x, y + 2, z) == Blocks.AIR);
                if (headFree) return y;
            }
        }
        return 64;
    }

    /**
     * Allocates OpenGL resources, loads shaders and seeds the world generation structures.
     */
    private void initResources() {
        computeProgram = linkProgram(compileShader(GL_COMPUTE_SHADER, loadResource("shaders/voxel.comp")));
        quadProgram = linkProgram(
                compileShader(GL_VERTEX_SHADER, loadResource("shaders/quad.vert")),
                compileShader(GL_FRAGMENT_SHADER, loadResource("shaders/quad.frag"))
        );
        meshProgram = linkProgram(
                compileShader(GL_VERTEX_SHADER, loadResource("shaders/chunk.vert")),
                compileShader(GL_FRAGMENT_SHADER, loadResource("shaders/chunk.frag"))
        );
        cacheComputeUniformLocations();
        cacheQuadUniformLocations();
        cacheMeshUniformLocations();

        vaoQuad = glGenVertexArrays();
        createOutputTexture();

        generator = new WorldGenerator(1337L, 62);
        int chunkCacheSize = determineChunkCacheSize();
        chunkManager = new ChunkManager(generator, chunkCacheSize);
        System.out.println("[Engine] Chunk cache capacity set to " + chunkManager.getMaxLoaded() + " chunks");

        // Spawn above ground
        int spawnX = (int) Math.floor(camera.position.x);
        int spawnZ = (int) Math.floor(camera.position.z);
        int topY = findTopSolidY(chunkManager, spawnX, spawnZ);
        camera.position.set(spawnX + 0.5f, topY + 2.5f, spawnZ + 0.5f);

        region = new ActiveRegion(chunkManager, 128, 128, 128);
        int rw = Math.max(1, (int) (width * resolutionScale));
        int rh = Math.max(1, (int) (height * resolutionScale));
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(75.0), (float) rw / rh, 0.1f, 800.0f);
        Matrix4f view = camera.viewMatrix();
        Frustum frustum = buildFrustum(proj, view);
        region.rebuildAround((int) Math.floor(camera.position.x),
                (int) Math.floor(camera.position.y),
                (int) Math.floor(camera.position.z),
                frustum);
        lastRegionYaw = camera.yawDeg();
        lastRegionPitch = camera.pitchDeg();
        ssboVoxels = region.ssbo();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboVoxels);
        ssboVoxelsCoarse = region.ssboCoarse();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssboVoxelsCoarse);
        resetPrefetchBounds();
        lastPrefetchPosition.set(camera.position);
    }

    private int determineChunkCacheSize() {
        String configured = System.getProperty("voxel.maxChunks");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("VOXEL_MAX_CHUNKS");
        }
        if (configured != null) {
            try {
                int parsed = Integer.parseInt(configured.trim());
                if (parsed > 0) {
                    return parsed;
                }
                System.err.println("[Engine] Ignoring non-positive chunk cache override: " + configured);
            } catch (NumberFormatException ex) {
                System.err.println("[Engine] Failed to parse chunk cache override '" + configured + "': " + ex.getMessage());
            }
        }

        long chunkBytes = (long) Chunk.SX * Chunk.SY * Chunk.SZ * Integer.BYTES;
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory <= 0 || maxMemory == Long.MAX_VALUE) {
            long total = Runtime.getRuntime().totalMemory();
            if (total > 0 && total != Long.MAX_VALUE) {
                maxMemory = total;
            }
        }

        if (maxMemory <= 0 || maxMemory == Long.MAX_VALUE) {
            return ChunkManager.DEFAULT_CACHE_SIZE;
        }

        long budget = java.lang.Math.max(maxMemory / 5, 128L << 20);
        long computed = budget / chunkBytes;
        if (computed <= 0) {
            return ChunkManager.DEFAULT_CACHE_SIZE;
        }

        long capped = java.lang.Math.min(Integer.MAX_VALUE, computed);
        return (int) java.lang.Math.max(ChunkManager.MIN_CACHE_SIZE, capped);
    }

    private void cacheComputeUniformLocations() {
        locComputeSkyModel = glGetUniformLocation(computeProgram, "uSkyModel");
        locComputeTurbidity = glGetUniformLocation(computeProgram, "uTurbidity");
        locComputeSkyIntensity = glGetUniformLocation(computeProgram, "uSkyIntensity");
        locComputeSkyZenith = glGetUniformLocation(computeProgram, "uSkyZenith");
        locComputeSkyHorizon = glGetUniformLocation(computeProgram, "uSkyHorizon");
        locComputeSunAngularRadius = glGetUniformLocation(computeProgram, "uSunAngularRadius");
        locComputeSunSoftSamples = glGetUniformLocation(computeProgram, "uSunSoftSamples");
        locComputeTorchEnabled = glGetUniformLocation(computeProgram, "uTorchEnabled");
        locComputeTorchPos = glGetUniformLocation(computeProgram, "uTorchPos");
        locComputeTorchIntensity = glGetUniformLocation(computeProgram, "uTorchIntensity");
        locComputeTorchRadius = glGetUniformLocation(computeProgram, "uTorchRadius");
        locComputeTorchSoftSamples = glGetUniformLocation(computeProgram, "uTorchSoftSamples");
        locComputeInvProj = glGetUniformLocation(computeProgram, "uInvProj");
        locComputeInvView = glGetUniformLocation(computeProgram, "uInvView");
        locComputeWorldSize = glGetUniformLocation(computeProgram, "uWorldSize");
        locComputeWorldSizeCoarse = glGetUniformLocation(computeProgram, "uWorldSizeCoarse");
        locComputeRegionOrigin = glGetUniformLocation(computeProgram, "uRegionOrigin");
        locComputeVoxelScale = glGetUniformLocation(computeProgram, "uVoxelScale");
        locComputeLodScale = glGetUniformLocation(computeProgram, "uLodScale");
        locComputeLodSwitchDistance = glGetUniformLocation(computeProgram, "uLodSwitchDistance");
        locComputeCamPos = glGetUniformLocation(computeProgram, "uCamPos");
        locComputeSunDir = glGetUniformLocation(computeProgram, "uSunDir");
        locComputeResolution = glGetUniformLocation(computeProgram, "uResolution");
        locComputeDebugGradient = glGetUniformLocation(computeProgram, "uDebugGradient");
        locComputeUseGPUWorld = glGetUniformLocation(computeProgram, "uUseGPUWorld");
    }

    private void cacheQuadUniformLocations() {
        locQuadTex = glGetUniformLocation(quadProgram, "uTex");
        locQuadScreenSize = glGetUniformLocation(quadProgram, "uScreenSize");
        locQuadPresentTest = glGetUniformLocation(quadProgram, "uPresentTest");
    }

    private void cacheMeshUniformLocations() {
        locMeshProj = glGetUniformLocation(meshProgram, "uProj");
        locMeshView = glGetUniformLocation(meshProgram, "uView");
        locMeshSunDir = glGetUniformLocation(meshProgram, "uSunDir");
    }

    private void rebuildChunkMeshes(java.util.List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        for (Chunk chunk : chunks) {
            if (!chunk.isMeshDirty()) {
                continue;
            }
            MeshBuilder.MeshData data = MeshBuilder.build(chunk, chunkManager);
            ChunkMesh old = chunk.mesh();
            ChunkMesh nextMesh = null;
            if (data.vertexCount() > 0) {
                nextMesh = ChunkMesh.create(data.vertices(), data.vertexCount());
            }
            if (old != null) {
                old.destroy();
            }
            chunk.setMesh(nextMesh);
            chunk.clearMeshDirty();
        }
    }

    private void renderChunkMeshes(Matrix4f proj, Matrix4f view, java.util.List<Chunk> chunks) {
        glViewport(0, 0, width, height);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glUseProgram(meshProgram);
        uploadMatrix(locMeshProj, proj);
        uploadMatrix(locMeshView, view);
        Vector3f sunDir = new Vector3f(-0.6f, -1.0f, -0.3f).normalize();
        if (locMeshSunDir >= 0) {
            glUniform3f(locMeshSunDir, sunDir.x, sunDir.y, sunDir.z);
        }
        for (Chunk chunk : chunks) {
            ChunkMesh mesh = chunk.mesh();
            if (mesh != null && mesh.vertexCount() > 0) {
                mesh.draw();
            }
        }
        glUseProgram(0);
    }

    private void createOutputTexture() {
        if (outputTex != 0) glDeleteTextures(outputTex);
        int rw = Math.max(1, (int) (width * resolutionScale));
        int rh = Math.max(1, (int) (height * resolutionScale));
        outputTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, outputTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, rw, rh, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private void recreateOutputTexture() {
        createOutputTexture();
    }

    /**
     * Main render loop – processes input, updates camera movement and dispatches rendering.
     */
    private void loop() {
        double lastTime = glfwGetTime();
        double lastPrint = lastTime;
        double fpsTimer = lastTime;
        int fpsFrames = 0;
        while (!glfwWindowShouldClose(window)) {
            double now = glfwGetTime();
            float dt = (float) (now - lastTime);
            lastTime = now;
            fpsFrames++;

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            pollInput(dt);

            chunkManager.update();
            boolean loadedNewChunks = chunkManager.drainIntegratedFlag();
            java.util.List<Chunk> loadedChunks = chunkManager.snapshotLoadedChunks();
            rebuildChunkMeshes(loadedChunks);

            int cx = (int) Math.floor(camera.position.x);
            int cy = (int) Math.floor(camera.position.y);
            int cz = (int) Math.floor(camera.position.z);
            int margin = 24;
            int rw = Math.max(1, (int) (width * resolutionScale));
            int rh = Math.max(1, (int) (height * resolutionScale));
            Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(75.0), (float) rw / rh, 0.1f, 800.0f);
            Matrix4f view = camera.viewMatrix();
            Matrix4f invProj = new Matrix4f(proj).invert();
            Matrix4f invView = new Matrix4f(view).invert();

            float yawDiff = angularDifference(camera.yawDeg(), lastRegionYaw);
            float pitchDiff = Float.isNaN(lastRegionPitch) ? Float.POSITIVE_INFINITY : (float) java.lang.Math.abs(camera.pitchDeg() - lastRegionPitch);
            boolean rotatedSignificantly = yawDiff > REGION_REBUILD_ANGLE_THRESHOLD || pitchDiff > REGION_REBUILD_ANGLE_THRESHOLD;
            boolean needsRegionRebuild = loadedNewChunks ||
                    cx < region.originX + margin || cz < region.originZ + margin ||
                    cx > region.originX + region.rx - margin || cz > region.originZ + region.rz - margin ||
                    cy < region.originY + margin || cy > region.originY + region.ry - margin ||
                    rotatedSignificantly;

            if (needsRegionRebuild) {
                Frustum frustum = buildFrustum(proj, view);
                region.rebuildAround(cx, cy, cz, frustum);
                ssboVoxels = region.ssbo();
                ssboVoxelsCoarse = region.ssboCoarse();
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboVoxels);
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssboVoxelsCoarse);
                lastRegionYaw = camera.yawDeg();
                lastRegionPitch = camera.pitchDeg();
                resetPrefetchBounds();
                lastPrefetchPosition.set(camera.position);
            }

            updatePrefetch();

            // Compute pass
            if (!rasterEnabled && computeEnabled) {
                glUseProgram(computeProgram);
                if (locComputeSkyModel >= 0) glUniform1i(locComputeSkyModel, 1);         // Preetham
                if (locComputeTurbidity >= 0) glUniform1f(locComputeTurbidity, 2.5f);    // mild clear sky
                if (locComputeSkyIntensity >= 0) glUniform1f(locComputeSkyIntensity, 1.0f);
                if (locComputeSkyZenith >= 0)
                    glUniform3f(locComputeSkyZenith, 0.60f, 0.70f, 0.90f);   // fallback gradient
                if (locComputeSkyHorizon >= 0) glUniform3f(locComputeSkyHorizon, 0.95f, 0.80f, 0.60f);
                if (locComputeSunAngularRadius >= 0) glUniform1f(locComputeSunAngularRadius, 0.00465f);    // ~0.266°
                if (locComputeSunSoftSamples >= 0) glUniform1i(locComputeSunSoftSamples, 8);

                if (locComputeTorchEnabled >= 0) glUniform1i(locComputeTorchEnabled, 1);
                if (locComputeTorchPos >= 0)
                    glUniform3f(locComputeTorchPos, camera.position.x, camera.position.y, camera.position.z);
                if (locComputeTorchIntensity >= 0) glUniform1f(locComputeTorchIntensity, 30.0f);
                if (locComputeTorchRadius >= 0) glUniform1f(locComputeTorchRadius, 0.15f);
                if (locComputeTorchSoftSamples >= 0) glUniform1i(locComputeTorchSoftSamples, 8);

                glBindImageTexture(0, outputTex, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);

                uploadMatrix(locComputeInvProj, invProj);
                uploadMatrix(locComputeInvView, invView);

                if (locComputeWorldSize >= 0) glUniform3i(locComputeWorldSize, region.rx, region.ry, region.rz);
                if (locComputeWorldSizeCoarse >= 0)
                    glUniform3i(locComputeWorldSizeCoarse, region.rxCoarse(), region.ryCoarse(), region.rzCoarse());
                if (locComputeRegionOrigin >= 0)
                    glUniform3i(locComputeRegionOrigin, region.originX, region.originY, region.originZ);
                if (locComputeVoxelScale >= 0) glUniform1f(locComputeVoxelScale, 1.0f);
                if (locComputeLodScale >= 0) glUniform1f(locComputeLodScale, region.lodScale());
                if (locComputeLodSwitchDistance >= 0) glUniform1f(locComputeLodSwitchDistance, lodSwitchDistance);
                if (locComputeCamPos >= 0)
                    glUniform3f(locComputeCamPos, camera.position.x, camera.position.y, camera.position.z);
                Vector3f sunDir = new Vector3f(-0.6f, -1.0f, -0.3f).normalize();
                if (locComputeSunDir >= 0) glUniform3f(locComputeSunDir, sunDir.x, sunDir.y, sunDir.z);
                if (locComputeResolution >= 0) glUniform2i(locComputeResolution, rw, rh);
                if (locComputeDebugGradient >= 0) glUniform1i(locComputeDebugGradient, debugGradient ? 1 : 0);
                if (locComputeUseGPUWorld >= 0) glUniform1i(locComputeUseGPUWorld, useGPUWorld ? 1 : 0);

                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboVoxels);
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssboVoxelsCoarse);
                int gx = (rw + 15) / 16, gy = (rh + 15) / 16;
                glDispatchCompute(gx, gy, 1);
                glUseProgram(0);
                glMemoryBarrier(GL_ALL_BARRIER_BITS);
            }

            // Present
            if (rasterEnabled) {
                renderChunkMeshes(proj, view, loadedChunks);
            } else {
                glDisable(GL_DEPTH_TEST);
                glDisable(GL_CULL_FACE);
                glViewport(0, 0, width, height);
                glUseProgram(quadProgram);
                glBindVertexArray(vaoQuad);
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, outputTex);
                if (locQuadTex >= 0) glUniform1i(locQuadTex, 0);
                if (locQuadScreenSize >= 0) glUniform2i(locQuadScreenSize, width, height);
                if (locQuadPresentTest >= 0) glUniform1i(locQuadPresentTest, presentTest ? 1 : 0);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glEnable(GL_DEPTH_TEST);
                glEnable(GL_CULL_FACE);
            }

            if (now - lastPrint > 1.0) {
                int[] who = new int[1];
                org.lwjgl.opengl.GL46C.glGetIntegeri_v(org.lwjgl.opengl.GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, 0, who);
                System.out.println("SSBO@0=" + who[0] + " expected=" + ssboVoxels + " gradient=" + debugGradient + " gpuWorld=" + useGPUWorld);
                lastPrint = now;
            }

            if (now - fpsTimer >= 1.0) {
                double fps = fpsFrames / (now - fpsTimer);
                glfwSetWindowTitle(window, String.format("Voxel RT - %.1f FPS", fps));
                fpsFrames = 0;
                fpsTimer = now;
            }

            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    private void pollInput(float dt) {
        float speed = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS ? 12f : 6f;
        Vector3f f = camera.getForward();
        Vector3f r = new Vector3f(f).cross(0, 1, 0).normalize();
        Vector3f u = new Vector3f(0, 1, 0);
        Vector3f wish = new Vector3f();
        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) wish.z -= 1;
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) wish.z += 1;
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) wish.x -= 1;
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) wish.x += 1;
        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) wish.y += 1;
        if (glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS) wish.y -= 1;
        Vector3f vel = new Vector3f();
        vel.fma(wish.z, f).fma(wish.x, r).fma(wish.y, u);
        if (vel.lengthSquared() > 0) vel.normalize(speed);
        Physics.collideAABB(chunkManager, camera.position, vel, 0.6f, 1.8f, dt);
    }

    private void updatePrefetch() {
        if (region == null || chunkManager == null) {
            return;
        }
        Vector3f position = camera.position;
        float dx = position.x - lastPrefetchPosition.x;
        float dz = position.z - lastPrefetchPosition.z;
        float horizontalLenSq = dx * dx + dz * dz;
        if (horizontalLenSq < 1e-4f) {
            lastPrefetchPosition.set(position);
            return;
        }

        float invLen = (float) (1.0 / java.lang.Math.sqrt(horizontalLenSq));
        float dirX = dx * invLen;
        float dirZ = dz * invLen;

        float localX = position.x - region.originX;
        float localZ = position.z - region.originZ;
        int marginX = java.lang.Math.min(PREFETCH_MARGIN, region.rx / 2);
        int marginZ = java.lang.Math.min(PREFETCH_MARGIN, region.rz / 2);

        if (dirX > PREFETCH_DIRECTION_THRESHOLD && localX > region.rx - marginX) {
            prefetchEast();
        } else if (dirX < -PREFETCH_DIRECTION_THRESHOLD && localX < marginX) {
            prefetchWest();
        }

        if (dirZ > PREFETCH_DIRECTION_THRESHOLD && localZ > region.rz - marginZ) {
            prefetchSouth();
        } else if (dirZ < -PREFETCH_DIRECTION_THRESHOLD && localZ < marginZ) {
            prefetchNorth();
        }

        lastPrefetchPosition.set(position);
    }

    private void resetPrefetchBounds() {
        if (region == null) {
            return;
        }
        prefetchedEast = java.lang.Math.floorDiv(region.originX + region.rx - 1, Chunk.SX);
        prefetchedWest = java.lang.Math.floorDiv(region.originX, Chunk.SX);
        prefetchedSouth = java.lang.Math.floorDiv(region.originZ + region.rz - 1, Chunk.SZ);
        prefetchedNorth = java.lang.Math.floorDiv(region.originZ, Chunk.SZ);
    }

    private void prefetchEast() {
        int minChunkZ = java.lang.Math.floorDiv(region.originZ, Chunk.SZ);
        int maxChunkZ = java.lang.Math.floorDiv(region.originZ + region.rz - 1, Chunk.SZ);
        int desired = java.lang.Math.floorDiv(region.originX + region.rx - 1, Chunk.SX) + PREFETCH_LOOKAHEAD_CHUNKS;
        if (prefetchedEast >= desired) {
            return;
        }
        for (int chunkX = prefetchedEast + 1; chunkX <= desired; chunkX++) {
            requestColumn(chunkX, minChunkZ, maxChunkZ);
        }
        prefetchedEast = desired;
    }

    private void prefetchWest() {
        int minChunkZ = java.lang.Math.floorDiv(region.originZ, Chunk.SZ);
        int maxChunkZ = java.lang.Math.floorDiv(region.originZ + region.rz - 1, Chunk.SZ);
        int desired = java.lang.Math.floorDiv(region.originX, Chunk.SX) - PREFETCH_LOOKAHEAD_CHUNKS;
        if (prefetchedWest <= desired) {
            return;
        }
        for (int chunkX = prefetchedWest - 1; chunkX >= desired; chunkX--) {
            requestColumn(chunkX, minChunkZ, maxChunkZ);
        }
        prefetchedWest = desired;
    }

    private void prefetchSouth() {
        int minChunkX = java.lang.Math.floorDiv(region.originX, Chunk.SX);
        int maxChunkX = java.lang.Math.floorDiv(region.originX + region.rx - 1, Chunk.SX);
        int desired = java.lang.Math.floorDiv(region.originZ + region.rz - 1, Chunk.SZ) + PREFETCH_LOOKAHEAD_CHUNKS;
        if (prefetchedSouth >= desired) {
            return;
        }
        for (int chunkZ = prefetchedSouth + 1; chunkZ <= desired; chunkZ++) {
            requestRow(chunkZ, minChunkX, maxChunkX);
        }
        prefetchedSouth = desired;
    }

    private void prefetchNorth() {
        int minChunkX = java.lang.Math.floorDiv(region.originX, Chunk.SX);
        int maxChunkX = java.lang.Math.floorDiv(region.originX + region.rx - 1, Chunk.SX);
        int desired = java.lang.Math.floorDiv(region.originZ, Chunk.SZ) - PREFETCH_LOOKAHEAD_CHUNKS;
        if (prefetchedNorth <= desired) {
            return;
        }
        for (int chunkZ = prefetchedNorth - 1; chunkZ >= desired; chunkZ--) {
            requestRow(chunkZ, minChunkX, maxChunkX);
        }
        prefetchedNorth = desired;
    }

    private void requestColumn(int chunkX, int minChunkZ, int maxChunkZ) {
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            chunkManager.requestChunk(new ChunkPos(chunkX, chunkZ));
        }
    }

    private void requestRow(int chunkZ, int minChunkX, int maxChunkX) {
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            chunkManager.requestChunk(new ChunkPos(chunkX, chunkZ));
        }
    }

    private float angularDifference(float current, float previous) {
        if (Float.isNaN(previous)) {
            return Float.POSITIVE_INFINITY;
        }
        float diff = current - previous;
        diff = diff % 360f;
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;
        return (float) java.lang.Math.abs(diff);
    }

    private Frustum buildFrustum(Matrix4f proj, Matrix4f view) {
        return Frustum.fromMatrix(new Matrix4f(proj).mul(view));
    }

    private void uploadMatrix(int location, Matrix4f m) {
        if (location < 0) return;
        try (MemoryStack stack = stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            m.get(fb);
            glUniformMatrix4fv(location, false, fb);
        }
    }

    /**
     * Releases OpenGL resources and destroys the GLFW window.
     */
    private void cleanup() {
        glDeleteProgram(computeProgram);
        glDeleteProgram(quadProgram);
        glDeleteProgram(meshProgram);
        glDeleteTextures(outputTex);
        glDeleteVertexArrays(vaoQuad);
        if (chunkManager != null) {
            for (Chunk chunk : chunkManager.snapshotLoadedChunks()) {
                chunk.releaseMesh();
            }
            chunkManager.shutdown();
        }
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
