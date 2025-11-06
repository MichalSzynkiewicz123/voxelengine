package com.example.voxelrt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Persists chunk edits to disk so that player changes survive streaming and restarts.
 */
public class WorldStorage implements AutoCloseable {
    private final Path baseDir;
    private final Path chunkDir;
    private final ExecutorService ioExecutor;
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> pendingSaves = new ConcurrentLinkedQueue<>();

    public WorldStorage(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        try {
            Files.createDirectories(baseDir);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to create world directory " + baseDir, ex);
        }
        this.chunkDir = baseDir.resolve("chunks");
        try {
            Files.createDirectories(chunkDir);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to create chunk directory " + chunkDir, ex);
        }
        int workers = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
        this.ioExecutor = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "WorldStorage-" + Integer.toHexString(r.hashCode()));
            t.setDaemon(true);
            return t;
        });
    }

    public List<ChunkEdit> loadChunkEdits(ChunkPos pos) {
        Objects.requireNonNull(pos, "pos");
        Path file = chunkFile(pos);
        if (!Files.exists(file)) {
            return Collections.emptyList();
        }
        List<ChunkEdit> edits = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file); DataInputStream data = new DataInputStream(in)) {
            int count = data.readInt();
            for (int i = 0; i < count; i++) {
                int x = data.readUnsignedByte();
                int y = data.readUnsignedShort();
                int z = data.readUnsignedByte();
                int block = data.readInt();
                edits.add(new ChunkEdit(x, y, z, block));
            }
        } catch (IOException ex) {
            System.err.println("[WorldStorage] Failed to read edits for chunk " + pos + ": " + ex.getMessage());
            return Collections.emptyList();
        }
        return edits;
    }

    public void saveChunkEditsAsync(ChunkPos pos, List<ChunkEdit> edits) {
        if (edits == null) {
            edits = Collections.emptyList();
        }
        List<ChunkEdit> snapshot = new ArrayList<>(edits);
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> saveChunkEdits(pos, snapshot), ioExecutor);
        pendingSaves.add(future);
    }

    public void saveChunkEdits(ChunkPos pos, List<ChunkEdit> edits) {
        Objects.requireNonNull(pos, "pos");
        List<ChunkEdit> list = edits == null ? Collections.emptyList() : edits;
        Path file = chunkFile(pos);
        if (list.isEmpty()) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ex) {
                System.err.println("[WorldStorage] Failed to delete empty chunk file " + file + ": " + ex.getMessage());
            }
            return;
        }
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException ex) {
            System.err.println("[WorldStorage] Failed to create parent directories for " + file + ": " + ex.getMessage());
        }
        try (OutputStream out = Files.newOutputStream(file); DataOutputStream data = new DataOutputStream(out)) {
            data.writeInt(list.size());
            for (ChunkEdit edit : list) {
                data.writeByte(edit.x());
                data.writeShort(edit.y());
                data.writeByte(edit.z());
                data.writeInt(edit.block());
            }
        } catch (IOException ex) {
            System.err.println("[WorldStorage] Failed to write edits for chunk " + pos + ": " + ex.getMessage());
        }
    }

    public void waitForPendingSaves() {
        CompletableFuture<Void> future;
        while ((future = pendingSaves.poll()) != null) {
            future.join();
        }
    }

    @Override
    public void close() {
        waitForPendingSaves();
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }

    private Path chunkFile(ChunkPos pos) {
        return chunkDir.resolve(pos.cx() + "_" + pos.cz() + ".bin");
    }

    public record ChunkEdit(int x, int y, int z, int block) {
    }
}
