package com.example.voxelrt.render;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Objects;

/**
 * CPU height-map renderer based on the classic "voxel space" algorithm popularised by the
 * Comanche series of games.
 * <p>
 * The renderer projects a 2D height/colour map into screen space by sweeping depth slices from the
 * camera outwards. For each slice a line on the terrain map is rasterised and the highest visible
 * terrain sample per screen column is drawn as a vertical span. This allows large terrains to be
 * visualised efficiently without performing full 3D rasterisation.
 */
public final class VoxelSpaceRenderer {
    private final int[][] heightmap;
    private final int[][] colormap;
    private final int mapWidth;
    private final int mapHeight;
    private final int screenWidth;
    private final int screenHeight;
    private final float[] yBuffer;
    private final BufferedImage framebuffer;

    /**
     * Creates a renderer for the supplied height/colour maps.
     *
     * @param heightmap   2D array containing terrain heights in world units.
     * @param colormap    2D array containing ARGB colours for each terrain column.
     * @param screenWidth Width of the output framebuffer in pixels.
     * @param screenHeight Height of the output framebuffer in pixels.
     */
    public VoxelSpaceRenderer(int[][] heightmap, int[][] colormap, int screenWidth, int screenHeight) {
        Objects.requireNonNull(heightmap, "heightmap");
        Objects.requireNonNull(colormap, "colormap");
        if (heightmap.length == 0 || heightmap[0].length == 0) {
            throw new IllegalArgumentException("Heightmap must not be empty");
        }
        if (colormap.length != heightmap.length || colormap[0].length != heightmap[0].length) {
            throw new IllegalArgumentException("Colormap must match heightmap dimensions");
        }
        this.mapWidth = heightmap.length;
        this.mapHeight = heightmap[0].length;
        for (int x = 0; x < mapWidth; x++) {
            if (heightmap[x].length != mapHeight || colormap[x].length != mapHeight) {
                throw new IllegalArgumentException("Heightmap and colormap must be rectangular");
            }
        }
        if (screenWidth <= 0 || screenHeight <= 0) {
            throw new IllegalArgumentException("Screen size must be positive");
        }
        this.heightmap = heightmap;
        this.colormap = colormap;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.yBuffer = new float[screenWidth];
        this.framebuffer = new BufferedImage(screenWidth, screenHeight, BufferedImage.TYPE_INT_ARGB);
    }

    /**
     * Renders the terrain into the internal framebuffer and returns it.
     *
     * @param px           Camera X position on the height map.
     * @param py           Camera Y position on the height map.
     * @param phi          Camera rotation in radians (0 faces towards positive Y).
     * @param cameraHeight Camera height above terrain in world units.
     * @param horizon      Screen-space horizon position (pixels from the top of the framebuffer).
     * @param scaleHeight  Scaling factor that converts world height differences into pixels.
     * @param distance     Maximum render distance on the height map.
     * @return Buffered image containing the rendered frame.
     */
    public BufferedImage render(float px, float py, float phi,
                                float cameraHeight, float horizon,
                                float scaleHeight, float distance) {
        Arrays.fill(yBuffer, screenHeight);
        paintBackground((int) Math.round(horizon));

        double sinPhi = Math.sin(phi);
        double cosPhi = Math.cos(phi);

        double dz = 1.0;
        double z = 1.0;
        while (z < distance) {
            double startX = (-cosPhi * z - sinPhi * z) + px;
            double startY = ( sinPhi * z - cosPhi * z) + py;
            double endX = ( cosPhi * z - sinPhi * z) + px;
            double endY = (-sinPhi * z - cosPhi * z) + py;

            double dx = (endX - startX) / screenWidth;
            double dy = (endY - startY) / screenWidth;

            double sampleX = startX;
            double sampleY = startY;
            for (int column = 0; column < screenWidth; column++) {
                int mapX = wrapCoordinate(sampleX, mapWidth);
                int mapY = wrapCoordinate(sampleY, mapHeight);
                float terrainHeight = heightmap[mapX][mapY];
                float projectedHeight = (float) (((cameraHeight - terrainHeight) / z) * scaleHeight + horizon);
                if (projectedHeight < yBuffer[column]) {
                    int baseColor = colormap[mapX][mapY];
                    float shading = computeShading(mapX, mapY, terrainHeight, z, distance);
                    int shadedColor = applyShading(baseColor, shading);
                    drawVerticalLine(column, projectedHeight, yBuffer[column], shadedColor);
                    yBuffer[column] = projectedHeight;
                }
                sampleX += dx;
                sampleY += dy;
            }

            z += dz;
            dz += 0.2;
        }

        return framebuffer;
    }

