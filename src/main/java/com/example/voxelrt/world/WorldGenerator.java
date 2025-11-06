package com.example.voxelrt.world;

import java.util.Random;

/**
 * Procedural generator that decides which block occupies a given world coordinate.
 */
public class WorldGenerator {
    private final long seed;
    private final Noise baseHeight;
    private final Noise hillNoise;
    private final Noise mountainNoise;
    private final Noise temperature;
    private final Noise moisture;
    private final Noise densityNoise;
    private final Noise caveNoise;
    private final int seaLevel;
    private final int snowLine;

    public WorldGenerator(long seed, int seaLevel) {
        this.seed = seed;
        this.seaLevel = seaLevel;
        this.snowLine = seaLevel + 28;
        long mix = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
        this.baseHeight = new Noise(mix ^ 0x2545F4914F6CDD1DL);
        this.hillNoise = new Noise(mix ^ 0x632BE59BD9B4E019L);
        this.mountainNoise = new Noise(mix ^ 0xAC564B05A5A5A5A5L);
        this.temperature = new Noise(mix ^ 0xF1EA5EEDC0DEC0DEL);
        this.moisture = new Noise(mix ^ 0x8E9D5B3A778B675FL);
        this.densityNoise = new Noise(mix ^ 0xC6BC279692B5C323L);
        this.caveNoise = new Noise(mix ^ 0x94D049BB133111EBL);
    }

    /**
     * Samples a block type using layered noise to produce complex terrain with caves.
     */
    public int sampleBlock(int x, int y, int z) {
        Column column = sampleColumn(x, z);
        return sampleBlock(column, x, y, z);
    }

    /**
     * Samples a block for a position using a precomputed column description.
     */
    public int sampleBlock(Column column, int x, int y, int z) {
        int ground = column.groundHeight();
        if (y > ground) {
            return Blocks.AIR;
        }

        if (y == ground) {
            if (column.biome() == Biome.MOUNTAINS && y >= snowLine) {
                return Blocks.SNOW;
            }
            if (column.biome() == Biome.TUNDRA && y >= seaLevel + 5) {
                return Blocks.SNOW;
            }
            return column.surfaceBlock();
        }

        double depth = ground - y;
        double density = depth * 0.12 - 0.35 + densityNoise.fractal3D(x * 0.012, y * 0.015, z * 0.012, 3);
        if (density < 0) {
            return Blocks.AIR;
        }

        double cave = caveNoise.fractal3D(x * 0.035, y * 0.035, z * 0.035, 4);
        double caveThreshold = 0.60 - java.lang.Math.min(depth, 32) * 0.018;
        if (caveThreshold < -0.35) {
            caveThreshold = -0.35;
        }
        if (cave > caveThreshold) {
            return Blocks.AIR;
        }

        if (y >= ground - 3) {
            return column.fillerBlock();
        }
        return column.lowerBlock();
    }

    /**
     * Computes the terrain column for the world coordinate (x, z).
     */
    public Column sampleColumn(int x, int z) {
        double continental = baseHeight.fractal2D(x * 0.002, z * 0.002, 4);
        double hills = hillNoise.fractal2D(x * 0.02, z * 0.02, 3);
        double mountainVal = mountainNoise.fractal2D(x * 0.0016, z * 0.0016, 5);
        double mountainMask = java.lang.Math.max(0.0, mountainVal);
        int mountainBoost = (int) java.lang.Math.round(mountainMask * mountainMask * 90.0);
        int base = (int) java.lang.Math.round(continental * 28.0 + hills * 10.0) + seaLevel - 4;
        int ground = base + mountainBoost;

        double tempVal = temperature.fractal2D(x * 0.002, z * 0.002, 4);
        double moistureVal = moisture.fractal2D(x * 0.0025, z * 0.0025, 4);

        Biome biome = selectBiome(ground, mountainBoost, tempVal, moistureVal);
        int surface;
        int filler;
        int lower = Blocks.STONE;

        switch (biome) {
            case DESERT -> {
                surface = Blocks.SAND;
                filler = Blocks.SAND;
            }
            case TUNDRA -> {
                surface = ground >= seaLevel + 2 ? Blocks.SNOW : Blocks.DIRT;
                filler = Blocks.DIRT;
            }
            case MOUNTAINS -> {
                surface = ground >= snowLine ? Blocks.SNOW : Blocks.STONE;
                filler = Blocks.STONE;
            }
            default -> {
                surface = Blocks.GRASS;
                filler = Blocks.DIRT;
            }
        }

        if (ground < seaLevel - 4) {
            ground = seaLevel - 4;
        }

        return new Column(ground, surface, filler, lower, biome, tempVal, moistureVal);
    }

    private Biome selectBiome(int ground, int mountainBoost, double tempVal, double moistureVal) {
        if (mountainBoost > 28 || ground > snowLine + 10) {
            return Biome.MOUNTAINS;
        }
        if (tempVal < -0.35) {
            return Biome.TUNDRA;
        }
        if (tempVal > 0.38 && moistureVal < 0.1) {
            return Biome.DESERT;
        }
        if (moistureVal > 0.32) {
            return Biome.FOREST;
        }
        return Biome.PLAINS;
    }

    public Random randomForChunk(int chunkX, int chunkZ) {
        long mixed = seed ^ (chunkX * 0x632BE59BD9B4E019L) ^ (chunkZ * 0x94D049BB133111EBL);
        mixed ^= Long.rotateLeft(chunkX * 0x2545F4914F6CDD1DL, 17);
        mixed ^= Long.rotateRight(chunkZ * 0xF1EA5EEDC0DEC0DEL, 11);
        return new Random(mixed);
    }

    public int seaLevel() {
        return seaLevel;
    }

    public int snowLine() {
        return snowLine;
    }

    public enum Biome {
        PLAINS,
        FOREST,
        DESERT,
        TUNDRA,
        MOUNTAINS
    }

    public record Column(int groundHeight, int surfaceBlock, int fillerBlock, int lowerBlock,
                         Biome biome, double temperature, double moisture) {
    }
}
