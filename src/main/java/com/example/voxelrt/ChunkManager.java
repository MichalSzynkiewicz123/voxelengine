package com.example.voxelrt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps track of chunks that have been generated and loaded into memory.
 * <p>
 * The manager owns both the persistent edits that the player has made and an in-memory LRU cache of
 * chunk data. When sampling blocks the cache is consulted first and, if a chunk is missing, it will
 * be generated on demand via the {@link WorldGenerator}.
 */
public class ChunkManager {
    public static final int MIN_CACHE_SIZE = 64;
    public static final int DEFAULT_CACHE_SIZE = 256;
    private static final String THREAD_COUNT_PROPERTY = "voxel.chunkThreads";
    private static final String THREAD_COUNT_ENV = "VOXEL_CHUNK_THREADS";
    private static final int REQUEST_INTEGRATION_BUDGET = 2;
    private final WorldGenerator gen;
    private final Map<ChunkPos, Chunk> map = new HashMap<>();
    private final LinkedHashMap<ChunkPos, Chunk> lru = new LinkedHashMap<>(64, 0.75f, true);
    private volatile int maxLoaded;
    private final Map<Long, Integer> edits = new HashMap<>();
    private final Object lock = new Object();
    private final Object editLock = new Object();
    private final JobSystem jobSystem;
    private final ConcurrentHashMap<ChunkPos, CompletableFuture<Chunk>> pending = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ChunkLoadResult> completed = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean integratedSinceLastPoll = new AtomicBoolean();
    private final ConcurrentLinkedQueue<Chunk> chunkPool = new ConcurrentLinkedQueue<>();
    private final WorldStorage storage;
    private final Set<ChunkPos> diskLoadedChunks = new HashSet<>();

    public ChunkManager(WorldGenerator g, int maxLoaded) {
        this(g, maxLoaded, null);
    }

    public ChunkManager(WorldGenerator g, int maxLoaded, WorldStorage storage) {
        this.gen = g;
        this.maxLoaded = sanitizeMaxLoaded(maxLoaded);
        this.storage = storage;
        int threads = resolveThreadCount();
        this.jobSystem = new JobSystem("ChunkGen-", threads);
        System.out.println("[ChunkManager] Job system started with " + threads + " worker thread" + (threads == 1 ? "" : "s"));
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
        synchronized (editLock) {
            return edits.get(key(x, y, z));
        }
    }

    /**
     * Records a player edit and eagerly applies the change if the target chunk is currently loaded.
     */
    public void setEdit(int x, int y, int z, int b) {
        long k = key(x, y, z);
        synchronized (editLock) {
            edits.put(k, b);
            if (storage != null) {
                diskLoadedChunks.remove(new ChunkPos(java.lang.Math.floorDiv(x, Chunk.SX), java.lang.Math.floorDiv(z, Chunk.SZ)));
            }
        }
        Chunk c = getIfLoaded(new ChunkPos(java.lang.Math.floorDiv(x, Chunk.SX), java.lang.Math.floorDiv(z, Chunk.SZ)));
        if (c != null) {
            int localX = java.lang.Math.floorMod(x, Chunk.SX);
            int localZ = java.lang.Math.floorMod(z, Chunk.SZ);
            int previous = c.get(localX, y, localZ);
            c.set(localX, y, localZ, b);
            if (previous != b) {
                synchronized (lock) {
                    markNeighborsForVoxelChange(c.pos(), localX, localZ);
                }
            }
        }
    }

    /**
     * Retrieves a chunk either from the cache or by generating it on demand.
     * <p>
     * Any known edits within the chunk bounds are baked in after generation. The chunk is also marked
     * as most recently used to maintain the LRU eviction order.
     */
    public Chunk getOrLoad(ChunkPos p) {
        update();
        synchronized (lock) {
            Chunk cached = map.get(p);
            if (cached != null) {
                lru.put(p, cached);
                return cached;
            }
        }

        CompletableFuture<Chunk> future = ensureTask(p);
        Chunk chunk;
        try {
            chunk = future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Chunk generation interrupted for " + p, e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Chunk generation failed for " + p, e.getCause());
        }

