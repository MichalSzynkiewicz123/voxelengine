package com.example.voxelrt.app.config;

import com.example.voxelrt.world.Chunk;
import com.example.voxelrt.world.ChunkManager;

import java.nio.file.Path;

/**
 * Immutable configuration bundle describing the tunable runtime parameters for {@link com.example.voxelrt.app.Engine}.
 * <p>
 * The configuration sources its values from system properties and environment variables with sensible defaults so
 * the engine can run out of the box. Consolidating this logic keeps the engine class focused on orchestrating the
 * render loop instead of parsing configuration in multiple places.
 */
public final class EngineConfig {
    private final int viewDistanceChunks;
    private final int chunkCacheSize;
    private final Path worldDirectory;
    private final int chunkIntegrationBudget;
    private final int activeRegionSizeXZ;
    private final int activeRegionHeight;
    private final int activeRegionMargin;

    private EngineConfig(int viewDistanceChunks,
                         int chunkCacheSize,
                         Path worldDirectory,
                         int chunkIntegrationBudget,
                         int activeRegionSizeXZ,
                         int activeRegionHeight,
                         int activeRegionMargin) {
        this.viewDistanceChunks = viewDistanceChunks;
        this.chunkCacheSize = chunkCacheSize;
        this.worldDirectory = worldDirectory;
        this.chunkIntegrationBudget = chunkIntegrationBudget;
        this.activeRegionSizeXZ = activeRegionSizeXZ;
        this.activeRegionHeight = activeRegionHeight;
        this.activeRegionMargin = activeRegionMargin;
    }

    public static EngineConfig load() {
        int viewDistance = parsePositiveInt("voxel.viewDistance", "VOXEL_VIEW_DISTANCE", 8, 4, 64);
        int chunkCache = determineChunkCacheSize();
        Path worldDir = determineWorldDirectory();
        int chunkBudget = parsePositiveInt("voxel.chunksPerFrame", "VOXEL_CHUNKS_PER_FRAME", 6, 1, Integer.MAX_VALUE);
        int activeRegionSize = determineActiveRegionSizeXZ(viewDistance);
        int activeRegionHeight = parsePositiveInt("voxel.activeRegionHeight", "VOXEL_ACTIVE_REGION_HEIGHT", 128, 64, Chunk.SY);
        int activeRegionMargin = computeActiveRegionMargin(activeRegionSize, activeRegionHeight);
        return new EngineConfig(viewDistance, chunkCache, worldDir, chunkBudget, activeRegionSize, activeRegionHeight, activeRegionMargin);
    }

    public int viewDistanceChunks() {
        return viewDistanceChunks;
    }

    public int chunkCacheSize() {
        return chunkCacheSize;
    }

    public Path worldDirectory() {
        return worldDirectory;
    }

    public int chunkIntegrationBudget() {
        return chunkIntegrationBudget;
    }

    public int activeRegionSizeXZ() {
        return activeRegionSizeXZ;
    }

    public int activeRegionHeight() {
        return activeRegionHeight;
    }

    public int activeRegionMargin() {
        return activeRegionMargin;
    }

    private static int parsePositiveInt(String propertyKey, String envKey, int fallback, int min, int max) {
        String configured = System.getProperty(propertyKey);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(envKey);
        }
        if (configured != null) {
            try {
                int parsed = Integer.parseInt(configured.trim());
                if (parsed > 0) {
                    return java.lang.Math.max(min, java.lang.Math.min(max, parsed));
                }
                System.err.println("[EngineConfig] Ignoring non-positive value for " + propertyKey + ": " + configured);
            } catch (NumberFormatException ex) {
                System.err.println("[EngineConfig] Failed to parse " + propertyKey + "='" + configured + "': " + ex.getMessage());
            }
        }
        return fallback;
    }

    private static int determineChunkCacheSize() {
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
                System.err.println("[EngineConfig] Ignoring non-positive chunk cache override: " + configured);
            } catch (NumberFormatException ex) {
                System.err.println("[EngineConfig] Failed to parse chunk cache override '" + configured + "': " + ex.getMessage());
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

    private static Path determineWorldDirectory() {
        String configured = System.getProperty("voxel.worldDir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("VOXEL_WORLD_DIR");
        }
        if (configured != null && !configured.isBlank()) {
            try {
                return Path.of(configured.trim());
            } catch (RuntimeException ex) {
                System.err.println("[EngineConfig] Failed to resolve world directory '" + configured + "': " + ex.getMessage());
            }
        }
        return Path.of("world");
    }

    private static int determineActiveRegionSizeXZ(int viewDistanceChunks) {
        String configured = System.getProperty("voxel.activeRegionSize");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("VOXEL_ACTIVE_REGION_SIZE");
        }
        if (configured != null) {
            try {
                int parsed = Integer.parseInt(configured.trim());
                if (parsed > 0) {
                    return alignToChunkMultiple(parsed);
                }
                System.err.println("[EngineConfig] Ignoring non-positive active region size override: " + configured);
            } catch (NumberFormatException ex) {
                System.err.println("[EngineConfig] Failed to parse active region size '" + configured + "': " + ex.getMessage());
            }
        }
        int base = Chunk.SX * (viewDistanceChunks * 2 + 3);
        int fallback = 128;
        int auto = java.lang.Math.max(fallback, base);
        int maxAuto = Chunk.SX * 32;
        auto = java.lang.Math.min(auto, maxAuto);
        return alignToChunkMultiple(auto);
    }

    private static int alignToChunkMultiple(int value) {
        int chunk = Chunk.SX;
        if (value % chunk == 0) {
            return value;
        }
        return (value / chunk + 1) * chunk;
    }

    private static int computeActiveRegionMargin(int regionSize, int regionHeight) {
        int margin = regionSize / 6;
        margin = java.lang.Math.max(margin, Chunk.SX);
        margin = java.lang.Math.max(margin, 24);
        int maxMargin = regionSize / 2 - Chunk.SX;
        if (maxMargin > 0) {
            margin = java.lang.Math.min(margin, maxMargin);
        }
        if (regionHeight > 0) {
            int verticalMax = regionHeight / 2 - 4;
            if (verticalMax > 0) {
                margin = java.lang.Math.min(margin, verticalMax);
            }
        }
        return margin;
    }
}
