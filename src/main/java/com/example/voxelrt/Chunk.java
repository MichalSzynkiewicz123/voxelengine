package com.example.voxelrt;

public class Chunk {
    public static final int SX = 16, SY = 256, SZ = 16;
    private static final int SECTION_HEIGHT = 16;
    private static final int SECTION_COUNT = (SY + SECTION_HEIGHT - 1) / SECTION_HEIGHT;
    private ChunkPos pos;
    private final Section[] sections = new Section[SECTION_COUNT];

    public Chunk(ChunkPos p) {
        this.pos = p;
    }

    public ChunkPos pos() {
        return pos;
    }

    void reset(ChunkPos newPos) {
        this.pos = newPos;
    }

    private static byte encode(int blockId) {
        if ((blockId & 0xFFFFFF00) != 0) {
            throw new IllegalArgumentException("Block ID out of range for byte storage: " + blockId);
        }
        return (byte) blockId;
    }

    private static int localIndex(int x, int y, int z) {
        return x + (z * SX) + (y * SX * SZ);
    }

    private Section section(int y) {
        int sectionIndex = y / SECTION_HEIGHT;
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return null;
        }
        return sections[sectionIndex];
    }

    private Section ensureSection(int y) {
        int sectionIndex = y / SECTION_HEIGHT;
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return null;
        }
        Section section = sections[sectionIndex];
        if (section == null) {
            section = new Section();
            sections[sectionIndex] = section;
        }
        return section;
    }

    private void maybeReleaseSection(int sectionIndex) {
        Section section = sections[sectionIndex];
        if (section != null && section.nonAir == 0) {
            sections[sectionIndex] = null;
        }
    }

    public int get(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= SX || y >= SY || z >= SZ) return Blocks.AIR;
        Section section = section(y);
        if (section == null) {
            return Blocks.AIR;
        }
        int localY = y % SECTION_HEIGHT;
        return section.voxels[localIndex(x, localY, z)] & 0xFF;
    }

    public void set(int x, int y, int z, int b) {
        if ((x | y | z) < 0 || x >= SX || y >= SY || z >= SZ) return;
        if (b == Blocks.AIR) {
            int sectionIndex = y / SECTION_HEIGHT;
            Section section = section(y);
            if (section == null) {
                return;
            }
            int localY = y % SECTION_HEIGHT;
            int idx = localIndex(x, localY, z);
            if ((section.voxels[idx] & 0xFF) == 0) {
                return;
            }
            section.voxels[idx] = 0;
            section.nonAir--;
            maybeReleaseSection(sectionIndex);
            return;
        }

        Section section = ensureSection(y);
        if (section == null) {
            return;
        }
        int localY = y % SECTION_HEIGHT;
        int idx = localIndex(x, localY, z);
        byte encoded = encode(b);
        if (section.voxels[idx] == encoded) {
            return;
        }
        if ((section.voxels[idx] & 0xFF) == 0) {
            section.nonAir++;
        }
        section.voxels[idx] = encoded;
    }

    public void fill(WorldGenerator gen) {
        clearSections();
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
                    set(x, y, z, block);
                }
            }
        }
    }

    private void clearSections() {
        for (int i = 0; i < sections.length; i++) {
            Section section = sections[i];
            if (section != null) {
                section.clear();
            }
        }
    }

    void prepareForPool() {
        clearSections();
        this.pos = null;
    }

    private static final class Section {
        final byte[] voxels = new byte[SX * SECTION_HEIGHT * SZ];
        int nonAir = 0;

        void clear() {
            nonAir = 0;
            java.util.Arrays.fill(voxels, (byte) 0);
        }
    }
}
