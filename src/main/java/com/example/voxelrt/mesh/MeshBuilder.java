package com.example.voxelrt.mesh;

import com.example.voxelrt.Blocks;
import com.example.voxelrt.Chunk;
import com.example.voxelrt.ChunkManager;
import com.example.voxelrt.ChunkPos;

import java.util.ArrayList;
import java.util.List;

public final class MeshBuilder {
    private static final int FLOATS_PER_INSTANCE = 9;

    private MeshBuilder() {
    }

    public static MeshData build(Chunk chunk, ChunkManager manager) {
        List<QuadInstance> instances = new ArrayList<>();
        int worldX0 = chunk.pos().cx() * Chunk.SX;
        int worldZ0 = chunk.pos().cz() * Chunk.SZ;

        buildForAxisX(chunk, manager, instances, worldX0, worldZ0);
        buildForAxisY(chunk, manager, instances, worldX0, worldZ0);
        buildForAxisZ(chunk, manager, instances, worldX0, worldZ0);

        int instanceCount = instances.size();
        if (instanceCount == 0) {
            return MeshData.empty();
        }
        float[] array = new float[instanceCount * FLOATS_PER_INSTANCE];
        int index = 0;
        for (QuadInstance instance : instances) {
            array[index++] = instance.minX;
            array[index++] = instance.minY;
            array[index++] = instance.minZ;
            array[index++] = instance.maxX;
            array[index++] = instance.maxY;
            array[index++] = instance.maxZ;
            array[index++] = instance.axis;
            array[index++] = instance.positive ? 1.0f : 0.0f;
            array[index++] = instance.blockId;
        }
        return new MeshData(array, instanceCount);
    }

    private static void buildForAxisX(Chunk chunk, ChunkManager manager, List<QuadInstance> out, int worldX0, int worldZ0) {
        int width = Chunk.SZ;
        int height = Chunk.SY;
        int[] mask = new int[width * height];
        for (int x = 0; x <= Chunk.SX; x++) {
            for (int y = 0; y < Chunk.SY; y++) {
                for (int z = 0; z < Chunk.SZ; z++) {
                    int left = sample(chunk, manager, x - 1, y, z);
                    int right = sample(chunk, manager, x, y, z);
                    int value = 0;
                    if (left != Blocks.AIR && right == Blocks.AIR) {
                        value = left;
                    } else if (left == Blocks.AIR && right != Blocks.AIR) {
                        value = -right;
                    }
                    mask[z + y * width] = value;
                }
            }
            emitGreedyQuads(out, mask, width, height, 0, x, worldX0, worldZ0);
        }
    }

    private static void buildForAxisY(Chunk chunk, ChunkManager manager, List<QuadInstance> out, int worldX0, int worldZ0) {
        int width = Chunk.SX;
        int height = Chunk.SZ;
        int[] mask = new int[width * height];
        for (int y = 0; y <= Chunk.SY; y++) {
            for (int z = 0; z < Chunk.SZ; z++) {
                for (int x = 0; x < Chunk.SX; x++) {
                    int below = sample(chunk, manager, x, y - 1, z);
                    int above = sample(chunk, manager, x, y, z);
                    int value = 0;
                    if (below != Blocks.AIR && above == Blocks.AIR) {
                        value = below;
                    } else if (below == Blocks.AIR && above != Blocks.AIR) {
                        value = -above;
                    }
                    mask[x + z * width] = value;
                }
            }
            emitGreedyQuads(out, mask, width, height, 1, y, worldX0, worldZ0);
        }
    }

    private static void buildForAxisZ(Chunk chunk, ChunkManager manager, List<QuadInstance> out, int worldX0, int worldZ0) {
        int width = Chunk.SX;
        int height = Chunk.SY;
        int[] mask = new int[width * height];
        for (int z = 0; z <= Chunk.SZ; z++) {
            for (int y = 0; y < Chunk.SY; y++) {
                for (int x = 0; x < Chunk.SX; x++) {
                    int back = sample(chunk, manager, x, y, z - 1);
                    int front = sample(chunk, manager, x, y, z);
                    int value = 0;
                    if (back != Blocks.AIR && front == Blocks.AIR) {
                        value = back;
                    } else if (back == Blocks.AIR && front != Blocks.AIR) {
                        value = -front;
                    }
                    mask[x + y * width] = value;
                }
            }
            emitGreedyQuads(out, mask, width, height, 2, z, worldX0, worldZ0);
        }
    }