    private void paintBackground(int horizon) {
        int skyTop = 0xFF6AA9FF;      // light blue
        int skyBottom = 0xFFB5D4FF;   // pale blue near the horizon
        int groundTop = 0xFF7E5B3C;   // light brown just below the horizon
        int groundBottom = 0xFF2D1F12; // darker brown near the bottom of the screen

        if (horizon < 0) {
            horizon = 0;
        }
        if (horizon > screenHeight) {
            horizon = screenHeight;
        }

        for (int y = 0; y < screenHeight; y++) {
            int color;
            if (y < horizon) {
                float t = horizon == 0 ? 0f : (float) y / (float) Math.max(1, horizon);
                color = lerpColor(skyTop, skyBottom, t);
            } else {
                int groundHeight = screenHeight - horizon;
                float t = groundHeight == 0 ? 1f : (float) (y - horizon) / (float) Math.max(1, groundHeight);
                color = lerpColor(groundTop, groundBottom, clamp01(t));
            }
            for (int x = 0; x < screenWidth; x++) {
                framebuffer.setRGB(x, y, color);
            }
        }
    }

    private float computeShading(int mapX, int mapY, float terrainHeight, double z, double maxDistance) {
        int eastX = wrapCoordinate(mapX + 1, mapWidth);
        int northY = wrapCoordinate(mapY + 1, mapHeight);
        float eastHeight = heightmap[eastX][mapY];
        float northHeight = heightmap[mapX][northY];
        float slope = ((terrainHeight - eastHeight) + (terrainHeight - northHeight)) * 0.5f;
        float slopeTerm = clamp01(0.75f + slope * 0.02f);
        float fog = clamp01(1.0f - (float) (z / maxDistance));
        return clamp01(0.35f + slopeTerm * 0.65f * fog);
    }

    private int applyShading(int color, float shading) {
        shading = clamp01(shading);
        int a = (color >>> 24) & 0xFF;
        int r = (color >>> 16) & 0xFF;
        int g = (color >>> 8) & 0xFF;
        int b = color & 0xFF;
        r = Math.round(r * shading);
        g = Math.round(g * shading);
        b = Math.round(b * shading);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void drawVerticalLine(int x, float top, float bottom, int color) {
        int yStart = (int) Math.ceil(Math.max(0f, top));
        int yEnd = (int) Math.floor(Math.min(bottom, screenHeight - 1f));
        if (yStart > yEnd) {
            return;
        }
        for (int y = yStart; y <= yEnd; y++) {
            framebuffer.setRGB(x, y, color);
        }
    }

    private static int wrapCoordinate(double value, int size) {
        int idx = (int) Math.floor(value);
        int mod = idx % size;
        if (mod < 0) {
            mod += size;
        }
        return mod;
    }

    private static float clamp01(float value) {
        if (value < 0f) {
            return 0f;
        }
        if (value > 1f) {
            return 1f;
        }
        return value;
    }

    private static int lerpColor(int c0, int c1, float t) {
        t = clamp01(t);
        int a0 = (c0 >>> 24) & 0xFF;
        int r0 = (c0 >>> 16) & 0xFF;
        int g0 = (c0 >>> 8) & 0xFF;
        int b0 = c0 & 0xFF;
        int a1 = (c1 >>> 24) & 0xFF;
        int r1 = (c1 >>> 16) & 0xFF;
        int g1 = (c1 >>> 8) & 0xFF;
        int b1 = c1 & 0xFF;
        int a = Math.round(a0 + (a1 - a0) * t);
        int r = Math.round(r0 + (r1 - r0) * t);
        int g = Math.round(g0 + (g1 - g0) * t);
        int b = Math.round(b0 + (b1 - b0) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
