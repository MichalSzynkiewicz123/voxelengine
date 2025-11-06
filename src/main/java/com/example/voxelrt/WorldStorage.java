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
    private static final int CHUNK_DATA_FILE_VERSION = 1;

    private final Path baseDir;
    private final Path chunkEditDir;
    private final Path chunkDataDir;
    private final ExecutorService ioExecutor;
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> pendingSaves = new ConcurrentLinkedQueue<>();

    public WorldStorage(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        try {
            Files.createDirectories(baseDir);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to create world directory " + baseDir, ex);
        }
        this.chunkEditDir = baseDir.resolve("chunks");
        try {
            Files.createDirectories(chunkEditDir);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to create chunk directory " + chunkEditDir, ex);
        }
        this.chunkDataDir = baseDir.resolve("chunkdata");
        try {
            Files.createDirectories(chunkDataDir);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to create chunk data directory " + chunkDataDir, ex);
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
        Path file = chunkEditFile(pos);
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
        Path file = chunkEditFile(pos);
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

    public ChunkCompression.CompressedChunkData loadChunkData(ChunkPos pos) {
        Objects.requireNonNull(pos, "pos");
        Path file = chunkDataFile(pos);
        if (!Files.exists(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file); DataInputStream data = new DataInputStream(in)) {
            int fileVersion = data.readInt();
            if (fileVersion != CHUNK_DATA_FILE_VERSION) {
                System.err.println("[WorldStorage] Unsupported chunk data file version " + fileVersion + " for " + pos);
                return null;
            }
            int compressionVersion = data.readInt();
            if (compressionVersion != ChunkCompression.currentFormatVersion()) {
                System.err.println("[WorldStorage] Unsupported chunk compression version " + compressionVersion + " for " + pos);
                return null;
            }
            boolean allAir = data.readBoolean();
            int uncompressedSize = data.readInt();
            int nonAir = data.readInt();
            int compressedLength = data.readInt();
            byte[] compressed = new byte[compressedLength];
            data.readFully(compressed);
            return new ChunkCompression.CompressedChunkData(compressed, uncompressedSize, nonAir, allAir, compressionVersion);
        } catch (IOException ex) {
            System.err.println("[WorldStorage] Failed to read chunk data for " + pos + ": " + ex.getMessage());
            return null;
        }
    }

    public void saveChunkDataAsync(ChunkPos pos, ChunkCompression.CompressedChunkData data) {
        ChunkCompression.CompressedChunkData snapshot = data;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> saveChunkData(pos, snapshot), ioExecutor);
        pendingSaves.add(future);
    }

    public void saveChunkData(ChunkPos pos, ChunkCompression.CompressedChunkData data) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(data, "data");
        Path file = chunkDataFile(pos);
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException ex) {
            System.err.println("[WorldStorage] Failed to create parent directories for " + file + ": " + ex.getMessage());
        }
        try (OutputStream out = Files.newOutputStream(file); DataOutputStream dataOut = new DataOutputStream(out)) {
            dataOut.writeInt(CHUNK_DATA_FILE_VERSION);
            dataOut.writeInt(data.formatVersion());
            dataOut.writeBoolean(data.allAir());
            dataOut.writeInt(data.uncompressedSize());
            dataOut.writeInt(data.nonAir());
            byte[] compressed = data.compressed();
            dataOut.writeInt(compressed.length);
            dataOut.write(compressed);
        } catch (IOException ex) {
            System.err.println("[WorldStorage] Failed to write chunk data for " + pos + ": " + ex.getMessage());
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

    private Path chunkEditFile(ChunkPos pos) {
        return chunkEditDir.resolve(pos.cx() + "_" + pos.cz() + ".bin");
    }

    private Path chunkDataFile(ChunkPos pos) {
        return chunkDataDir.resolve(pos.cx() + "_" + pos.cz() + ".cbin");
    }

    public record ChunkEdit(int x, int y, int z, int block) {
    }
}
