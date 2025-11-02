package com.example.voxelrt;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.joml.*;

import java.io.IOException;
import java.lang.Math;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Main application driver that owns the GLFW window, OpenGL resources and game loop.
 * <p>
 * The engine sets up LWJGL bindings, feeds camera input, manages compute shaders and keeps the CPU
 * and GPU representations of the world in sync.
 */
public class Engine {
    private long window;
    private int width=1280, height=720;
    private float resolutionScale=1.0f;

    private int computeProgram, quadProgram;
    private int outputTex, vaoQuad;
    private int ssboVoxels;

    private Camera camera = new Camera(new Vector3f(64, 120, 64));
    private boolean mouseCaptured = true;
    private double lastMouseX=Double.NaN, lastMouseY=Double.NaN;

    private WorldGenerator generator;
    private ChunkManager chunkManager;
    private ActiveRegion region;
    private int placeBlock = Blocks.GRASS;

    private boolean debugGradient=false;
    private boolean presentTest=false;
    private boolean computeEnabled=true;
    private boolean useGPUWorld=false; // start with GPU fallback visible

    /** Starts the engine and tears down the native resources when the loop exits. */
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
    private void initWindow(){
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        window = glfwCreateWindow(width, height, "Voxel RT ", NULL, NULL);
        if (window==NULL) throw new RuntimeException("Create window failed");
        GLFWVidMode vm = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vm.width()-width)/2, (vm.height()-height)/2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);

        glfwSetFramebufferSizeCallback(window, (win,w,h)->{
            width = Math.max(1,w); height=Math.max(1,h);
            recreateOutputTexture();
        });

        glfwSetCursorPosCallback(window, (win,mx,my)->{
            if(!mouseCaptured) return;
            if (Double.isNaN(lastMouseX)) { lastMouseX=mx; lastMouseY=my; }
            double dx=mx-lastMouseX, dy=my-lastMouseY;
            lastMouseX=mx; lastMouseY=my;
            camera.addYawPitch((float)dx, (float)dy);
        });

        glfwSetKeyCallback(window, (win,key,sc,action,mods)->{
            if (action==GLFW_PRESS){
                if (key==GLFW_KEY_ESCAPE){
                    mouseCaptured=!mouseCaptured;
                    glfwSetInputMode(window, GLFW_CURSOR, mouseCaptured?GLFW_CURSOR_DISABLED:GLFW_CURSOR_NORMAL);
                }
                if (key==GLFW_KEY_E){
                    placeBlock = switch (placeBlock){
                        case Blocks.GRASS -> Blocks.DIRT;
                        case Blocks.DIRT  -> Blocks.STONE;
                        case Blocks.STONE -> Blocks.SAND;
                        case Blocks.SAND  -> Blocks.SNOW;
                        default -> Blocks.GRASS;
                    };
                }
                if (key==GLFW_KEY_R){
                    region.rebuildAround((int)Math.floor(camera.position.x),
                                         (int)Math.floor(camera.position.y),
                                         (int)Math.floor(camera.position.z));
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboVoxels);
                }
                if (key==GLFW_KEY_G){ debugGradient = !debugGradient; System.out.println("[DEBUG] debugGradient="+debugGradient); }
                if (key==GLFW_KEY_P){ presentTest = !presentTest; System.out.println("[DEBUG] presentTest="+presentTest); }
                if (key==GLFW_KEY_C){ computeEnabled = !computeEnabled; System.out.println("[DEBUG] computeEnabled="+computeEnabled); }
                if (key==GLFW_KEY_H){ useGPUWorld = !useGPUWorld; System.out.println("[DEBUG] useGPUWorld="+useGPUWorld); }
                if (key==GLFW_KEY_KP_ADD || key==GLFW_KEY_EQUAL){ resolutionScale=Math.min(2.0f,resolutionScale+0.1f); recreateOutputTexture(); }
                if (key==GLFW_KEY_KP_SUBTRACT || key==GLFW_KEY_MINUS){ resolutionScale=Math.max(0.5f,resolutionScale-0.1f); recreateOutputTexture(); }
            }
        });

        glfwSetMouseButtonCallback(window, (win,button,action,mods)->{
            if (!mouseCaptured || action!=GLFW_PRESS) return;
            Vector3f origin=new Vector3f(camera.position);
            Vector3f dir=camera.getForward();
            Raycast.Hit hit = Raycast.raycast(chunkManager, origin, dir, 8f);
            if (hit!=null){
                if (button==GLFW_MOUSE_BUTTON_LEFT){
                    chunkManager.setEdit(hit.x,hit.y,hit.z, Blocks.AIR);
                    region.setVoxelWorld(hit.x,hit.y,hit.z, Blocks.AIR);
                } else if (button==GLFW_MOUSE_BUTTON_RIGHT){
                    int px=hit.x+hit.nx, py=hit.y+hit.ny, pz=hit.z+hit.nz;
                    chunkManager.setEdit(px,py,pz, placeBlock);
                    region.setVoxelWorld(px,py,pz, placeBlock);
                }
            }
        });

        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        if (glfwRawMouseMotionSupported()) glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
    }

    /** Performs basic OpenGL state setup after the GLFW context has been created. */
    private void initGL(){
        GL.createCapabilities();
        System.out.println("OpenGL: " + glGetString(GL_VERSION));
        System.out.println("GPU: " + glGetString(GL_RENDERER));
        glClearColor(0.12f,0.14f,0.18f,1.0f);
        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
    }

    /** Loads a text resource either from the classpath or directly from the resources directory. */
    private String loadResource(String path){
        try{
            var is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
            if (is!=null) return new String(is.readAllBytes());
            return Files.readString(Path.of("src/main/resources/"+path));
        }catch(IOException e){ throw new RuntimeException("load "+path, e); }
    }

    /** Compiles a shader of the given type and prints a detailed log when compilation fails. */
    private int compileShader(int type,String src){
        int id=glCreateShader(type); glShaderSource(id, src); glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS)!=GL_TRUE){
            String log = glGetShaderInfoLog(id);
            System.err.println("=== SHADER COMPILE FAILED ===");
            System.err.println(log);
            String[] lines = src.split("\r?\n", -1);
            for (int i=0;i<lines.length;i++){
                String n = String.format("%03d", i+1);
                System.err.println(n + ": " + lines[i]);
            }
            throw new RuntimeException("Shader compile error: "+log);
        }
        return id;
    }
    private int linkProgram(int... shaders){
        int prog=glCreateProgram();
        for(int s: shaders) glAttachShader(prog,s);
        glLinkProgram(prog);
        if (glGetProgrami(prog, GL_LINK_STATUS)!=GL_TRUE) throw new RuntimeException("Link error: "+glGetProgramInfoLog(prog));
        for(int s: shaders) glDeleteShader(s);
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
    private void initResources(){
        computeProgram = linkProgram(compileShader(GL_COMPUTE_SHADER, loadResource("shaders/voxel.comp")));
        quadProgram = linkProgram(
                compileShader(GL_VERTEX_SHADER,   loadResource("shaders/quad.vert")),
                compileShader(GL_FRAGMENT_SHADER, loadResource("shaders/quad.frag"))
        );

        vaoQuad = glGenVertexArrays();
        createOutputTexture();

        generator = new WorldGenerator(1337L, 62);
        chunkManager = new ChunkManager(generator, 256);

        // Spawn above ground
        int spawnX = (int) Math.floor(camera.position.x);
        int spawnZ = (int) Math.floor(camera.position.z);
        int topY = findTopSolidY(chunkManager, spawnX, spawnZ);
        camera.position.set(spawnX + 0.5f, topY + 2.5f, spawnZ + 0.5f);

        region = new ActiveRegion(chunkManager, 128, 128, 128);
        region.rebuildAround((int)Math.floor(camera.position.x),
                             (int)Math.floor(camera.position.y),
                             (int)Math.floor(camera.position.z));
        ssboVoxels = region.ssbo();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboVoxels);
    }

    private void createOutputTexture(){
        if (outputTex!=0) glDeleteTextures(outputTex);
        int rw = Math.max(1, (int)(width*resolutionScale));
        int rh = Math.max(1, (int)(height*resolutionScale));
        outputTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, outputTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, rw, rh, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer)null);
        glBindTexture(GL_TEXTURE_2D, 0);
    }
    private void recreateOutputTexture(){ createOutputTexture(); }

    /** Main render loop – processes input, updates camera movement and dispatches rendering. */
    private void loop(){
        double lastTime = glfwGetTime();
        double lastPrint = lastTime;
        double fpsTimer = lastTime;
        int fpsFrames = 0;
        while(!glfwWindowShouldClose(window)){
            double now=glfwGetTime(); float dt=(float)(now-lastTime); lastTime=now;
            fpsFrames++;

            glClear(GL_COLOR_BUFFER_BIT);
            pollInput(dt);

            int cx=(int)Math.floor(camera.position.x);
            int cy=(int)Math.floor(camera.position.y);
            int cz=(int)Math.floor(camera.position.z);
            int margin=24;
            if (cx < region.originX+margin || cz < region.originZ+margin ||
                cx > region.originX+region.rx-margin || cz > region.originZ+region.rz-margin ||
                cy < region.originY+margin || cy > region.originY+region.ry-margin){
                region.rebuildAround(cx,cy,cz);
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboVoxels);
            }

            // Compute pass
            if (computeEnabled){
                glUseProgram(computeProgram);
        // === Sky & soft-shadow defaults ===
        int locSkyModel = glGetUniformLocation(computeProgram, "uSkyModel");
        int locTurb     = glGetUniformLocation(computeProgram, "uTurbidity");
        int locSkyInt   = glGetUniformLocation(computeProgram, "uSkyIntensity");
        int locSkyZen   = glGetUniformLocation(computeProgram, "uSkyZenith");
        int locSkyHor   = glGetUniformLocation(computeProgram, "uSkyHorizon");
        int locSunAng   = glGetUniformLocation(computeProgram, "uSunAngularRadius");
        int locSunSamp  = glGetUniformLocation(computeProgram, "uSunSoftSamples");
        if (locSkyModel >= 0) glUniform1i(locSkyModel, 1);         // Preetham
        if (locTurb     >= 0) glUniform1f(locTurb, 2.5f);          // mild clear sky
        if (locSkyInt   >= 0) glUniform1f(locSkyInt, 1.0f);
        if (locSkyZen   >= 0) glUniform3f(locSkyZen, 0.60f, 0.70f, 0.90f);   // fallback gradient
        if (locSkyHor   >= 0) glUniform3f(locSkyHor, 0.95f, 0.80f, 0.60f);
        if (locSunAng   >= 0) glUniform1f(locSunAng, 0.00465f);    // ~0.266° in radians
        if (locSunSamp  >= 0) glUniform1i(locSunSamp, 8);

        // === Torch defaults (player-held) ===
        int locTorchEn  = glGetUniformLocation(computeProgram, "uTorchEnabled");
        int locTorchPos = glGetUniformLocation(computeProgram, "uTorchPos");
        int locTorchI   = glGetUniformLocation(computeProgram, "uTorchIntensity");
        int locTorchR   = glGetUniformLocation(computeProgram, "uTorchRadius");
        int locTorchS   = glGetUniformLocation(computeProgram, "uTorchSoftSamples");
        if (locTorchEn  >= 0) glUniform1i(locTorchEn, 1);
        if (locTorchPos >= 0) glUniform3f(locTorchPos, camera.position.x, camera.position.y, camera.position.z);
        if (locTorchI   >= 0) glUniform1f(locTorchI, 30.0f);
        if (locTorchR   >= 0) glUniform1f(locTorchR, 0.15f);
        if (locTorchS   >= 0) glUniform1i(locTorchS, 8);

                int rw = Math.max(1, (int)(width*resolutionScale));
                int rh = Math.max(1, (int)(height*resolutionScale));
                glBindImageTexture(0, outputTex, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);

                Matrix4f proj = new Matrix4f().perspective((float)Math.toRadians(75.0), (float)rw/rh, 0.1f, 800.0f);
                Matrix4f invProj = new Matrix4f(proj).invert();
                Matrix4f invView = new Matrix4f(camera.viewMatrix()).invert();
                uploadMatrix(computeProgram, "uInvProj", invProj);
                uploadMatrix(computeProgram, "uInvView", invView);

                glUniform3i(glGetUniformLocation(computeProgram, "uWorldSize"), region.rx, region.ry, region.rz);
                glUniform3i(glGetUniformLocation(computeProgram, "uRegionOrigin"), region.originX, region.originY, region.originZ);
                glUniform1f(glGetUniformLocation(computeProgram, "uVoxelScale"), 1.0f);
                glUniform3f(glGetUniformLocation(computeProgram, "uCamPos"), camera.position.x, camera.position.y, camera.position.z);
                Vector3f sunDir = new Vector3f(-0.6f,-1.0f,-0.3f).normalize();
                glUniform3f(glGetUniformLocation(computeProgram, "uSunDir"), sunDir.x, sunDir.y, sunDir.z);
                glUniform2i(glGetUniformLocation(computeProgram, "uResolution"), rw, rh);
                glUniform1i(glGetUniformLocation(computeProgram, "uDebugGradient"), debugGradient ? 1 : 0);
                glUniform1i(glGetUniformLocation(computeProgram, "uUseGPUWorld"), useGPUWorld ? 1 : 0);

                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboVoxels);
                int gx=(rw+15)/16, gy=(rh+15)/16;
                glDispatchCompute(gx,gy,1);
                glUseProgram(0);
                glMemoryBarrier(GL_ALL_BARRIER_BITS);
            }

            // Present
            glViewport(0,0,width,height);
            glUseProgram(quadProgram);
            glBindVertexArray(vaoQuad);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, outputTex);
            glUniform1i(glGetUniformLocation(quadProgram, "uTex"), 0);
            glUniform2i(glGetUniformLocation(quadProgram, "uScreenSize"), width, height);
            glUniform1i(glGetUniformLocation(quadProgram, "uPresentTest"), presentTest ? 1 : 0);
            glDrawArrays(GL_TRIANGLES, 0, 3);

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

    private void pollInput(float dt){
        float speed = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT)==GLFW_PRESS ? 12f : 6f;
        Vector3f f = camera.getForward();
        Vector3f r = new Vector3f(f).cross(0,1,0).normalize();
        Vector3f u = new Vector3f(0,1,0);
        Vector3f wish = new Vector3f();
        if (glfwGetKey(window, GLFW_KEY_W)==GLFW_PRESS) wish.z -= 1;
        if (glfwGetKey(window, GLFW_KEY_S)==GLFW_PRESS) wish.z += 1;
        if (glfwGetKey(window, GLFW_KEY_A)==GLFW_PRESS) wish.x -= 1;
        if (glfwGetKey(window, GLFW_KEY_D)==GLFW_PRESS) wish.x += 1;
        if (glfwGetKey(window, GLFW_KEY_SPACE)==GLFW_PRESS) wish.y += 1;
        if (glfwGetKey(window, GLFW_KEY_LEFT_CONTROL)==GLFW_PRESS) wish.y -= 1;
        Vector3f vel = new Vector3f();
        vel.fma(wish.z, f).fma(wish.x, r).fma(wish.y, u);
        if (vel.lengthSquared()>0) vel.normalize(speed);
        Physics.collideAABB(chunkManager, camera.position, vel, 0.6f, 1.8f, dt);
    }

    private void uploadMatrix(int program,String name, Matrix4f m){
        int loc = glGetUniformLocation(program, name);
        try(MemoryStack stack = stackPush()){
            FloatBuffer fb = stack.mallocFloat(16);
            m.get(fb);
            glUniformMatrix4fv(loc, false, fb);
        }
    }

    /** Releases OpenGL resources and destroys the GLFW window. */
    private void cleanup(){
        glDeleteProgram(computeProgram);
        glDeleteProgram(quadProgram);
        glDeleteTextures(outputTex);
        glDeleteVertexArrays(vaoQuad);
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
