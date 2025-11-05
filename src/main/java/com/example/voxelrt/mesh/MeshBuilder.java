package com.example.voxelrt.mesh;

import com.example.voxelrt.Blocks;
import com.example.voxelrt.Chunk;
import com.example.voxelrt.ChunkManager;
import com.example.voxelrt.ChunkPos;

import java.util.ArrayList;
import java.util.List;

public final class MeshBuilder {
    private static final int FLOATS_PER_VERTEX = 7;

    private MeshBuilder() {
    }

    public static MeshData build(Chunk chunk, ChunkManager manager) {
        List<Float> vertices = new ArrayList<>();
        int vertexCount = 0;
        int worldX0 = chunk.pos().cx() * Chunk.SX;
        int worldZ0 = chunk.pos().cz() * Chunk.SZ;

        buildForAxisX(chunk, manager, vertices, worldX0, worldZ0);
        buildForAxisY(chunk, manager, vertices, worldX0, worldZ0);
        buildForAxisZ(chunk, manager, vertices, worldX0, worldZ0);

        vertexCount = vertices.size() / FLOATS_PER_VERTEX;
        if (vertexCount == 0) {
            return MeshData.empty();
        }
        float[] array = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            array[i] = vertices.get(i);
        }
        return new MeshData(array, vertexCount);
    }

    private static void buildForAxisX(Chunk chunk, ChunkManager manager, List<Float> out, int worldX0, int worldZ0) {
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

    private static void buildForAxisY(Chunk chunk, ChunkManager manager, List<Float> out, int worldX0, int worldZ0) {
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

    private static void buildForAxisZ(Chunk chunk, ChunkManager manager, List<Float> out, int worldX0, int worldZ0) {
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

    private static void emitGreedyQuads(List<Float> out, int[] mask, int width, int height, int axis, int plane, int worldX0, int worldZ0) {
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

    private static void emitQuad(List<Float> out, int axis, boolean positive, int plane, int u0, int v0, int u1, int v1, int blockId, int worldX0, int worldZ0) {
        float nx = 0f, ny = 0f, nz = 0f;
        switch (axis) {
            case 0 -> nx = positive ? 1f : -1f;
            case 1 -> ny = positive ? 1f : -1f;
            case 2 -> nz = positive ? 1f : -1f;
            default -> {
            }
        }
        float x0, x1, y0, y1, z0, z1;
        if (axis == 0) {
            float x = worldX0 + plane;
            y0 = v0;
            y1 = v1;
            z0 = worldZ0 + u0;
            z1 = worldZ0 + u1;
            if (positive) {
                addVertex(out, x, y0, z0, nx, ny, nz, blockId);
                addVertex(out, x, y1, z0, nx, ny, nz, blockId);
                addVertex(out, x, y1, z1, nx, ny, nz, blockId);
                addVertex(out, x, y0, z0, nx, ny, nz, blockId);
                addVertex(out, x, y1, z1, nx, ny, nz, blockId);
                addVertex(out, x, y0, z1, nx, ny, nz, blockId);
            } else {
                addVertex(out, x, y0, z1, nx, ny, nz, blockId);
                addVertex(out, x, y1, z1, nx, ny, nz, blockId);
                addVertex(out, x, y1, z0, nx, ny, nz, blockId);
                addVertex(out, x, y0, z1, nx, ny, nz, blockId);
                addVertex(out, x, y1, z0, nx, ny, nz, blockId);
                addVertex(out, x, y0, z0, nx, ny, nz, blockId);
            }
        } else if (axis == 1) {
            float y = plane;
            x0 = worldX0 + u0;
            x1 = worldX0 + u1;
            z0 = worldZ0 + v0;
            z1 = worldZ0 + v1;
            if (positive) {
                addVertex(out, x0, y, z0, nx, ny, nz, blockId);
                addVertex(out, x0, y, z1, nx, ny, nz, blockId);
                addVertex(out, x1, y, z1, nx, ny, nz, blockId);
                addVertex(out, x0, y, z0, nx, ny, nz, blockId);
                addVertex(out, x1, y, z1, nx, ny, nz, blockId);
                addVertex(out, x1, y, z0, nx, ny, nz, blockId);
            } else {
                addVertex(out, x0, y, z1, nx, ny, nz, blockId);
                addVertex(out, x0, y, z0, nx, ny, nz, blockId);
                addVertex(out, x1, y, z0, nx, ny, nz, blockId);
                addVertex(out, x0, y, z1, nx, ny, nz, blockId);
                addVertex(out, x1, y, z0, nx, ny, nz, blockId);
                addVertex(out, x1, y, z1, nx, ny, nz, blockId);
            }
        } else {
            float z = worldZ0 + plane;
            x0 = worldX0 + u0;
            x1 = worldX0 + u1;
            y0 = v0;
            y1 = v1;
            if (positive) {
                addVertex(out, x0, y0, z, nx, ny, nz, blockId);
                addVertex(out, x1, y0, z, nx, ny, nz, blockId);
                addVertex(out, x1, y1, z, nx, ny, nz, blockId);
                addVertex(out, x0, y0, z, nx, ny, nz, blockId);
                addVertex(out, x1, y1, z, nx, ny, nz, blockId);
                addVertex(out, x0, y1, z, nx, ny, nz, blockId);
            } else {
                addVertex(out, x1, y0, z, nx, ny, nz, blockId);
                addVertex(out, x0, y0, z, nx, ny, nz, blockId);
                addVertex(out, x0, y1, z, nx, ny, nz, blockId);
                addVertex(out, x1, y0, z, nx, ny, nz, blockId);
                addVertex(out, x0, y1, z, nx, ny, nz, blockId);
                addVertex(out, x1, y1, z, nx, ny, nz, blockId);
            }
        }
    }

    private static void addVertex(List<Float> out, float x, float y, float z, float nx, float ny, float nz, int blockId) {
        out.add(x);
        out.add(y);
        out.add(z);
        out.add(nx);
        out.add(ny);
        out.add(nz);
        out.add((float) blockId);
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

    public record MeshData(float[] vertices, int vertexCount) {
        public static MeshData empty() {
            return new MeshData(new float[0], 0);
        }
    }
}
