package com.example.voxelrt.app;

import com.example.voxelrt.app.config.EngineConfig;
import com.example.voxelrt.camera.Camera;
import com.example.voxelrt.camera.Frustum;
import com.example.voxelrt.camera.Raycast;
import com.example.voxelrt.mesh.ChunkBatcher;
import com.example.voxelrt.mesh.ChunkMesh;
import com.example.voxelrt.mesh.MeshBuilder;
import com.example.voxelrt.physics.PhysicsDebugDrawer;
import com.example.voxelrt.physics.PhysicsSystem;
import com.example.voxelrt.physics.VoxelDebrisRenderer;
import com.example.voxelrt.render.DebugRenderer;
import com.example.voxelrt.render.DynamicLight;
import com.example.voxelrt.render.LightManager;
import com.example.voxelrt.render.LightPropagationVolume;
import com.example.voxelrt.render.shader.ShaderSourceLoader;
import com.example.voxelrt.util.Profiler;
import com.example.voxelrt.world.ActiveRegion;
import com.example.voxelrt.world.Blocks;
import com.example.voxelrt.world.Chunk;
import com.example.voxelrt.world.ChunkManager;
import com.example.voxelrt.world.ChunkPos;
import com.example.voxelrt.world.Physics;
import com.example.voxelrt.world.WorldGenerator;
import com.example.voxelrt.world.WorldStorage;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

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

    private int computeProgram, quadProgram, meshProgram, debrisProgram;
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
    private int locComputeWorldSizeFar = -1;
    private int locComputeRegionOrigin = -1;
    private int locComputeVoxelScale = -1;
    private int locComputeLodScale = -1;
    private int locComputeLodScaleFar = -1;
    private int locComputeLodSwitchDistance = -1;
    private int locComputeLodSwitchDistanceFar = -1;
    private int locComputeLodTransitionBand = -1;
    private int locComputeCamPos = -1;
    private int locComputeSunDir = -1;
    private int locComputeResolution = -1;
    private int locComputeDebugGradient = -1;
    private int locComputeUseGPUWorld = -1;
    private int locComputeGIEnabled = -1;
    private int locComputeGISampleCount = -1;
    private int locComputeGIMaxDistance = -1;
    private int locComputeGIIntensity = -1;
    private int locComputeSecondaryTraceMaxSteps = -1;
    private int locComputeAOEnabled = -1;
    private int locComputeAOSampleCount = -1;
    private int locComputeAORadius = -1;
    private int locComputeAOIntensity = -1;
    private int locComputeReflectionEnabled = -1;
    private int locComputeReflectionMaxDistance = -1;
    private int locComputeReflectionIntensity = -1;
    private int locComputeLightCount = -1;
    private int locComputeLightSoftSamples = -1;
    private int locComputeShadowTraceMaxSteps = -1;
    private int locComputeShadowOccupancyScale = -1;
    private final int[] locComputeLightPositions = new int[MAX_DYNAMIC_LIGHTS];
    private final int[] locComputeLightColors = new int[MAX_DYNAMIC_LIGHTS];
    private final int[] locComputeLightRadii = new int[MAX_DYNAMIC_LIGHTS];
    private int locComputeGIVolumeEnabled = -1;
    private int locComputeGIVolumeTex = -1;
    private int locComputeGIVolumeOrigin = -1;
    private int locComputeGIVolumeSize = -1;
    private int locComputeGIVolumeCellSize = -1;
    private int locQuadTex = -1;
    private int locQuadScreenSize = -1;
    private int locQuadPresentTest = -1;
    private int locMeshProj = -1;
    private int locMeshView = -1;
    private int locMeshModel = -1;
    private int locMeshSunDir = -1;
    private int locDebrisProj = -1;
    private int locDebrisView = -1;
    private int locDebrisSunDir = -1;
    private int outputTex, vaoQuad;
    private int ssboVoxels;
    private int ssboVoxelsCoarse;
    private int ssboVoxelsFar;
    private ChunkBatcher chunkBatcher;
    private VoxelDebrisRenderer debrisRenderer;
    private DebugRenderer debugRenderer;
    private LightPropagationVolume giVolume;
    private int giVolumeTexture = 0;

    private Camera camera = new Camera(new Vector3f(64, 120, 64));
    private boolean mouseCaptured = true;
    private double lastMouseX = Double.NaN, lastMouseY = Double.NaN;

    private WorldGenerator generator;
    private WorldStorage worldStorage;
    private ChunkManager chunkManager;
    private ActiveRegion region;
    private static final int[] BLOCK_PALETTE = {
            Blocks.GRASS,
            Blocks.DIRT,
            Blocks.STONE,
            Blocks.SAND,
            Blocks.SNOW,
            Blocks.LOG,
            Blocks.LEAVES,
            Blocks.CACTUS
    };
    private int blockPaletteIndex = 0;
    private int placeBlock = BLOCK_PALETTE[0];
    private PhysicsSystem physicsSystem;
    private PhysicsDebugDrawer physicsDebugDrawer;

    private boolean debugGradient = false;
    private boolean presentTest = false;
    private boolean computeEnabled = true;
    private boolean rasterEnabled = false;
    private boolean useGPUWorld = false; // start with GPU fallback visible
    private boolean editorMode = false;
    private boolean showChunkBounds = false;
    private boolean showPhysicsDebug = false;
    private boolean showLightingDebug = false;
    private float lodSwitchDistance = 72.0f;
    private float lodSwitchDistanceFar = 160.0f;
    private float lodTransitionBand = 12.0f;
    private boolean lodCameraInitialized = false;
    private final Vector3f lastLodCameraPos = new Vector3f();
    private float lastRegionYaw = Float.NaN;
    private float lastRegionPitch = Float.NaN;
    private static final float REGION_REBUILD_ANGLE_THRESHOLD = 5f;
    private static final int PREFETCH_LOOKAHEAD_CHUNKS = 2;
    private static final float PREFETCH_DIRECTION_THRESHOLD = 0.15f;
    private static final int PREFETCH_MARGIN = 48;
    private static final int REGION_PREFETCH_MARGIN_CHUNKS = PREFETCH_LOOKAHEAD_CHUNKS + 1;
    private static final int MAX_DYNAMIC_LIGHTS = 8;
    private final Vector3f lastPrefetchPosition = new Vector3f();
    private int prefetchedEast = Integer.MIN_VALUE;
    private int prefetchedWest = Integer.MAX_VALUE;
    private int prefetchedSouth = Integer.MIN_VALUE;
    private int prefetchedNorth = Integer.MAX_VALUE;
    private int chunkIntegrationBudget = 8;
    private int viewDistanceChunks = 8;
    private int streamingRequestRadiusChunks = viewDistanceChunks + REGION_PREFETCH_MARGIN_CHUNKS;
    private int unloadDistanceChunks = streamingRequestRadiusChunks + 1;
    private int activeRegionMargin = 24;
    private int streamingCenterChunkX;
    private int streamingCenterChunkZ;

    private final LightManager lightManager = new LightManager();
    private final java.util.ArrayList<DynamicLight> activeLightsScratch = new java.util.ArrayList<>(MAX_DYNAMIC_LIGHTS);
    private DynamicLight playerTorch;
    private DynamicLight debugLightA;
    private DynamicLight debugLightB;
    private boolean debugLightsEnabled = true;
    private boolean enableGI = true;
    private int giSampleCount = 1;
    private float giMaxDistance = 28f;
    private float giIntensity = 0.45f;
    private boolean enableAO = true;
    private int aoSampleCount = 2;
    private float aoRadius = 3.0f;
    private float aoIntensity = 1.0f;
    private boolean enableReflections = true;
    private float reflectionMaxDistance = 96f;
    private float reflectionIntensity = 0.6f;
    private int secondaryTraceMaxSteps = 96;
    private int shadowTraceMaxSteps = 96;
    private int shadowOccupancyScale = 2;
    private int dynamicLightSoftSamples = 2;
    private int sunSoftSamples = 2;

    private final ShaderSourceLoader shaderSourceLoader = new ShaderSourceLoader();
    private final Profiler profiler = new Profiler();
    private Profiler.Snapshot profilerSnapshot = Profiler.Snapshot.EMPTY;
    private int profilerLevel = 0;
    private double profilerLastStatsUpdate = 0.0;
    private int profilerLoadedChunks = 0;
    private int profilerVisibleChunks = 0;
    private int profilerPendingChunks = 0;
    private int profilerCompletedChunks = 0;
    private int profilerChunkPool = 0;
    private int profilerMaxLoadedChunks = 0;
    private int profilerDynamicBodies = 0;
    private int profilerDebrisCount = 0;
    private int profilerStaticBodyCount = 0;
    private int profilerActiveLights = 0;
    private long profilerUsedMemoryBytes = 0L;
    private long profilerTotalMemoryBytes = 0L;
    private long profilerMaxMemoryBytes = 0L;

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

    public LightManager lightManager() {
        return lightManager;
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
                if (key == GLFW_KEY_F1) {
                    editorMode = !editorMode;
                    if (editorMode && !mouseCaptured) {
                        mouseCaptured = true;
                        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                    }
                    System.out.println("[DEBUG] editorMode=" + editorMode);
                }
                if (key == GLFW_KEY_F2) {
                    showChunkBounds = !showChunkBounds;
                    System.out.println("[DEBUG] chunkBounds=" + showChunkBounds);
                }
                if (key == GLFW_KEY_F3) {
                    if (physicsSystem != null && physicsDebugDrawer != null) {
                        showPhysicsDebug = !showPhysicsDebug;
                        int mode = showPhysicsDebug
                                ? PhysicsDebugDrawer.MODE_WIREFRAME_AABB
                                : PhysicsDebugDrawer.MODE_NONE;
                        physicsDebugDrawer.setDebugMode(mode);
                        System.out.println("[DEBUG] physicsDebug=" + showPhysicsDebug);
                    } else {
                        System.out.println("[DEBUG] physicsDebug unavailable");
                    }
                }
                if (key == GLFW_KEY_F4) {
                    showLightingDebug = !showLightingDebug;
                    System.out.println("[DEBUG] lightingDebug=" + showLightingDebug);
                }
                if (key == GLFW_KEY_F5) {
                    profilerLevel = (profilerLevel + 1) % 3;
                    System.out.println("[DEBUG] profilerOverlayLevel=" + profilerLevel);
                }
                if (key == GLFW_KEY_F9) {
                    updateProfilerStats();
                    profilerLastStatsUpdate = glfwGetTime();
                    logProfilerSnapshot();
                }
                if (key == GLFW_KEY_E) {
                    cyclePalette(1);
                }
                if (key >= GLFW_KEY_1 && key <= GLFW_KEY_8) {
                    selectPaletteIndex(key - GLFW_KEY_1);
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
                    ssboVoxels = region.ssbo();
                    ssboVoxelsCoarse = region.ssboCoarse();
                    ssboVoxelsFar = region.ssboFar();
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboVoxels);
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssboVoxelsCoarse);
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ssboVoxelsFar);
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
                if (key == GLFW_KEY_F6) {
                    enableGI = !enableGI;
                    System.out.println("[DEBUG] giEnabled=" + enableGI);
                }
                if (key == GLFW_KEY_F7) {
                    enableReflections = !enableReflections;
                    System.out.println("[DEBUG] reflections=" + enableReflections);
                }
                if (key == GLFW_KEY_F8) {
                    debugLightsEnabled = !debugLightsEnabled;
                    if (debugLightA != null) debugLightA.setEnabled(debugLightsEnabled);
                    if (debugLightB != null) debugLightB.setEnabled(debugLightsEnabled);
                    System.out.println("[DEBUG] dynamicLights=" + debugLightsEnabled);
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
                    int previous = chunkManager.sample(hit.x, hit.y, hit.z);
                    if (previous != Blocks.AIR) {
                        chunkManager.setEdit(hit.x, hit.y, hit.z, Blocks.AIR);
                        region.setVoxelWorld(hit.x, hit.y, hit.z, Blocks.AIR);
                        if (physicsSystem != null) {
                            Vector3f impulse = new Vector3f(dir).mul(2.4f);
                            impulse.y += 1.1f;
                            physicsSystem.onVoxelEdited(hit.x, hit.y, hit.z, previous, Blocks.AIR, impulse);
                        }
                    }
                } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                    int px = hit.x + hit.nx, py = hit.y + hit.ny, pz = hit.z + hit.nz;
                    int previous = chunkManager.sample(px, py, pz);
                    if (previous != placeBlock) {
                        chunkManager.setEdit(px, py, pz, placeBlock);
                        region.setVoxelWorld(px, py, pz, placeBlock);
                        if (physicsSystem != null) {
                            physicsSystem.onVoxelEdited(px, py, pz, previous, placeBlock, null);
                        }
                    }
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
        computeProgram = linkProgram(compileShader(GL_COMPUTE_SHADER, shaderSourceLoader.load("shaders/voxel/voxel.comp")));
        quadProgram = linkProgram(
                compileShader(GL_VERTEX_SHADER, shaderSourceLoader.load("shaders/quad.vert")),
                compileShader(GL_FRAGMENT_SHADER, shaderSourceLoader.load("shaders/quad.frag"))
        );
        meshProgram = linkProgram(
                compileShader(GL_VERTEX_SHADER, shaderSourceLoader.load("shaders/chunk.vert")),
                compileShader(GL_FRAGMENT_SHADER, shaderSourceLoader.load("shaders/chunk.frag"))
        );
        debrisProgram = linkProgram(
                compileShader(GL_VERTEX_SHADER, shaderSourceLoader.load("shaders/debris.vert")),
                compileShader(GL_FRAGMENT_SHADER, shaderSourceLoader.load("shaders/debris.frag"))
        );
        cacheComputeUniformLocations();
        cacheQuadUniformLocations();
        cacheMeshUniformLocations();
        cacheDebrisUniformLocations();
        chunkBatcher = new ChunkBatcher();
        debrisRenderer = new VoxelDebrisRenderer();
        debugRenderer = new DebugRenderer(shaderSourceLoader::load);
        initDynamicLights();

        vaoQuad = glGenVertexArrays();
        createOutputTexture();

        generator = new WorldGenerator(1337L, 62);
        EngineConfig config = EngineConfig.load();
        viewDistanceChunks = config.viewDistanceChunks();
        streamingRequestRadiusChunks = viewDistanceChunks + REGION_PREFETCH_MARGIN_CHUNKS;
        unloadDistanceChunks = streamingRequestRadiusChunks + 1;
        worldStorage = new WorldStorage(config.worldDirectory());
        chunkManager = new ChunkManager(generator, config.chunkCacheSize(), worldStorage);
        chunkIntegrationBudget = config.chunkIntegrationBudget();
        System.out.println("[Engine] Chunk integration budget set to " + chunkIntegrationBudget + " per frame");
        System.out.println("[Engine] Chunk cache capacity set to " + chunkManager.getMaxLoaded() + " chunks");

        // Spawn above ground
        int spawnX = (int) Math.floor(camera.position.x);
        int spawnZ = (int) Math.floor(camera.position.z);
        int topY = findTopSolidY(chunkManager, spawnX, spawnZ);
        camera.position.set(spawnX + 0.5f, topY + 2.5f, spawnZ + 0.5f);

        int regionSizeXZ = config.activeRegionSizeXZ();
        int regionSizeY = config.activeRegionHeight();
        region = new ActiveRegion(chunkManager, regionSizeXZ, regionSizeY, regionSizeXZ);
        activeRegionMargin = config.activeRegionMargin();
        giVolume = new LightPropagationVolume(4);
        int rw = Math.max(1, (int) (width * resolutionScale));
        int rh = Math.max(1, (int) (height * resolutionScale));
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(75.0), (float) rw / rh, 0.1f, 800.0f);
        Matrix4f view = camera.viewMatrix();
        Frustum frustum = buildFrustum(proj, view);
        streamingCenterChunkX = java.lang.Math.floorDiv((int) Math.floor(camera.position.x), Chunk.SX);
        streamingCenterChunkZ = java.lang.Math.floorDiv((int) Math.floor(camera.position.z), Chunk.SZ);
        region.rebuildAround((int) Math.floor(camera.position.x),
                (int) Math.floor(camera.position.y),
                (int) Math.floor(camera.position.z),
                frustum,
                new ChunkPos(streamingCenterChunkX, streamingCenterChunkZ),
                streamingRequestRadiusChunks);
        lastRegionYaw = camera.yawDeg();
        lastRegionPitch = camera.pitchDeg();
        ssboVoxels = region.ssbo();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboVoxels);
        ssboVoxelsCoarse = region.ssboCoarse();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssboVoxelsCoarse);
        ssboVoxelsFar = region.ssboFar();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ssboVoxelsFar);
        physicsSystem = new PhysicsSystem(chunkManager, region);
        physicsDebugDrawer = new PhysicsDebugDrawer();
        physicsSystem.world().setDebugDrawer(physicsDebugDrawer);
        physicsDebugDrawer.setDebugMode(PhysicsDebugDrawer.MODE_NONE);
        resetPrefetchBounds();
        lastPrefetchPosition.set(camera.position);
        prefetchActiveRegionPadding();
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
        locComputeWorldSizeFar = glGetUniformLocation(computeProgram, "uWorldSizeFar");
        locComputeRegionOrigin = glGetUniformLocation(computeProgram, "uRegionOrigin");
        locComputeVoxelScale = glGetUniformLocation(computeProgram, "uVoxelScale");
        locComputeLodScale = glGetUniformLocation(computeProgram, "uLodScale");
        locComputeLodScaleFar = glGetUniformLocation(computeProgram, "uLodScaleFar");
        locComputeLodSwitchDistance = glGetUniformLocation(computeProgram, "uLodSwitchDistance");
        locComputeLodSwitchDistanceFar = glGetUniformLocation(computeProgram, "uLodSwitchDistanceFar");
        locComputeLodTransitionBand = glGetUniformLocation(computeProgram, "uLodTransitionBand");
        locComputeCamPos = glGetUniformLocation(computeProgram, "uCamPos");
        locComputeSunDir = glGetUniformLocation(computeProgram, "uSunDir");
        locComputeResolution = glGetUniformLocation(computeProgram, "uResolution");
        locComputeDebugGradient = glGetUniformLocation(computeProgram, "uDebugGradient");
        locComputeUseGPUWorld = glGetUniformLocation(computeProgram, "uUseGPUWorld");
        locComputeGIEnabled = glGetUniformLocation(computeProgram, "uGIEnabled");
        locComputeGISampleCount = glGetUniformLocation(computeProgram, "uGISampleCount");
        locComputeGIMaxDistance = glGetUniformLocation(computeProgram, "uGIMaxDistance");
        locComputeGIIntensity = glGetUniformLocation(computeProgram, "uGIIntensity");
        locComputeSecondaryTraceMaxSteps = glGetUniformLocation(computeProgram, "uSecondaryTraceMaxSteps");
        locComputeAOEnabled = glGetUniformLocation(computeProgram, "uAOEnabled");
        locComputeAOSampleCount = glGetUniformLocation(computeProgram, "uAOSampleCount");
        locComputeAORadius = glGetUniformLocation(computeProgram, "uAORadius");
        locComputeAOIntensity = glGetUniformLocation(computeProgram, "uAOIntensity");
        locComputeReflectionEnabled = glGetUniformLocation(computeProgram, "uReflectionEnabled");
        locComputeReflectionMaxDistance = glGetUniformLocation(computeProgram, "uReflectionMaxDistance");
        locComputeReflectionIntensity = glGetUniformLocation(computeProgram, "uReflectionIntensity");
        locComputeLightCount = glGetUniformLocation(computeProgram, "uLightCount");
        locComputeLightSoftSamples = glGetUniformLocation(computeProgram, "uLightSoftSamples");
        locComputeShadowTraceMaxSteps = glGetUniformLocation(computeProgram, "uShadowTraceMaxSteps");
        locComputeShadowOccupancyScale = glGetUniformLocation(computeProgram, "uShadowOccupancyScale");
        locComputeGIVolumeEnabled = glGetUniformLocation(computeProgram, "uGIVolumeEnabled");
        locComputeGIVolumeTex = glGetUniformLocation(computeProgram, "uGIVolume");
        locComputeGIVolumeOrigin = glGetUniformLocation(computeProgram, "uGIVolumeOrigin");
        locComputeGIVolumeSize = glGetUniformLocation(computeProgram, "uGIVolumeSize");
        locComputeGIVolumeCellSize = glGetUniformLocation(computeProgram, "uGIVolumeCellSize");
        for (int i = 0; i < MAX_DYNAMIC_LIGHTS; i++) {
            locComputeLightPositions[i] = glGetUniformLocation(computeProgram, "uLightPositions[" + i + "]");
            locComputeLightColors[i] = glGetUniformLocation(computeProgram, "uLightColors[" + i + "]");
            locComputeLightRadii[i] = glGetUniformLocation(computeProgram, "uLightRadii[" + i + "]");
        }
    }

    private void cacheQuadUniformLocations() {
        locQuadTex = glGetUniformLocation(quadProgram, "uTex");
        locQuadScreenSize = glGetUniformLocation(quadProgram, "uScreenSize");
        locQuadPresentTest = glGetUniformLocation(quadProgram, "uPresentTest");
    }

    private void cacheMeshUniformLocations() {
        locMeshProj = glGetUniformLocation(meshProgram, "uProj");
        locMeshView = glGetUniformLocation(meshProgram, "uView");
        locMeshModel = glGetUniformLocation(meshProgram, "uModel");
        locMeshSunDir = glGetUniformLocation(meshProgram, "uSunDir");
    }

    private void cacheDebrisUniformLocations() {
        locDebrisProj = glGetUniformLocation(debrisProgram, "uProj");
        locDebrisView = glGetUniformLocation(debrisProgram, "uView");
        locDebrisSunDir = glGetUniformLocation(debrisProgram, "uSunDir");
    }

    private void initDynamicLights() {
        lightManager.clear();

        playerTorch = new DynamicLight()
                .setColor(1.0f, 0.72f, 0.45f)
                .setIntensity(30f)
                .setRadius(0.18f)
                .setRange(28f)
                .setPosition(camera.position);
        lightManager.addLight(playerTorch);

        debugLightA = new DynamicLight()
                .setColor(1.0f, 0.72f, 0.45f)
                .setIntensity(20f)
                .setRadius(0.35f)
                .setRange(36f)
                .setEnabled(debugLightsEnabled);
        lightManager.addLight(debugLightA);

        debugLightB = new DynamicLight()
                .setColor(0.4f, 0.65f, 1.0f)
                .setIntensity(16f)
                .setRadius(0.45f)
                .setRange(40f)
                .setEnabled(debugLightsEnabled);
        lightManager.addLight(debugLightB);
    }

    private void updateDynamicLights(double now, float dt) {
        if (playerTorch != null) {
            playerTorch.setPosition(camera.position);
        }

        float time = (float) now;
        if (debugLightA != null) {
            debugLightA.setEnabled(debugLightsEnabled);
            if (debugLightsEnabled) {
                debugLightA.setPosition(
                        camera.position.x + (float) java.lang.Math.cos(time * 0.65f) * 4.0f,
                        camera.position.y + 2.5f,
                        camera.position.z + (float) java.lang.Math.sin(time * 0.65f) * 4.0f
                );
            }
        }
        if (debugLightB != null) {
            debugLightB.setEnabled(debugLightsEnabled);
            if (debugLightsEnabled) {
                debugLightB.setPosition(
                        camera.position.x + (float) java.lang.Math.cos(time * 0.85f + java.lang.Math.PI * 0.5f) * 3.2f,
                        camera.position.y + 1.5f + (float) java.lang.Math.sin(time * 0.45f) * 0.75f,
                        camera.position.z + (float) java.lang.Math.sin(time * 0.85f + java.lang.Math.PI * 0.5f) * 3.2f
                );
            }
        }

        lightManager.update(dt);
    }

    private void uploadDynamicLights() {
        int count = activeLightsScratch.size();
        if (locComputeLightCount >= 0) {
            glUniform1i(locComputeLightCount, count);
        }
        if (locComputeLightSoftSamples >= 0) {
            glUniform1i(locComputeLightSoftSamples, dynamicLightSoftSamples);
        }
        for (int i = 0; i < MAX_DYNAMIC_LIGHTS; i++) {
            float px = 0f, py = 0f, pz = 0f, range = 0f;
            float radius = 0f;
            float r = 0f, g = 0f, b = 0f, intensity = 0f;
            if (i < count) {
                DynamicLight light = activeLightsScratch.get(i);
                px = light.position().x;
                py = light.position().y;
                pz = light.position().z;
                range = light.range();
                radius = light.radius();
                r = light.color().x;
                g = light.color().y;
                b = light.color().z;
                intensity = light.intensity();
            }
            if (locComputeLightPositions[i] >= 0) {
                glUniform4f(locComputeLightPositions[i], px, py, pz, range);
            }
            if (locComputeLightColors[i] >= 0) {
                glUniform4f(locComputeLightColors[i], r, g, b, intensity);
            }
            if (locComputeLightRadii[i] >= 0) {
                glUniform1f(locComputeLightRadii[i], radius);
            }
        }
    }

    private void updateGlobalIlluminationVolume(Vector3f sunDir) {
        if (!enableGI || giVolume == null || region == null) {
            return;
        }
        giVolume.rebuild(region, sunDir, activeLightsScratch);
        if (!giVolume.isDirty()) {
            return;
        }
        ensureGiVolumeTexture();
        int sx = giVolume.sizeX();
        int sy = giVolume.sizeY();
        int sz = giVolume.sizeZ();
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            giVolume.clearDirtyFlag();
            return;
        }
        glBindTexture(GL_TEXTURE_3D, giVolumeTexture);
        FloatBuffer buffer = giVolume.uploadBuffer();
        if (buffer != null) {
            glTexImage3D(GL_TEXTURE_3D, 0, GL_RGB16F, sx, sy, sz, 0, GL_RGB, GL_FLOAT, buffer);
            glGenerateMipmap(GL_TEXTURE_3D);
        }
        glBindTexture(GL_TEXTURE_3D, 0);
        giVolume.clearDirtyFlag();
    }

    private void ensureGiVolumeTexture() {
        if (giVolumeTexture != 0) {
            return;
        }
        giVolumeTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_3D, giVolumeTexture);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_3D, 0);
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
            if (physicsSystem != null) {
                physicsSystem.updateStaticChunkCollider(chunk, data);
            }
            ChunkMesh old = chunk.mesh();
            ChunkMesh nextMesh = null;
            if (data.instanceCount() > 0) {
                nextMesh = ChunkMesh.create(data.instanceData(), data.instanceCount());
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
        Matrix4f identityModel = new Matrix4f();
        uploadMatrix(locMeshModel, identityModel);
        Vector3f sunDir = new Vector3f(-0.6f, -1.0f, -0.3f).normalize();
        if (locMeshSunDir >= 0) {
            glUniform3f(locMeshSunDir, sunDir.x, sunDir.y, sunDir.z);
        }
        java.util.ArrayList<ChunkMesh> drawList = new java.util.ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            ChunkMesh mesh = chunk.mesh();
            if (mesh != null && mesh.instanceCount() > 0) {
                drawList.add(mesh);
            }
        }
        if (!drawList.isEmpty() && chunkBatcher != null) {
            chunkBatcher.drawBatched(drawList);
        }
        if (physicsSystem != null) {
            java.util.List<PhysicsSystem.DynamicBodyInstance> bodies = physicsSystem.collectDynamicBodies();
            if (!bodies.isEmpty()) {
                for (PhysicsSystem.DynamicBodyInstance body : bodies) {
                    uploadMatrix(locMeshModel, body.transform());
                    ChunkMesh mesh = body.mesh();
                    if (mesh != null) {
                        mesh.draw();
                    }
                }
                uploadMatrix(locMeshModel, identityModel);
            }
        }
        glUseProgram(0);
    }

    private void renderDebris(Matrix4f proj, Matrix4f view) {
        if (physicsSystem == null || debrisRenderer == null) {
            return;
        }
        java.util.List<PhysicsSystem.DebrisInstance> instances = physicsSystem.collectDebrisInstances();
        if (instances.isEmpty()) {
            return;
        }
        glViewport(0, 0, width, height);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(debrisProgram);
        uploadMatrix(locDebrisProj, proj);
        uploadMatrix(locDebrisView, view);
        Vector3f sunDir = new Vector3f(-0.6f, -1.0f, -0.3f).normalize();
        if (locDebrisSunDir >= 0) {
            glUniform3f(locDebrisSunDir, sunDir.x, sunDir.y, sunDir.z);
        }
        debrisRenderer.drawInstances(instances);
        glUseProgram(0);
        glDisable(GL_BLEND);
    }

    private void renderDebugOverlays(Matrix4f proj, Matrix4f view, java.util.List<Chunk> visibleChunks) {
        if (debugRenderer == null) {
            return;
        }
        debugRenderer.beginFrame();

        Raycast.Hit hit = null;
        if (chunkManager != null) {
            hit = Raycast.raycast(chunkManager, new Vector3f(camera.position), camera.getForward(), 12f);
        }
        if (hit != null) {
            debugRenderer.addVoxelOutline(hit.x, hit.y, hit.z, 1.0f, 0.86f, 0.35f, 1.0f);
            if (editorMode && (hit.nx != 0 || hit.ny != 0 || hit.nz != 0)) {
                int px = hit.x + hit.nx;
                int py = hit.y + hit.ny;
                int pz = hit.z + hit.nz;
                debugRenderer.addVoxelOutline(px, py, pz, 0.4f, 1.0f, 0.4f, 0.7f);
            }
        }

        if (showChunkBounds && visibleChunks != null) {
            for (Chunk chunk : visibleChunks) {
                if (chunk == null) {
                    continue;
                }
                ChunkPos pos = chunk.pos();
                if (pos == null) {
                    continue;
                }
                float minX = pos.cx() * Chunk.SX;
                float minZ = pos.cz() * Chunk.SZ;
                float maxX = minX + Chunk.SX;
                float maxZ = minZ + Chunk.SZ;
                debugRenderer.addWireBox(minX, 0f, minZ, maxX, Chunk.SY, maxZ, 0.25f, 0.7f, 1.0f, 0.4f);
            }
        }

        if (showLightingDebug && !activeLightsScratch.isEmpty()) {
            for (DynamicLight light : activeLightsScratch) {
                if (light == null || light.range() <= 0f && light.radius() <= 0f) {
                    continue;
                }
                float radius = light.range() > 0f ? light.range() : light.radius();
                Vector3f pos = light.position();
                Vector3f color = light.color();
                debugRenderer.addWireBox(pos.x - radius, pos.y - radius, pos.z - radius,
                        pos.x + radius, pos.y + radius, pos.z + radius,
                        color.x(), color.y(), color.z(), 0.45f);
            }
        }

        if (showPhysicsDebug && physicsSystem != null && physicsDebugDrawer != null) {
            physicsDebugDrawer.beginFrame();
            physicsSystem.world().debugDrawWorld();
            physicsDebugDrawer.flush(debugRenderer);
        }

        float hudX = 18f;
        float hudY = 28f;
        float hudScale = 1f;
        String editorLabel = "F1 Editor Mode: " + (editorMode ? "ON" : "OFF");
        float editorR = editorMode ? 1.0f : 0.85f;
        float editorG = editorMode ? 0.88f : 0.85f;
        float editorB = editorMode ? 0.32f : 0.85f;
        debugRenderer.addText(hudX, hudY, hudScale, editorLabel, editorR, editorG, editorB, 1f);
        hudY += 14f;

        if (editorMode) {
            debugRenderer.addText(hudX, hudY, hudScale,
                    "Left click remove | Right click place | ESC unlock cursor",
                    0.85f, 0.85f, 0.85f, 1f);
            hudY += 14f;
        }

        Vector3f camPos = camera.position;
        debugRenderer.addText(hudX, hudY, hudScale,
                String.format("Position: %.2f, %.2f, %.2f", camPos.x, camPos.y, camPos.z),
                0.9f, 0.9f, 0.9f, 1f);
        hudY += 14f;

        if (region != null) {
            int chunkX = java.lang.Math.floorDiv((int) java.lang.Math.floor(camPos.x), Chunk.SX);
            int chunkZ = java.lang.Math.floorDiv((int) java.lang.Math.floor(camPos.z), Chunk.SZ);
            debugRenderer.addText(hudX, hudY, hudScale,
                    String.format("Chunk: %d, %d | Region origin: %d, %d, %d",
                            chunkX, chunkZ, region.originX, region.originY, region.originZ),
                    0.82f, 0.82f, 0.82f, 1f);
            hudY += 14f;
        }

        if (hit != null) {
            int blockId = chunkManager != null ? chunkManager.sample(hit.x, hit.y, hit.z) : Blocks.AIR;
            debugRenderer.addText(hudX, hudY, hudScale,
                    String.format("Target: %d, %d, %d [%s]", hit.x, hit.y, hit.z, Blocks.name(blockId)),
                    0.95f, 0.85f, 0.55f, 1f);
            hudY += 14f;
            if (editorMode && (hit.nx != 0 || hit.ny != 0 || hit.nz != 0)) {
                int px = hit.x + hit.nx;
                int py = hit.y + hit.ny;
                int pz = hit.z + hit.nz;
                debugRenderer.addText(hudX, hudY, hudScale,
                        String.format("Placement: %d, %d, %d [%s]", px, py, pz, Blocks.name(placeBlock)),
                        0.75f, 0.95f, 0.75f, 1f);
                hudY += 14f;
            }
        }

        debugRenderer.addText(hudX, hudY, hudScale,
                String.format("Current block: %s (E / 1-8)", Blocks.name(placeBlock)),
                0.9f, 0.9f, 1.0f, 1f);
        hudY += 14f;

        if (editorMode) {
            for (int i = 0; i < BLOCK_PALETTE.length; i++) {
                boolean selected = i == blockPaletteIndex;
                float r = selected ? 0.45f : 0.7f;
                float g = selected ? 1.0f : 0.7f;
                float b = selected ? 0.45f : 0.7f;
                debugRenderer.addText(hudX + 14f, hudY, hudScale,
                        String.format("%d) %s%s", i + 1, Blocks.name(BLOCK_PALETTE[i]), selected ? " <-" : ""),
                        r, g, b, 1f);
                hudY += 12f;
            }
        }

        if (profilerLevel > 0) {
            Profiler.Snapshot snapshot = profilerSnapshot;
            debugRenderer.addText(hudX, hudY, hudScale,
                    String.format("Frame: %.2f ms (avg %.2f) | FPS: %.1f (avg %.1f)",
                            snapshot.frameTimeMs(), snapshot.averageFrameTimeMs(),
                            snapshot.fps(), snapshot.averageFps()),
                    0.75f, 0.95f, 1.0f, 1f);
            hudY += 14f;

            debugRenderer.addText(hudX, hudY, hudScale,
                    String.format("Memory: %s / %s MB (max %s MB)",
                            formatMegabytes(profilerUsedMemoryBytes),
                            formatMegabytes(profilerTotalMemoryBytes),
                            formatMegabytes(profilerMaxMemoryBytes)),
                    0.78f, 0.9f, 1.0f, 1f);
            hudY += 14f;

            debugRenderer.addText(hudX, hudY, hudScale,
                    String.format("Chunks: %d/%d loaded | visible: %d | pending: %d | completed: %d | pool: %d",
                            profilerLoadedChunks, profilerMaxLoadedChunks, profilerVisibleChunks,
                            profilerPendingChunks, profilerCompletedChunks, profilerChunkPool),
                    0.75f, 0.85f, 1.0f, 1f);
            hudY += 14f;

            debugRenderer.addText(hudX, hudY, hudScale,
                    String.format("Physics: %d dynamic | %d static | debris: %d",
                            profilerDynamicBodies, profilerStaticBodyCount, profilerDebrisCount),
                    0.85f, 0.78f, 1.0f, 1f);
            hudY += 14f;

            if (profilerLevel > 1 && !snapshot.sections().isEmpty()) {
                debugRenderer.addText(hudX, hudY, hudScale, "Timing (ms):",
                        0.7f, 0.9f, 1.0f, 1f);
                hudY += 14f;
                for (Profiler.SectionSnapshot section : snapshot.sections()) {
                    debugRenderer.addText(hudX + 14f, hudY, hudScale,
                            String.format("%s: %.3f (avg %.3f)", section.name(), section.lastMs(), section.averageMs()),
                            0.68f, 0.85f, 1.0f, 1f);
                    hudY += 12f;
                }
            }
        }

        String chunkToggle = showChunkBounds ? "ON" : "OFF";
        String physicsToggle = physicsSystem != null ? (showPhysicsDebug ? "ON" : "OFF") : "N/A";
        String lightToggle = showLightingDebug ? "ON" : "OFF";
        String heatmapToggle = debugGradient ? "ON" : "OFF";
        debugRenderer.addText(hudX, hudY, hudScale,
                String.format("F2 Chunk bounds: %s | F3 Physics: %s | F4 Lights: %s | G Heatmap: %s",
                        chunkToggle, physicsToggle, lightToggle, heatmapToggle),
                0.8f, 0.8f, 0.8f, 1f);
        hudY += 14f;

        debugRenderer.addText(hudX, hudY, hudScale,
                String.format("Active lights: %d", profilerActiveLights),
                0.8f, 0.8f, 0.95f, 1f);

        debugRenderer.renderLines(proj, view);
        debugRenderer.renderText(width, height);
    }

    private java.util.List<Chunk> filterVisibleChunks(java.util.List<Chunk> chunks, Frustum frustum) {
        if (chunks.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        java.util.ArrayList<Chunk> visible = new java.util.ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            if (chunkVisible(chunk, frustum)) {
                visible.add(chunk);
            }
        }
        if (visible.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return visible;
    }

    private boolean chunkVisible(Chunk chunk, Frustum frustum) {
        if (frustum == null) {
            return true;
        }
        ChunkPos pos = chunk.pos();
        if (pos == null) {
            return false;
        }
        float minX = pos.cx() * Chunk.SX;
        float minZ = pos.cz() * Chunk.SZ;
        float minY = 0f;
        float maxX = minX + Chunk.SX;
        float maxZ = minZ + Chunk.SZ;
        float maxY = Chunk.SY;
        return frustum.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ);
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
            profiler.beginFrame();
            double now = glfwGetTime();
            float dt = (float) (now - lastTime);
            lastTime = now;
            fpsFrames++;

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            pollInput(dt);
            updateLodDistances(dt);

            try (Profiler.Sample ignored = profileSection("Chunk Update", 2)) {
                chunkManager.update(chunkIntegrationBudget);
            }
            boolean loadedNewChunks = chunkManager.drainIntegratedFlag();

            int cx = (int) Math.floor(camera.position.x);
            int cy = (int) Math.floor(camera.position.y);
            int cz = (int) Math.floor(camera.position.z);
            int playerChunkX = java.lang.Math.floorDiv(cx, Chunk.SX);
            int playerChunkZ = java.lang.Math.floorDiv(cz, Chunk.SZ);
            streamingCenterChunkX = playerChunkX;
            streamingCenterChunkZ = playerChunkZ;
            chunkManager.unloadOutsideRadius(new ChunkPos(playerChunkX, playerChunkZ), unloadDistanceChunks);

            java.util.List<Chunk> loadedChunks = chunkManager.snapshotLoadedChunks();
            if (physicsSystem != null) {
                physicsSystem.pruneStaticChunks(loadedChunks);
            }
            try (Profiler.Sample ignored = profileSection("Mesh Rebuild", 2)) {
                rebuildChunkMeshes(loadedChunks);
            }

            int margin = activeRegionMargin;
            int rw = Math.max(1, (int) (width * resolutionScale));
            int rh = Math.max(1, (int) (height * resolutionScale));
            Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(75.0), (float) rw / rh, 0.1f, 800.0f);
            Matrix4f view = camera.viewMatrix();
            Matrix4f invProj = new Matrix4f(proj).invert();
            Matrix4f invView = new Matrix4f(view).invert();
            Frustum frustum = buildFrustum(proj, view);

            float yawDiff = angularDifference(camera.yawDeg(), lastRegionYaw);
            float pitchDiff = Float.isNaN(lastRegionPitch) ? Float.POSITIVE_INFINITY : (float) java.lang.Math.abs(camera.pitchDeg() - lastRegionPitch);
            boolean rotatedSignificantly = yawDiff > REGION_REBUILD_ANGLE_THRESHOLD || pitchDiff > REGION_REBUILD_ANGLE_THRESHOLD;
            boolean needsRegionRebuild = loadedNewChunks ||
                    cx < region.originX + margin || cz < region.originZ + margin ||
                    cx > region.originX + region.rx - margin || cz > region.originZ + region.rz - margin ||
                    cy < region.originY + margin || cy > region.originY + region.ry - margin ||
                    rotatedSignificantly;

            if (needsRegionRebuild) {
                try (Profiler.Sample ignored = profileSection("Region Rebuild", 2)) {
                    region.rebuildAround(cx, cy, cz, frustum, new ChunkPos(streamingCenterChunkX, streamingCenterChunkZ), streamingRequestRadiusChunks);
                    ssboVoxels = region.ssbo();
                    ssboVoxelsCoarse = region.ssboCoarse();
                    ssboVoxelsFar = region.ssboFar();
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboVoxels);
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssboVoxelsCoarse);
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ssboVoxelsFar);
                    lastRegionYaw = camera.yawDeg();
                    lastRegionPitch = camera.pitchDeg();
                    resetPrefetchBounds();
                    lastPrefetchPosition.set(camera.position);
                    prefetchActiveRegionPadding();
                }
            }

            updatePrefetch();

            java.util.List<Chunk> visibleChunks = filterVisibleChunks(loadedChunks, frustum);

            try (Profiler.Sample ignored = profileSection("Light Update", 2)) {
                updateDynamicLights(now, dt);
                lightManager.gatherActiveLights(camera.position, MAX_DYNAMIC_LIGHTS, activeLightsScratch);
            }
            profilerActiveLights = activeLightsScratch.size();

            Vector3f sunDir = new Vector3f(-0.6f, -1.0f, -0.3f).normalize();
            try (Profiler.Sample ignored = profileSection("GI Update", 2)) {
                updateGlobalIlluminationVolume(sunDir);
            }

            if (physicsSystem != null) {
                try (Profiler.Sample ignored = profileSection("Physics", 2)) {
                    physicsSystem.stepSimulation(dt);
                }
            }

            // Compute pass
            if (!rasterEnabled && computeEnabled) {
                try (Profiler.Sample ignored = profileSection("Compute Render", 2)) {
                    glUseProgram(computeProgram);
                    if (locComputeSkyModel >= 0) glUniform1i(locComputeSkyModel, 1);         // Preetham
                    if (locComputeTurbidity >= 0) glUniform1f(locComputeTurbidity, 2.5f);    // mild clear sky
                    if (locComputeSkyIntensity >= 0) glUniform1f(locComputeSkyIntensity, 1.0f);
                    if (locComputeSkyZenith >= 0)
                        glUniform3f(locComputeSkyZenith, 0.60f, 0.70f, 0.90f);   // fallback gradient
                    if (locComputeSkyHorizon >= 0) glUniform3f(locComputeSkyHorizon, 0.95f, 0.80f, 0.60f);
                    if (locComputeSunAngularRadius >= 0) glUniform1f(locComputeSunAngularRadius, 0.00465f);    // ~0.266°
                    if (locComputeSunSoftSamples >= 0) glUniform1i(locComputeSunSoftSamples, sunSoftSamples);

                    if (locComputeTorchEnabled >= 0) glUniform1i(locComputeTorchEnabled, 0);
                    if (locComputeTorchPos >= 0) glUniform3f(locComputeTorchPos, 0f, 0f, 0f);
                    if (locComputeTorchIntensity >= 0) glUniform1f(locComputeTorchIntensity, 0.0f);
                    if (locComputeTorchRadius >= 0) glUniform1f(locComputeTorchRadius, 0.0f);
                    if (locComputeTorchSoftSamples >= 0) glUniform1i(locComputeTorchSoftSamples, 0);

                    if (locComputeGIEnabled >= 0) glUniform1i(locComputeGIEnabled, enableGI ? 1 : 0);
                    if (locComputeGISampleCount >= 0) glUniform1i(locComputeGISampleCount, giSampleCount);
                    if (locComputeGIMaxDistance >= 0) glUniform1f(locComputeGIMaxDistance, giMaxDistance);
                    if (locComputeGIIntensity >= 0) glUniform1f(locComputeGIIntensity, giIntensity);
                    boolean giVolumeAvailable = enableGI && giVolumeTexture != 0 && giVolume != null
                            && giVolume.sizeX() > 0 && giVolume.sizeY() > 0 && giVolume.sizeZ() > 0;
                    if (locComputeGIVolumeEnabled >= 0) {
                        glUniform1i(locComputeGIVolumeEnabled, giVolumeAvailable ? 1 : 0);
                    }
                    if (locComputeGIVolumeTex >= 0) {
                        glUniform1i(locComputeGIVolumeTex, 3);
                    }
                    if (giVolumeAvailable) {
                        if (locComputeGIVolumeOrigin >= 0) {
                            Vector3fc origin = giVolume.origin();
                            glUniform3f(locComputeGIVolumeOrigin, origin.x(), origin.y(), origin.z());
                        }
                        if (locComputeGIVolumeSize >= 0) {
                            glUniform3i(locComputeGIVolumeSize, giVolume.sizeX(), giVolume.sizeY(), giVolume.sizeZ());
                        }
                        if (locComputeGIVolumeCellSize >= 0) {
                            glUniform1f(locComputeGIVolumeCellSize, giVolume.cellSize());
                        }
                        glActiveTexture(GL_TEXTURE3);
                        glBindTexture(GL_TEXTURE_3D, giVolumeTexture);
                    } else {
                        glActiveTexture(GL_TEXTURE3);
                        glBindTexture(GL_TEXTURE_3D, 0);
                    }
                    if (locComputeSecondaryTraceMaxSteps >= 0)
                        glUniform1i(locComputeSecondaryTraceMaxSteps, secondaryTraceMaxSteps);
                    if (locComputeAOEnabled >= 0) glUniform1i(locComputeAOEnabled, enableAO ? 1 : 0);
                    if (locComputeAOSampleCount >= 0) glUniform1i(locComputeAOSampleCount, aoSampleCount);
                    if (locComputeAORadius >= 0) glUniform1f(locComputeAORadius, aoRadius);
                    if (locComputeAOIntensity >= 0) glUniform1f(locComputeAOIntensity, aoIntensity);
                    if (locComputeReflectionEnabled >= 0)
                        glUniform1i(locComputeReflectionEnabled, enableReflections ? 1 : 0);
                    if (locComputeReflectionMaxDistance >= 0)
                        glUniform1f(locComputeReflectionMaxDistance, reflectionMaxDistance);
                    if (locComputeReflectionIntensity >= 0)
                        glUniform1f(locComputeReflectionIntensity, reflectionIntensity);
                    if (locComputeShadowTraceMaxSteps >= 0)
                        glUniform1i(locComputeShadowTraceMaxSteps, shadowTraceMaxSteps);
                    if (locComputeShadowOccupancyScale >= 0)
                        glUniform1i(locComputeShadowOccupancyScale, shadowOccupancyScale);
                    uploadDynamicLights();

                    glBindImageTexture(0, outputTex, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);

                    uploadMatrix(locComputeInvProj, invProj);
                    uploadMatrix(locComputeInvView, invView);

                    if (locComputeWorldSize >= 0) glUniform3i(locComputeWorldSize, region.rx, region.ry, region.rz);
                    if (locComputeWorldSizeCoarse >= 0)
                        glUniform3i(locComputeWorldSizeCoarse, region.rxCoarse(), region.ryCoarse(), region.rzCoarse());
                    if (locComputeWorldSizeFar >= 0)
                        glUniform3i(locComputeWorldSizeFar, region.rxFar(), region.ryFar(), region.rzFar());
                if (locComputeRegionOrigin >= 0)
                    glUniform3i(locComputeRegionOrigin, region.originX, region.originY, region.originZ);
                if (locComputeVoxelScale >= 0) glUniform1f(locComputeVoxelScale, 1.0f);
                if (locComputeLodScale >= 0) glUniform1f(locComputeLodScale, region.lodScale());
                if (locComputeLodScaleFar >= 0) glUniform1f(locComputeLodScaleFar, region.lodScaleFar());
                if (locComputeLodSwitchDistance >= 0) glUniform1f(locComputeLodSwitchDistance, lodSwitchDistance);
                if (locComputeLodSwitchDistanceFar >= 0)
                    glUniform1f(locComputeLodSwitchDistanceFar, lodSwitchDistanceFar);
                if (locComputeLodTransitionBand >= 0)
                    glUniform1f(locComputeLodTransitionBand, lodTransitionBand);
                if (locComputeCamPos >= 0)
                    glUniform3f(locComputeCamPos, camera.position.x, camera.position.y, camera.position.z);
                if (locComputeSunDir >= 0) glUniform3f(locComputeSunDir, sunDir.x, sunDir.y, sunDir.z);
                if (locComputeResolution >= 0) glUniform2i(locComputeResolution, rw, rh);
                if (locComputeDebugGradient >= 0) glUniform1i(locComputeDebugGradient, debugGradient ? 1 : 0);
                if (locComputeUseGPUWorld >= 0) glUniform1i(locComputeUseGPUWorld, useGPUWorld ? 1 : 0);

                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboVoxels);
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssboVoxelsCoarse);
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ssboVoxelsFar);
                    int gx = (rw + 15) / 16, gy = (rh + 15) / 16;
                    glDispatchCompute(gx, gy, 1);
                    glActiveTexture(GL_TEXTURE3);
                    glBindTexture(GL_TEXTURE_3D, 0);
                    glActiveTexture(GL_TEXTURE0);
                    glUseProgram(0);
                    glMemoryBarrier(GL_ALL_BARRIER_BITS);
                }
            }

            // Present
            if (rasterEnabled) {
                try (Profiler.Sample ignored = profileSection("Chunk Raster", 2)) {
                    renderChunkMeshes(proj, view, visibleChunks);
                }
                try (Profiler.Sample ignored = profileSection("Debris Render", 2)) {
                    renderDebris(proj, view);
                }
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
                try (Profiler.Sample ignored = profileSection("Debris Render", 2)) {
                    renderDebris(proj, view);
                }
            }

            profilerVisibleChunks = visibleChunks != null ? visibleChunks.size() : 0;
            profilerLoadedChunks = loadedChunks.size();
            try (Profiler.Sample ignored = profileSection("Debug Overlay", 2)) {
                renderDebugOverlays(proj, view, visibleChunks);
            }

            if (now - lastPrint > 1.0) {
                int[] who = new int[1];
                org.lwjgl.opengl.GL46C.glGetIntegeri_v(org.lwjgl.opengl.GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, 0, who);
                int bound0 = who[0];
                org.lwjgl.opengl.GL46C.glGetIntegeri_v(org.lwjgl.opengl.GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, 1, who);
                int bound1 = who[0];
                org.lwjgl.opengl.GL46C.glGetIntegeri_v(org.lwjgl.opengl.GL46C.GL_SHADER_STORAGE_BUFFER_BINDING, 2, who);
                int bound2 = who[0];
                System.out.println("SSBO@0=" + bound0 + " @1=" + bound1 + " @2=" + bound2
                        + " expected0=" + ssboVoxels
                        + " expected1=" + ssboVoxelsCoarse
                        + " expected2=" + ssboVoxelsFar
                        + " gradient=" + debugGradient + " gpuWorld=" + useGPUWorld);
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
            profiler.endFrame();

            double statsNow = glfwGetTime();
            if (statsNow - profilerLastStatsUpdate >= 1.0) {
                updateProfilerStats();
                profilerLastStatsUpdate = statsNow;
            }
        }
    }

    private Profiler.Sample profileSection(String name, int requiredLevel) {
        return profilerLevel >= requiredLevel ? profiler.sample(name) : Profiler.Sample.noop();
    }

    private void updateProfilerStats() {
        profilerSnapshot = profiler.snapshot();
        if (chunkManager != null) {
            profilerPendingChunks = chunkManager.pendingGenerationCount();
            profilerCompletedChunks = chunkManager.completedGenerationCount();
            profilerChunkPool = chunkManager.chunkPoolSize();
            profilerMaxLoadedChunks = chunkManager.maxLoadedChunks();
        } else {
            profilerPendingChunks = 0;
            profilerCompletedChunks = 0;
            profilerChunkPool = 0;
            profilerMaxLoadedChunks = 0;
        }
        if (physicsSystem != null) {
            profilerDynamicBodies = physicsSystem.dynamicBodyCount();
            profilerDebrisCount = physicsSystem.debrisCount();
            profilerStaticBodyCount = physicsSystem.staticChunkBodyCount();
        } else {
            profilerDynamicBodies = 0;
            profilerDebrisCount = 0;
            profilerStaticBodyCount = 0;
        }
        Runtime runtime = Runtime.getRuntime();
        profilerTotalMemoryBytes = runtime.totalMemory();
        profilerMaxMemoryBytes = runtime.maxMemory();
        profilerUsedMemoryBytes = profilerTotalMemoryBytes - runtime.freeMemory();
    }

    private static String formatMegabytes(long bytes) {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }

    private void logProfilerSnapshot() {
        Profiler.Snapshot snapshot = profiler.snapshot();
        System.out.printf("[Profiler] Frame %.2f ms (avg %.2f) | FPS %.1f (avg %.1f)%n",
                snapshot.frameTimeMs(), snapshot.averageFrameTimeMs(),
                snapshot.fps(), snapshot.averageFps());
        System.out.printf("[Profiler] Memory %s / %s MB (max %s MB)%n",
                formatMegabytes(profilerUsedMemoryBytes),
                formatMegabytes(profilerTotalMemoryBytes),
                formatMegabytes(profilerMaxMemoryBytes));
        System.out.printf("[Profiler] Chunks loaded=%d/%d visible=%d pending=%d completed=%d pool=%d%n",
                profilerLoadedChunks, profilerMaxLoadedChunks, profilerVisibleChunks,
                profilerPendingChunks, profilerCompletedChunks, profilerChunkPool);
        System.out.printf("[Profiler] Physics dynamic=%d static=%d debris=%d%n",
                profilerDynamicBodies, profilerStaticBodyCount, profilerDebrisCount);
        if (!snapshot.sections().isEmpty()) {
            System.out.println("[Profiler] Sections:");
            for (Profiler.SectionSnapshot section : snapshot.sections()) {
                System.out.printf("[Profiler]  %s: %.3f ms (avg %.3f ms)%n",
                        section.name(), section.lastMs(), section.averageMs());
            }
        }
    }

    private void pollInput(float dt) {
        boolean sprint = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS;
        float speed = editorMode ? 12f : 6f;
        if (sprint) {
            speed *= editorMode ? 2.0f : 2.0f;
        }
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
        if (editorMode) {
            camera.position.fma(dt, vel);
        } else {
            Physics.collideAABB(chunkManager, camera.position, vel, 0.6f, 1.8f, dt);
        }
    }

    private void cyclePalette(int delta) {
        if (BLOCK_PALETTE.length == 0) {
            return;
        }
        blockPaletteIndex = java.lang.Math.floorMod(blockPaletteIndex + delta, BLOCK_PALETTE.length);
        placeBlock = BLOCK_PALETTE[blockPaletteIndex];
    }

    private void selectPaletteIndex(int index) {
        if (index < 0 || index >= BLOCK_PALETTE.length) {
            return;
        }
        blockPaletteIndex = index;
        placeBlock = BLOCK_PALETTE[blockPaletteIndex];
    }

    private void updateLodDistances(float dt) {
        if (camera == null) {
            return;
        }
        if (!lodCameraInitialized) {
            lastLodCameraPos.set(camera.position);
            lodCameraInitialized = true;
            return;
        }
        if (dt <= 0f) {
            return;
        }

        float dx = camera.position.x - lastLodCameraPos.x;
        float dy = camera.position.y - lastLodCameraPos.y;
        float dz = camera.position.z - lastLodCameraPos.z;
        float distance = (float) java.lang.Math.sqrt(dx * dx + dy * dy + dz * dz);
        float speed = distance / dt;

        float desiredNear = 64.0f + speed * 1.5f;
        float desiredFar = 140.0f + speed * 2.5f;
        desiredNear = (float) java.lang.Math.max(48.0f, java.lang.Math.min(120.0f, desiredNear));
        desiredFar = (float) java.lang.Math.max(120.0f, java.lang.Math.min(260.0f, desiredFar));

        float alpha = 1.0f - (float) java.lang.Math.exp(-dt * 4.0f);
        lodSwitchDistance = lerp(lodSwitchDistance, desiredNear, alpha);
        lodSwitchDistanceFar = lerp(lodSwitchDistanceFar, desiredFar, alpha);

        float minFar = lodSwitchDistance + lodTransitionBand * 2.0f + 8.0f;
        if (lodSwitchDistanceFar < minFar) {
            lodSwitchDistanceFar = minFar;
        }

        lastLodCameraPos.set(camera.position);
    }

    private float lerp(float a, float b, float t) {
        return a + (b - a) * t;
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
        clampPrefetchBoundsToRadius();
    }

    private void clampPrefetchBoundsToRadius() {
        int minX = streamingCenterChunkX - streamingRequestRadiusChunks;
        int maxX = streamingCenterChunkX + streamingRequestRadiusChunks;
        int minZ = streamingCenterChunkZ - streamingRequestRadiusChunks;
        int maxZ = streamingCenterChunkZ + streamingRequestRadiusChunks;
        prefetchedEast = java.lang.Math.min(prefetchedEast, maxX);
        prefetchedWest = java.lang.Math.max(prefetchedWest, minX);
        prefetchedSouth = java.lang.Math.min(prefetchedSouth, maxZ);
        prefetchedNorth = java.lang.Math.max(prefetchedNorth, minZ);
    }

    private void prefetchActiveRegionPadding() {
        if (region == null || chunkManager == null) {
            return;
        }
        int minChunkX = java.lang.Math.floorDiv(region.originX, Chunk.SX) - REGION_PREFETCH_MARGIN_CHUNKS;
        int maxChunkX = java.lang.Math.floorDiv(region.originX + region.rx - 1, Chunk.SX) + REGION_PREFETCH_MARGIN_CHUNKS;
        int minChunkZ = java.lang.Math.floorDiv(region.originZ, Chunk.SZ) - REGION_PREFETCH_MARGIN_CHUNKS;
        int maxChunkZ = java.lang.Math.floorDiv(region.originZ + region.rz - 1, Chunk.SZ) + REGION_PREFETCH_MARGIN_CHUNKS;
        int minAllowedX = streamingCenterChunkX - streamingRequestRadiusChunks;
        int maxAllowedX = streamingCenterChunkX + streamingRequestRadiusChunks;
        int minAllowedZ = streamingCenterChunkZ - streamingRequestRadiusChunks;
        int maxAllowedZ = streamingCenterChunkZ + streamingRequestRadiusChunks;
        minChunkX = java.lang.Math.max(minChunkX, minAllowedX);
        maxChunkX = java.lang.Math.min(maxChunkX, maxAllowedX);
        minChunkZ = java.lang.Math.max(minChunkZ, minAllowedZ);
        maxChunkZ = java.lang.Math.min(maxChunkZ, maxAllowedZ);
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            clampPrefetchBoundsToRadius();
            return;
        }
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            requestColumn(chunkX, minChunkZ, maxChunkZ);
        }
        prefetchedEast = java.lang.Math.max(prefetchedEast, maxChunkX);
        prefetchedWest = java.lang.Math.min(prefetchedWest, minChunkX);
        prefetchedSouth = java.lang.Math.max(prefetchedSouth, maxChunkZ);
        prefetchedNorth = java.lang.Math.min(prefetchedNorth, minChunkZ);
        clampPrefetchBoundsToRadius();
    }

    private void prefetchEast() {
        int minChunkZ = java.lang.Math.floorDiv(region.originZ, Chunk.SZ);
        int maxChunkZ = java.lang.Math.floorDiv(region.originZ + region.rz - 1, Chunk.SZ);
        int desired = java.lang.Math.floorDiv(region.originX + region.rx - 1, Chunk.SX) + PREFETCH_LOOKAHEAD_CHUNKS;
        int maxAllowed = streamingCenterChunkX + streamingRequestRadiusChunks;
        desired = java.lang.Math.min(desired, maxAllowed);
        if (prefetchedEast >= desired) {
            return;
        }
        for (int chunkX = prefetchedEast + 1; chunkX <= desired; chunkX++) {
            requestColumn(chunkX, minChunkZ, maxChunkZ);
        }
        prefetchedEast = desired;
        clampPrefetchBoundsToRadius();
    }

    private void prefetchWest() {
        int minChunkZ = java.lang.Math.floorDiv(region.originZ, Chunk.SZ);
        int maxChunkZ = java.lang.Math.floorDiv(region.originZ + region.rz - 1, Chunk.SZ);
        int desired = java.lang.Math.floorDiv(region.originX, Chunk.SX) - PREFETCH_LOOKAHEAD_CHUNKS;
        int minAllowed = streamingCenterChunkX - streamingRequestRadiusChunks;
        desired = java.lang.Math.max(desired, minAllowed);
        if (prefetchedWest <= desired) {
            return;
        }
        for (int chunkX = prefetchedWest - 1; chunkX >= desired; chunkX--) {
            requestColumn(chunkX, minChunkZ, maxChunkZ);
        }
        prefetchedWest = desired;
        clampPrefetchBoundsToRadius();
    }

    private void prefetchSouth() {
        int minChunkX = java.lang.Math.floorDiv(region.originX, Chunk.SX);
        int maxChunkX = java.lang.Math.floorDiv(region.originX + region.rx - 1, Chunk.SX);
        int desired = java.lang.Math.floorDiv(region.originZ + region.rz - 1, Chunk.SZ) + PREFETCH_LOOKAHEAD_CHUNKS;
        int maxAllowed = streamingCenterChunkZ + streamingRequestRadiusChunks;
        desired = java.lang.Math.min(desired, maxAllowed);
        if (prefetchedSouth >= desired) {
            return;
        }
        for (int chunkZ = prefetchedSouth + 1; chunkZ <= desired; chunkZ++) {
            requestRow(chunkZ, minChunkX, maxChunkX);
        }
        prefetchedSouth = desired;
        clampPrefetchBoundsToRadius();
    }

    private void prefetchNorth() {
        int minChunkX = java.lang.Math.floorDiv(region.originX, Chunk.SX);
        int maxChunkX = java.lang.Math.floorDiv(region.originX + region.rx - 1, Chunk.SX);
        int desired = java.lang.Math.floorDiv(region.originZ, Chunk.SZ) - PREFETCH_LOOKAHEAD_CHUNKS;
        int minAllowed = streamingCenterChunkZ - streamingRequestRadiusChunks;
        desired = java.lang.Math.max(desired, minAllowed);
        if (prefetchedNorth <= desired) {
            return;
        }
        for (int chunkZ = prefetchedNorth - 1; chunkZ >= desired; chunkZ--) {
            requestRow(chunkZ, minChunkX, maxChunkX);
        }
        prefetchedNorth = desired;
        clampPrefetchBoundsToRadius();
    }

    private void requestColumn(int chunkX, int minChunkZ, int maxChunkZ) {
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            if (!isWithinStreamingRadius(chunkX, chunkZ)) {
                continue;
            }
            chunkManager.requestChunk(new ChunkPos(chunkX, chunkZ));
        }
    }

    private void requestRow(int chunkZ, int minChunkX, int maxChunkX) {
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            if (!isWithinStreamingRadius(chunkX, chunkZ)) {
                continue;
            }
            chunkManager.requestChunk(new ChunkPos(chunkX, chunkZ));
        }
    }

    private boolean isWithinStreamingRadius(int chunkX, int chunkZ) {
        return java.lang.Math.max(java.lang.Math.abs(chunkX - streamingCenterChunkX),
                java.lang.Math.abs(chunkZ - streamingCenterChunkZ)) <= streamingRequestRadiusChunks;
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
        glDeleteProgram(debrisProgram);
        if (giVolumeTexture != 0) {
            glDeleteTextures(giVolumeTexture);
        }
        glDeleteTextures(outputTex);
        glDeleteVertexArrays(vaoQuad);
        if (chunkBatcher != null) {
            chunkBatcher.close();
        }
        if (debrisRenderer != null) {
            debrisRenderer.close();
        }
        if (debugRenderer != null) {
            debugRenderer.close();
        }
        if (physicsSystem != null) {
            physicsSystem.close();
        }
        if (chunkManager != null) {
            chunkManager.flushEdits();
            for (Chunk chunk : chunkManager.snapshotLoadedChunks()) {
                chunk.releaseMesh();
            }
            chunkManager.shutdown();
        }
        if (worldStorage != null) {
            worldStorage.close();
        }
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
