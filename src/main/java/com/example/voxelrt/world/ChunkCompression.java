package com.example.voxelrt.world;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Utility methods for compressing and decompressing chunk voxel payloads.
 */
public final class ChunkCompression {
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private static final int FORMAT_VERSION = 1;

    private ChunkCompression() {
        // Utility class – prevent instantiation.
    }

    public static int currentFormatVersion() {
        return FORMAT_VERSION;
    }

    public static CompressedChunkData compress(Chunk.DenseData data) {
        Objects.requireNonNull(data, "data");
        byte[] voxels = Objects.requireNonNull(data.voxels(), "voxels");
        int uncompressedSize = voxels.length;
        boolean allAir = data.nonAir() == 0;
        if (allAir) {
            return new CompressedChunkData(new byte[0], uncompressedSize, data.nonAir(), true, FORMAT_VERSION);
        }
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater, IO_BUFFER_SIZE)) {
            dos.write(voxels);
            dos.finish();
            return new CompressedChunkData(baos.toByteArray(), uncompressedSize, data.nonAir(), false, FORMAT_VERSION);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to compress chunk data", ex);
        } finally {
            deflater.end();
        }
    }

    public static byte[] decompress(CompressedChunkData data) {
        Objects.requireNonNull(data, "data");
        if (data.allAir()) {
            return new byte[data.uncompressedSize()];
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data.compressed());
             InflaterInputStream inflater = new InflaterInputStream(bais)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(data.uncompressedSize());
            byte[] buffer = new byte[IO_BUFFER_SIZE];
            int read;
            while ((read = inflater.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            byte[] result = baos.toByteArray();
            if (result.length != data.uncompressedSize()) {
                throw new IllegalStateException("Decompressed chunk size mismatch: expected "
                        + data.uncompressedSize() + " bytes but got " + result.length);
            }
            return result;
        } catch (IOException ex) {
            throw new RuntimeException("Failed to decompress chunk data", ex);
        }
    }

    public record CompressedChunkData(byte[] compressed, int uncompressedSize, int nonAir, boolean allAir,
                                      int formatVersion) {
        public CompressedChunkData {
            Objects.requireNonNull(compressed, "compressed");
            if (uncompressedSize <= 0) {
                throw new IllegalArgumentException("Uncompressed chunk size must be positive");
            }
            if (formatVersion <= 0) {
                throw new IllegalArgumentException("Invalid format version " + formatVersion);
            }
        }
    }
}
