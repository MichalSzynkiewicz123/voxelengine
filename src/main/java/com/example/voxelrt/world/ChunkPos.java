package com.example.voxelrt.world;

/**
 * Immutable chunk coordinate expressed in chunk units along the X and Z axes.
 * <p>
 * Using a record keeps the type lightweight while still enabling type safety when
 * passing chunk positions throughout the engine.
 */
public record ChunkPos(int cx, int cz) {
}
