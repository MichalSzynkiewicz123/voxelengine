package com.example.voxelrt;

import java.util.*;

/**
 * Keeps track of chunks that have been generated and loaded into memory.
 * <p>
 * The manager owns both the persistent edits that the player has made and an in-memory LRU cache of
 * chunk data. When sampling blocks the cache is consulted first and, if a chunk is missing, it will
 * be generated on demand via the {@link WorldGenerator}.
 */
public class ChunkManager {
    private final WorldGenerator gen;
    private final Map<ChunkPos, Chunk> map = new HashMap<>();
    private final LinkedHashMap<ChunkPos, Chunk> lru = new LinkedHashMap<>(64, 0.75f, true);
    private final int maxLoaded;
    private final Map<Long, Integer> edits = new HashMap<>();

    public ChunkManager(WorldGenerator g, int maxLoaded) {
        this.gen = g;
        this.maxLoaded = java.lang.Math.max(64, maxLoaded);
    }

    /**
     * Packs a world-space coordinate triple into a single long for use in hash-based collections.
     * <p>
     * The bit allocation mirrors the maximum dimensions supported by the chunk system. X and Z are
     * stored in 21-bit two's-complement form while Y is stored as an unsigned 10-bit integer which is
     * sufficient for the 0-255 vertical range of a chunk column.
     */
    public static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x3FF) << 32) | (long) (z & 0x1FFFFF);
    }

    public Integer getEdit(int x, int y, int z) {
        return edits.get(key(x, y, z));
    }

    /**
     * Records a player edit and eagerly applies the change if the target chunk is currently loaded.
     */
    public void setEdit(int x, int y, int z, int b) {
        edits.put(key(x, y, z), b);
        Chunk c = map.get(new ChunkPos(java.lang.Math.floorDiv(x, Chunk.SX), java.lang.Math.floorDiv(z, Chunk.SZ)));
        if (c != null) {
            c.set(java.lang.Math.floorMod(x, Chunk.SX), y, java.lang.Math.floorMod(z, Chunk.SZ), b);
        }
    }

    /**
     * Retrieves a chunk either from the cache or by generating it on demand.
     * <p>
     * Any known edits within the chunk bounds are baked in after generation. The chunk is also marked
     * as most recently used to maintain the LRU eviction order.
     */
    public Chunk getOrLoad(ChunkPos p) {
        Chunk c = map.get(p);
        if (c == null) {
            c = new Chunk(p);
            c.fill(gen);

            int wx0 = p.cx() * Chunk.SX;
            int wz0 = p.cz() * Chunk.SZ;
            for (var e : edits.entrySet()) {
                long k = e.getKey();
                int x = (int) ((k >> 42) & 0x1FFFFF);
                int y = (int) ((k >> 32) & 0x3FF);
                int z = (int) (k & 0x1FFFFF);
                if (x >= 0x100000) x -= 0x200000;
                if (z >= 0x100000) z -= 0x200000;
                if (x >= wx0 && x < wx0 + Chunk.SX && z >= wz0 && z < wz0 + Chunk.SZ) {
                    c.set(x - wx0, y, z - wz0, e.getValue());
                }
            }
            map.put(p, c);
        }

        lru.put(p, c);
        while (lru.size() > maxLoaded) {
            var it = lru.keySet().iterator();
            ChunkPos oldest = it.next();
            it.remove();
            map.remove(oldest);
        }
        return c;
    }

    /**
     * Samples a block from world space coordinates, taking player edits into account.
     */
    public int sample(int x, int y, int z) {
        Integer e = getEdit(x, y, z);
        if (e != null) return e;
        ChunkPos p = new ChunkPos(java.lang.Math.floorDiv(x, Chunk.SX), java.lang.Math.floorDiv(z, Chunk.SZ));
        Chunk c = getOrLoad(p);
        return c.get(java.lang.Math.floorMod(x, Chunk.SX), y, java.lang.Math.floorMod(z, Chunk.SZ));
    }
}