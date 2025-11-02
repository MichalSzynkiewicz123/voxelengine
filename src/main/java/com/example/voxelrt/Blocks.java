package com.example.voxelrt;

/**
 * Block identifiers shared between the CPU world representation and the GPU compute shader.
 * <p>
 * The numeric values are tightly packed so that they can be written directly into shader storage
 * buffers without any further translation. 0 is reserved for air which is treated as empty space in
 * almost every subsystem.
 */
public final class Blocks {
    /** Empty voxel – light rays can pass through it. */
    public static final int AIR = 0;
    /** Standard grass block used for most surface-level terrain. */
    public static final int GRASS = 1;
    /** Dirt block used under grass and for embankments. */
    public static final int DIRT = 2;
    /** Dense stone block generated deep underground. */
    public static final int STONE = 3;
    /** Sand block used near beaches and deserts. */
    public static final int SAND = 4;
    /** Snow block used for high elevations. */
    public static final int SNOW = 5;

    private Blocks() {
        // Utility class – prevent instantiation.
    }
}