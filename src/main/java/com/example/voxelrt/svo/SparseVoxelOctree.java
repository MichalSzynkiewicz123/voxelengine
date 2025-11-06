package com.example.voxelrt.svo;

import com.example.voxelrt.Blocks;
import com.example.voxelrt.Chunk;

import java.util.Objects;

/**
 * Sparse voxel representation that stores only non-uniform regions of a chunk.
 * <p>
 * The octree subdivides the chunk volume until a node contains a uniform block
 * value. Leaves therefore represent ranges of voxels that share the same block
 * identifier while interior nodes simply point at eight child regions. The
 * implementation works with the asymmetric chunk dimensions used by the engine
 * (16×256×16) by skipping empty child ranges when an axis has been fully
 * resolved.
 */
public final class SparseVoxelOctree {
    private static final int ESTIMATED_NODE_BYTES = 32;
    private final Node root;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    private SparseVoxelOctree(Node root, int sizeX, int sizeY, int sizeZ) {
        this.root = root;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
    }

    /**
     * Builds an octree snapshot from the current state of the provided chunk.
     */
    public static SparseVoxelOctree fromChunk(Chunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        Node root = buildNode(chunk, 0, Chunk.SX, 0, Chunk.SY, 0, Chunk.SZ);
        return new SparseVoxelOctree(root, Chunk.SX, Chunk.SY, Chunk.SZ);
    }

    /**
     * Returns the number of octree nodes that were generated for this snapshot.
     */
    public int nodeCount() {
        return root == null ? 0 : root.nodeCount;
    }

    /**
     * Returns an estimate of the number of bytes required to store this tree.
     * <p>
     * The estimate assumes a modest per-node overhead and is only used as a
     * heuristic when deciding whether to keep a snapshot instead of the dense
     * chunk representation.
     */
    public int estimateMemoryUsageBytes() {
        return nodeCount() * ESTIMATED_NODE_BYTES;
    }

    /**
     * Returns the number of non-air voxels represented by this snapshot.
     */
    public int nonAirVoxelCount() {
        return root == null ? 0 : root.nonAir;
    }

    /**
     * Returns whether the octree only contains air.
     */
    public boolean isAllAir() {
        return nonAirVoxelCount() == 0;
    }

    /**
     * Writes the voxel data represented by this octree into the provided chunk.
     * <p>
     * The chunk is assumed to have been reset (no sections allocated). Only
     * non-air voxels are written which keeps the array-based representation as
     * sparse as possible.
     */
    public void applyToChunk(Chunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        if (root == null || root.nonAir == 0) {
            return;
        }
        fillChunk(chunk, root, 0, sizeX, 0, sizeY, 0, sizeZ);
    }

    private static Node buildNode(Chunk chunk, int x0, int x1, int y0, int y1, int z0, int z1) {
        int sample = chunk.get(x0, y0, z0);
        boolean uniform = true;
        outer:
        for (int y = y0; y < y1; y++) {
            for (int z = z0; z < z1; z++) {
                for (int x = x0; x < x1; x++) {
                    int value = chunk.get(x, y, z);
                    if (value != sample) {
                        uniform = false;
                        break outer;
                    }
                }
            }
        }
        if (uniform) {
            int volume = (x1 - x0) * (y1 - y0) * (z1 - z0);
            int nonAir = sample == Blocks.AIR ? 0 : volume;
            return new Node(sample, nonAir);
        }

        Node[] children = new Node[8];
        int totalNonAir = 0;
        int totalNodes = 1; // count current node
        int midX = midpoint(x0, x1);
        int midY = midpoint(y0, y1);
        int midZ = midpoint(z0, z1);
        for (int yi = 0; yi < 2; yi++) {
            int cy0 = yi == 0 ? y0 : midY;
            int cy1 = yi == 0 ? midY : y1;
            if (cy1 <= cy0) continue;
            for (int zi = 0; zi < 2; zi++) {
                int cz0 = zi == 0 ? z0 : midZ;
                int cz1 = zi == 0 ? midZ : z1;
                if (cz1 <= cz0) continue;
                for (int xi = 0; xi < 2; xi++) {
                    int cx0 = xi == 0 ? x0 : midX;
                    int cx1 = xi == 0 ? midX : x1;
                    if (cx1 <= cx0) continue;
                    int childIndex = childIndex(xi, yi, zi);
                    Node child = buildNode(chunk, cx0, cx1, cy0, cy1, cz0, cz1);
                    children[childIndex] = child;
                    totalNonAir += child.nonAir;
                    totalNodes += child.nodeCount;
                }
            }
        }
        return new Node(children, totalNonAir, totalNodes);
    }

    private static void fillChunk(Chunk chunk, Node node, int x0, int x1, int y0, int y1, int z0, int z1) {
        if (node.isLeaf()) {
            if (node.value == Blocks.AIR) {
                return;
            }
            for (int y = y0; y < y1; y++) {
                for (int z = z0; z < z1; z++) {
                    for (int x = x0; x < x1; x++) {
                        chunk.set(x, y, z, node.value);
                    }
                }
            }
            return;
        }

        int midX = midpoint(x0, x1);
        int midY = midpoint(y0, y1);
        int midZ = midpoint(z0, z1);
        for (int yi = 0; yi < 2; yi++) {
            int cy0 = yi == 0 ? y0 : midY;
            int cy1 = yi == 0 ? midY : y1;
            if (cy1 <= cy0) continue;
            for (int zi = 0; zi < 2; zi++) {
                int cz0 = zi == 0 ? z0 : midZ;
                int cz1 = zi == 0 ? midZ : z1;
                if (cz1 <= cz0) continue;
                for (int xi = 0; xi < 2; xi++) {
                    int cx0 = xi == 0 ? x0 : midX;
                    int cx1 = xi == 0 ? midX : x1;
                    if (cx1 <= cx0) continue;
                    int childIndex = childIndex(xi, yi, zi);
                    Node child = node.children[childIndex];
                    if (child != null) {
                        fillChunk(chunk, child, cx0, cx1, cy0, cy1, cz0, cz1);
                    }
                }
            }
        }
    }

    private static int midpoint(int start, int end) {
        int size = end - start;
        if (size <= 1) {
            return end;
        }
        return start + size / 2;
    }

    private static int childIndex(int xi, int yi, int zi) {
        return (xi << 2) | (yi << 1) | zi;
    }

    private static final class Node {
        final int value;
        final Node[] children;
        final int nonAir;
        final int nodeCount;

        Node(int value, int nonAir) {
            this.value = value;
            this.children = null;
            this.nonAir = nonAir;
            this.nodeCount = 1;
        }

        Node(Node[] children, int nonAir, int nodeCount) {
            this.value = -1;
            this.children = children;
            this.nonAir = nonAir;
            this.nodeCount = nodeCount;
        }

        boolean isLeaf() {
            return children == null;
        }
    }
}
