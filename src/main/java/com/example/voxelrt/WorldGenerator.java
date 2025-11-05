package com.example.voxelrt;

/**
 * Procedural generator that decides which block occupies a given world coordinate.
 */
public class WorldGenerator {
    private final Noise height = new Noise(1337L);
    private final Noise temp = new Noise(1337L * 17L + 5L);
    private final int seaLevel;

    public WorldGenerator(long seed, int seaLevel) {
        this.seaLevel = seaLevel;
    }

    /**
     * Samples a block type using height and temperature noise to produce simple biomes.
     */
    public int sampleBlock(int x, int y, int z) {
        Column column = sampleColumn(x, z);
        int ground = column.groundHeight();
        if (y > ground) return Blocks.AIR;
        if (ground >= 0 && y == ground) return column.surfaceBlock();
        if (y >= ground - 3) return column.fillerBlock();
        return column.lowerBlock();
    }

    /**
     * Computes the terrain column for the world coordinate (x, z).
     */
    public Column sampleColumn(int x, int z) {
        double h = 28 + 24 * height.fractal2D(x * 0.012, z * 0.012, 5);
        int ground = (int) java.lang.Math.round(h) + 64;
        double t = temp.fractal2D(x * 0.004, z * 0.004, 4);
        if (ground >= 95 && t < -0.15) {
            return new Column(ground, Blocks.SNOW, Blocks.DIRT, Blocks.STONE);
        } else if (ground <= seaLevel + 2 && t > 0.2) {
            return new Column(ground, Blocks.SAND, Blocks.SAND, Blocks.DIRT);
        } else {
            return new Column(ground, Blocks.GRASS, Blocks.DIRT, Blocks.STONE);
        }
    }

    public record Column(int groundHeight, int surfaceBlock, int fillerBlock, int lowerBlock) {
    }
}