        update();
        synchronized (lock) {
            Chunk loaded = map.get(p);
            if (loaded != null) {
                lru.put(p, loaded);
                return loaded;
            }
            applyEdits(chunk);
            map.put(p, chunk);
            lru.put(p, chunk);
            trimToMaxLocked();
            return chunk;
        }
    }

    /**
     * Samples a block from world space coordinates, taking player edits into account.
     */
    public int sample(int x, int y, int z) {
        update();
        Integer e = getEdit(x, y, z);
        if (e != null) return e;
        ChunkPos p = new ChunkPos(java.lang.Math.floorDiv(x, Chunk.SX), java.lang.Math.floorDiv(z, Chunk.SZ));
        Chunk c = getOrLoad(p);
        return c.get(java.lang.Math.floorMod(x, Chunk.SX), y, java.lang.Math.floorMod(z, Chunk.SZ));
    }

    public Chunk getIfLoaded(ChunkPos pos) {
        update();
        synchronized (lock) {
            Chunk chunk = map.get(pos);
            if (chunk != null) {
                lru.put(pos, chunk);
            }
            return chunk;
        }
    }

    public void requestChunk(ChunkPos pos) {
        update(REQUEST_INTEGRATION_BUDGET);
        boolean alreadyLoaded;
        synchronized (lock) {
            alreadyLoaded = map.containsKey(pos);
            if (alreadyLoaded) {
                lru.put(pos, map.get(pos));
            }
        }
        if (!alreadyLoaded) {
            ensureTask(pos);
        }
    }

    public boolean update() {
        return updateInternal(Integer.MAX_VALUE);
    }

    public boolean update(int maxIntegrations) {
        if (maxIntegrations <= 0) {
            return false;
        }
        return updateInternal(maxIntegrations);
    }

    private boolean updateInternal(int maxIntegrations) {
        boolean changed = false;
        ChunkLoadResult result;
        int integrated = 0;
        while (integrated < maxIntegrations && (result = completed.poll()) != null) {
            applyEdits(result.chunk);
            result.chunk.markMeshDirty();
            synchronized (lock) {
                map.put(result.pos, result.chunk);
                lru.put(result.pos, result.chunk);
                markNeighborsDirty(result.pos);
                trimToMaxLocked();
            }
            changed = true;
            integrated++;
        }
        if (changed) {
            integratedSinceLastPoll.set(true);
        }
        return changed;
    }

    public boolean drainIntegratedFlag() {
        return integratedSinceLastPoll.getAndSet(false);
    }

    public void shutdown() {
        jobSystem.close();
    }

    public void flushEdits() {
        if (storage == null) {
            return;
        }
        List<ChunkPos> positions;
        synchronized (lock) {
            positions = new ArrayList<>(map.keySet());
        }
        for (ChunkPos pos : positions) {
            storage.saveChunkEditsAsync(pos, gatherEditsForChunk(pos));
        }
        storage.waitForPendingSaves();
    }

    public void setMaxLoaded(int maxLoaded) {
        int sanitized = sanitizeMaxLoaded(maxLoaded);
        synchronized (lock) {
            if (sanitized != this.maxLoaded) {
                this.maxLoaded = sanitized;
                trimToMaxLocked();
            }
        }
    }

    public int getMaxLoaded() {
        return maxLoaded;
    }

    private void trimToMaxLocked() {
        int limit = this.maxLoaded;
        while (lru.size() > limit) {
            Iterator<ChunkPos> it = lru.keySet().iterator();
            if (!it.hasNext()) break;
            ChunkPos oldest = it.next();
            it.remove();
            Chunk removed = map.remove(oldest);
            if (removed != null) {
                markNeighborsDirty(oldest);
                evictChunkLocked(oldest, removed);
            }
        }
    }

    private static int sanitizeMaxLoaded(int maxLoaded) {
        return java.lang.Math.max(MIN_CACHE_SIZE, maxLoaded);
    }

    private int resolveThreadCount() {
        int defaultThreads = java.lang.Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        String configured = System.getProperty(THREAD_COUNT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(THREAD_COUNT_ENV);
        }
        if (configured != null) {
            try {
                int parsed = Integer.parseInt(configured.trim());
                if (parsed > 0) {
                    return parsed;
                }
                System.err.println("[ChunkManager] Ignoring non-positive chunk thread override: " + configured);
            } catch (NumberFormatException ex) {
                System.err.println("[ChunkManager] Failed to parse chunk thread override '" + configured + "': " + ex.getMessage());
            }
        }
        return defaultThreads;
    }

    private void markNeighborsDirty(ChunkPos pos) {
        int cx = pos.cx();
        int cz = pos.cz();
        Chunk neighbor;
        neighbor = map.get(new ChunkPos(cx - 1, cz));
        if (neighbor != null) neighbor.markMeshDirty();
        neighbor = map.get(new ChunkPos(cx + 1, cz));
        if (neighbor != null) neighbor.markMeshDirty();
        neighbor = map.get(new ChunkPos(cx, cz - 1));
        if (neighbor != null) neighbor.markMeshDirty();
        neighbor = map.get(new ChunkPos(cx, cz + 1));
        if (neighbor != null) neighbor.markMeshDirty();
    }

    private void markNeighborsForVoxelChange(ChunkPos pos, int localX, int localZ) {
        int cx = pos.cx();
        int cz = pos.cz();
        if (localX == 0) {
            Chunk west = map.get(new ChunkPos(cx - 1, cz));
            if (west != null) west.markMeshDirty();
        }
        if (localX == Chunk.SX - 1) {
            Chunk east = map.get(new ChunkPos(cx + 1, cz));
            if (east != null) east.markMeshDirty();
        }
        if (localZ == 0) {
            Chunk north = map.get(new ChunkPos(cx, cz - 1));
            if (north != null) north.markMeshDirty();
        }
        if (localZ == Chunk.SZ - 1) {
            Chunk south = map.get(new ChunkPos(cx, cz + 1));
            if (south != null) south.markMeshDirty();
        }
    }

    public List<Chunk> snapshotLoadedChunks() {
        synchronized (lock) {
            return new ArrayList<>(map.values());
        }
    }

    private CompletableFuture<Chunk> ensureTask(ChunkPos pos) {
        return pending.computeIfAbsent(pos, p -> {
            CompletableFuture<Chunk> future = jobSystem.submit(() -> {
                Chunk chunk = obtainChunk(p);
                try {
                    chunk.fill(gen);
                    return chunk;
                } catch (Throwable t) {
                    chunk.prepareForPool();
                    chunkPool.offer(chunk);
                    throw t;
                }
            });
            future.whenComplete((chunk, throwable) -> {
                pending.remove(p, future);
                if (throwable == null) {
                    completed.add(new ChunkLoadResult(p, chunk));
                } else {
                    System.err.println("[ChunkManager] Failed to generate chunk " + p + ": " + throwable);
                }
            });
            return future;
        });
    }

    private Chunk obtainChunk(ChunkPos pos) {
        Chunk chunk = chunkPool.poll();
        if (chunk != null) {
            chunk.reset(pos);
            return chunk;
        }
        return new Chunk(pos);
    }

    private void applyEdits(Chunk chunk) {
        ChunkPos pos = chunk.pos();
        loadDiskEditsIfNeeded(pos);
        int wx0 = pos.cx() * Chunk.SX;
        int wz0 = pos.cz() * Chunk.SZ;
        synchronized (editLock) {
            for (var e : edits.entrySet()) {
                long k = e.getKey();
                int x = decodeX(k);
                int y = decodeY(k);
                int z = decodeZ(k);
                if (x >= wx0 && x < wx0 + Chunk.SX && z >= wz0 && z < wz0 + Chunk.SZ) {
                    chunk.set(x - wx0, y, z - wz0, e.getValue());
                }
            }
        }
    }

    private void loadDiskEditsIfNeeded(ChunkPos pos) {
        if (storage == null) {
            return;
        }
        synchronized (editLock) {
            if (diskLoadedChunks.contains(pos)) {
                return;
            }
        }
        List<WorldStorage.ChunkEdit> editsForChunk = storage.loadChunkEdits(pos);
        if (!editsForChunk.isEmpty()) {
            int wx0 = pos.cx() * Chunk.SX;
            int wz0 = pos.cz() * Chunk.SZ;
            synchronized (editLock) {
                for (WorldStorage.ChunkEdit edit : editsForChunk) {
                    long key = key(wx0 + edit.x(), edit.y(), wz0 + edit.z());
                    edits.put(key, edit.block());
                }
                diskLoadedChunks.add(pos);
            }
        } else {
            synchronized (editLock) {
                diskLoadedChunks.add(pos);
            }
        }
    }

    private List<WorldStorage.ChunkEdit> gatherEditsForChunk(ChunkPos pos) {
        if (storage == null) {
            return Collections.emptyList();
        }
        int wx0 = pos.cx() * Chunk.SX;
        int wz0 = pos.cz() * Chunk.SZ;
        List<WorldStorage.ChunkEdit> chunkEdits = new ArrayList<>();
        synchronized (editLock) {
            for (var entry : edits.entrySet()) {
                long k = entry.getKey();
                int x = decodeX(k);
                int y = decodeY(k);
                int z = decodeZ(k);
                if (x >= wx0 && x < wx0 + Chunk.SX && z >= wz0 && z < wz0 + Chunk.SZ) {
                    chunkEdits.add(new WorldStorage.ChunkEdit(x - wx0, y, z - wz0, entry.getValue()));
                }
            }
        }
        return chunkEdits;
    }

    private void evictChunkLocked(ChunkPos pos, Chunk chunk) {
        if (storage != null) {
            storage.saveChunkEditsAsync(pos, gatherEditsForChunk(pos));
            synchronized (editLock) {
                diskLoadedChunks.remove(pos);
            }
        }
        chunk.releaseMesh();
        chunk.prepareForPool();
        chunkPool.offer(chunk);
    }

    private static int decodeX(long key) {
        int x = (int) ((key >> 42) & 0x1FFFFF);
        if (x >= 0x100000) x -= 0x200000;
        return x;
    }

    private static int decodeY(long key) {
        return (int) ((key >> 32) & 0x3FF);
    }

    private static int decodeZ(long key) {
        int z = (int) (key & 0x1FFFFF);
        if (z >= 0x100000) z -= 0x200000;
        return z;
    }

    public void unloadOutsideRadius(ChunkPos center, int radius) {
        if (radius < 0) {
            return;
        }
        List<Map.Entry<ChunkPos, Chunk>> toEvict = new ArrayList<>();
        synchronized (lock) {
            Iterator<Map.Entry<ChunkPos, Chunk>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<ChunkPos, Chunk> entry = it.next();
                ChunkPos pos = entry.getKey();
                if (chebyshevDistance(pos, center) > radius) {
                    it.remove();
                    lru.remove(pos);
                    markNeighborsDirty(pos);
                    toEvict.add(entry);
                }
            }
        }
        for (Map.Entry<ChunkPos, Chunk> entry : toEvict) {
            evictChunkLocked(entry.getKey(), entry.getValue());
        }

        if (!pending.isEmpty()) {
            List<Map.Entry<ChunkPos, CompletableFuture<Chunk>>> toCancel = new ArrayList<>();
            for (Map.Entry<ChunkPos, CompletableFuture<Chunk>> entry : pending.entrySet()) {
                if (chebyshevDistance(entry.getKey(), center) > radius) {
                    toCancel.add(entry);
                }
            }
            for (Map.Entry<ChunkPos, CompletableFuture<Chunk>> entry : toCancel) {
                if (pending.remove(entry.getKey(), entry.getValue())) {
                    entry.getValue().cancel(true);
                }
            }
        }
    }

    private static int chebyshevDistance(ChunkPos a, ChunkPos b) {
        return java.lang.Math.max(java.lang.Math.abs(a.cx() - b.cx()), java.lang.Math.abs(a.cz() - b.cz()));
    }

    private static final class ChunkLoadResult {
        final ChunkPos pos;
        final Chunk chunk;

        ChunkLoadResult(ChunkPos pos, Chunk chunk) {
            this.pos = pos;
            this.chunk = chunk;
        }
    }
}