    private static void emitGreedyQuads(List<QuadInstance> out, int[] mask, int width, int height, int axis, int plane, int worldX0, int worldZ0) {
        for (int j = 0; j < height; j++) {
            int i = 0;
            while (i < width) {
                int idx = i + j * width;
                int c = mask[idx];
                if (c == 0) {
                    i++;
                    continue;
                }
                int w = 1;
                while (i + w < width && mask[idx + w] == c) {
                    w++;
                }
                int h = 1;
                outer:
                for (; j + h < height; h++) {
                    for (int k = 0; k < w; k++) {
                        if (mask[idx + k + h * width] != c) {
                            break outer;
                        }
                    }
                }
                boolean positive = c > 0;
                int blockId = positive ? c : -c;
                emitQuad(out, axis, positive, plane, i, j, i + w, j + h, blockId, worldX0, worldZ0);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        mask[idx + x + y * width] = 0;
                    }
                }
                i += w;
            }
        }
    }

    private static void emitQuad(List<QuadInstance> out, int axis, boolean positive, int plane, int u0, int v0, int u1, int v1, int blockId, int worldX0, int worldZ0) {
        float minX, minY, minZ, maxX, maxY, maxZ;
        if (axis == 0) {
            float x = worldX0 + plane;
            float y0 = v0;
            float y1 = v1;
            float z0 = worldZ0 + u0;
            float z1 = worldZ0 + u1;
            minX = x;
            maxX = x;
            minY = Math.min(y0, y1);
            maxY = Math.max(y0, y1);
            minZ = Math.min(z0, z1);
            maxZ = Math.max(z0, z1);
        } else if (axis == 1) {
            float y = plane;
            float x0 = worldX0 + u0;
            float x1 = worldX0 + u1;
            float z0 = worldZ0 + v0;
            float z1 = worldZ0 + v1;
            minX = Math.min(x0, x1);
            maxX = Math.max(x0, x1);
            minY = y;
            maxY = y;
            minZ = Math.min(z0, z1);
            maxZ = Math.max(z0, z1);
        } else {
            float z = worldZ0 + plane;
            float x0 = worldX0 + u0;
            float x1 = worldX0 + u1;
            float y0 = v0;
            float y1 = v1;
            minX = Math.min(x0, x1);
            maxX = Math.max(x0, x1);
            minY = Math.min(y0, y1);
            maxY = Math.max(y0, y1);
            minZ = z;
            maxZ = z;
        }
        out.add(new QuadInstance(minX, minY, minZ, maxX, maxY, maxZ, axis, positive, blockId));
    }

    private static int sample(Chunk chunk, ChunkManager manager, int x, int y, int z) {
        if (y < 0 || y >= Chunk.SY) {
            return Blocks.AIR;
        }
        if (x >= 0 && x < Chunk.SX && z >= 0 && z < Chunk.SZ) {
            return chunk.get(x, y, z);
        }
        if (manager == null) {
            return Blocks.AIR;
        }
        int worldX = chunk.pos().cx() * Chunk.SX + x;
        int worldZ = chunk.pos().cz() * Chunk.SZ + z;
        ChunkPos neighborPos = new ChunkPos(Math.floorDiv(worldX, Chunk.SX), Math.floorDiv(worldZ, Chunk.SZ));
        Chunk neighbor = manager.getIfLoaded(neighborPos);
        if (neighbor == null) {
            return Blocks.AIR;
        }
        int localX = Math.floorMod(worldX, Chunk.SX);
        int localZ = Math.floorMod(worldZ, Chunk.SZ);
        return neighbor.get(localX, y, localZ);
    }

    private record QuadInstance(float minX, float minY, float minZ,
                                float maxX, float maxY, float maxZ,
                                int axis, boolean positive, int blockId) {
    }

    public record MeshData(float[] instanceData, int instanceCount) {
        public static MeshData empty() {
            return new MeshData(new float[0], 0);
        }
    }
}
