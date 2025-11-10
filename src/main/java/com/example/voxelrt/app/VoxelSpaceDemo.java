package com.example.voxelrt.app;

import com.example.voxelrt.render.VoxelSpaceRenderer;
import com.example.voxelrt.world.Blocks;
import com.example.voxelrt.world.WorldGenerator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Simple command line entry point that demonstrates the {@link VoxelSpaceRenderer}.
 * <p>
 * The demo builds a procedural height/colour map using the existing {@link WorldGenerator} and then
 * renders it to an image file using the voxel space algorithm.
 */
public final class VoxelSpaceDemo {
    private VoxelSpaceDemo() {
    }

    public static void main(String[] args) throws IOException {
        int mapSize = 512;
        int screenWidth = 800;
        int screenHeight = 600;
        long seed = 1337L;
        int seaLevel = 62;

        WorldGenerator generator = new WorldGenerator(seed, seaLevel);
        int[][] heightmap = new int[mapSize][mapSize];
        int[][] colormap = new int[mapSize][mapSize];
        for (int x = 0; x < mapSize; x++) {
            for (int z = 0; z < mapSize; z++) {
                WorldGenerator.Column column = generator.sampleColumn(x, z);
                heightmap[x][z] = column.groundHeight();
                colormap[x][z] = encodeColor(column.surfaceBlock());
            }
        }

        VoxelSpaceRenderer renderer = new VoxelSpaceRenderer(heightmap, colormap, screenWidth, screenHeight);
        float cameraX = mapSize / 2.0f;
        float cameraY = mapSize / 2.0f;
        float phi = (float) Math.toRadians(25.0);
        float cameraHeight = generator.seaLevel() + 28.0f;
        float horizon = 220.0f;
        float scaleHeight = 140.0f;
        float distance = 320.0f;

        BufferedImage frame = renderer.render(cameraX, cameraY, phi, cameraHeight, horizon, scaleHeight, distance);
        File output = new File("voxelspace-demo.png");
        ImageIO.write(frame, "png", output);
        System.out.println("Wrote voxel space demo image to " + output.getAbsolutePath());
    }

    private static int encodeColor(int blockId) {
        org.joml.Vector3f color = Blocks.color(blockId);
        int r = Math.round(clamp(color.x) * 255.0f);
        int g = Math.round(clamp(color.y) * 255.0f);
        int b = Math.round(clamp(color.z) * 255.0f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static float clamp(float value) {
        if (value < 0.0f) {
            return 0.0f;
        }
        if (value > 1.0f) {
            return 1.0f;
        }
        return value;
    }
}
