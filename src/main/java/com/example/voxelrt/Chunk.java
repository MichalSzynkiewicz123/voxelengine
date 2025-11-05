package com.example.voxelrt;

public class Chunk {
    public static final int SX = 16, SY = 256, SZ = 16;
    private static final int X_BITS = Integer.SIZE - Integer.numberOfLeadingZeros(SX - 1);
    private static final int Y_BITS = Integer.SIZE - Integer.numberOfLeadingZeros(SY - 1);
    private static final int Z_BITS = Integer.SIZE - Integer.numberOfLeadingZeros(SZ - 1);
    public final ChunkPos pos;
    private final int[] vox = new int[SX * SY * SZ];

    public Chunk(ChunkPos p) {
        this.pos = p;
    }

    private static int idx(int x, int y, int z) {
        int morton = 0;
        int bitPos = 0;
        int maxBits = Math.max(Math.max(X_BITS, Y_BITS), Z_BITS);
        for (int bit = 0; bit < maxBits; bit++) {
            if (bit < X_BITS) {
                morton |= ((x >> bit) & 1) << bitPos;
                bitPos++;
            }
            if (bit < Y_BITS) {
                morton |= ((y >> bit) & 1) << bitPos;
                bitPos++;
            }
            if (bit < Z_BITS) {
                morton |= ((z >> bit) & 1) << bitPos;
                bitPos++;
            }
        }
        return morton;
    }

    public int get(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= SX || y >= SY || z >= SZ) return Blocks.AIR;
        return vox[idx(x, y, z)];
    }

    public void set(int x, int y, int z, int b) {
        if ((x | y | z) < 0 || x >= SX || y >= SY || z >= SZ) return;
        vox[idx(x, y, z)] = b;
    }

    public void fill(WorldGenerator gen) {
        int wx0 = pos.cx() * SX, wz0 = pos.cz() * SZ;
        for (int z = 0; z < SZ; z++) {
            int wz = wz0 + z;
            for (int x = 0; x < SX; x++) {
                int wx = wx0 + x;
                WorldGenerator.Column column = gen.sampleColumn(wx, wz);
                int ground = column.groundHeight();
                int fillerStart = Math.max(0, ground - 3);
                int topY = Math.min(ground, SY - 1);

                for (int y = 0; y <= topY; y++) {
                    int block;
                    if (ground >= 0 && y == ground) {
                        block = column.surfaceBlock();
                    } else if (y >= fillerStart) {
                        block = column.fillerBlock();
                    } else {
                        block = column.lowerBlock();
                    }
                    vox[idx(x, y, z)] = block;
                }

                for (int y = Math.max(topY + 1, 0); y < SY; y++) {
                    vox[idx(x, y, z)] = Blocks.AIR;
                }
            }
        }
    }
}
