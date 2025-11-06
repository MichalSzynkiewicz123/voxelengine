package com.example.voxelrt;

import com.example.voxelrt.mesh.ChunkMesh;

import java.util.Random;

public class Chunk {
    public static final int SX = 16, SY = 256, SZ = 16;
    public static final int TOTAL_VOXELS = SX * SY * SZ;
    private static final int SECTION_HEIGHT = 16;
    private static final int SECTION_COUNT = (SY + SECTION_HEIGHT - 1) / SECTION_HEIGHT;
    private ChunkPos pos;
    private final Section[] sections = new Section[SECTION_COUNT];
    private volatile boolean meshDirty = true;
    private volatile ChunkMesh mesh;

    public Chunk(ChunkPos p) {
        this.pos = p;
    }

    public ChunkPos pos() {
        return pos;
    }

    void reset(ChunkPos newPos) {
        this.pos = newPos;
        this.meshDirty = true;
        this.mesh = null;
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
            markMeshDirty();
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
        markMeshDirty();
    }

    public void fill(WorldGenerator gen) {
        clearSections();
        int wx0 = pos.cx() * SX, wz0 = pos.cz() * SZ;
        WorldGenerator.Column[] columns = new WorldGenerator.Column[SX * SZ];
        for (int z = 0; z < SZ; z++) {
            int wz = wz0 + z;
            for (int x = 0; x < SX; x++) {
                int wx = wx0 + x;
                WorldGenerator.Column column = gen.sampleColumn(wx, wz);
                columns[z * SX + x] = column;
                int topY = Math.min(column.groundHeight(), SY - 1);
                for (int y = 0; y <= topY; y++) {
                    int block = gen.sampleBlock(column, wx, y, wz);
                    if (block != Blocks.AIR) {
                        set(x, y, z, block);
                    }
                }
            }
        }
        populateStructures(gen, columns);
        markMeshDirty();
    }

    public DenseData captureDenseData() {
        byte[] voxels = new byte[TOTAL_VOXELS];
        int sectionSize = SX * SECTION_HEIGHT * SZ;
        int nonAir = 0;
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            Section section = sections[sectionIndex];
            if (section == null) {
                continue;
            }
            System.arraycopy(section.voxels, 0, voxels, sectionIndex * sectionSize, section.voxels.length);
            nonAir += section.nonAir;
        }
        return new DenseData(voxels, nonAir);
    }

    public void applyDenseData(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Chunk dense data cannot be null");
        }
        if (data.length != TOTAL_VOXELS) {
            throw new IllegalArgumentException("Unexpected dense chunk data length: " + data.length + " (expected " + TOTAL_VOXELS + ")");
        }
        int sectionSize = SX * SECTION_HEIGHT * SZ;
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            Section section = sections[sectionIndex];
            int offset = sectionIndex * sectionSize;
            int nonAir = 0;
            for (int i = 0; i < sectionSize; i++) {
                if ((data[offset + i] & 0xFF) != Blocks.AIR) {
                    nonAir++;
                }
            }
            if (nonAir == 0) {
                if (section != null) {
                    section.clear();
                    sections[sectionIndex] = null;
                }
                continue;
            }
            if (section == null) {
                section = new Section();
                sections[sectionIndex] = section;
            }
            section.nonAir = nonAir;
            System.arraycopy(data, offset, section.voxels, 0, section.voxels.length);
        }
        markMeshDirty();
    }

    private void populateStructures(WorldGenerator gen, WorldGenerator.Column[] columns) {
        Random rng = gen.randomForChunk(pos.cx(), pos.cz());
        for (int z = 0; z < SZ; z++) {
            for (int x = 0; x < SX; x++) {
                WorldGenerator.Column column = columns[z * SX + x];
                if (column == null) {
                    continue;
                }
                int ground = column.groundHeight();
                if (ground <= 0 || ground >= SY - 6) {
                    continue;
                }
                int localY = Math.min(ground, SY - 1);
                int surface = get(x, localY, z);
                if (surface == Blocks.AIR) {
                    continue;
                }
                switch (column.biome()) {
                    case FOREST -> {
                        if (surface == Blocks.GRASS && rng.nextFloat() < 0.08f) {
                            placeTree(rng, x, localY + 1, z);
                        } else if (surface == Blocks.GRASS && rng.nextFloat() < 0.10f) {
                            placeShrub(rng, x, localY + 1, z);
                        }
                    }
                    case PLAINS -> {
                        if (surface == Blocks.GRASS && rng.nextFloat() < 0.025f) {
                            placeTree(rng, x, localY + 1, z);
                        } else if (surface == Blocks.GRASS && rng.nextFloat() < 0.06f) {
                            placeShrub(rng, x, localY + 1, z);
                        }
                    }
                    case DESERT -> {
                        if (surface == Blocks.SAND && rng.nextFloat() < 0.05f) {
                            placeCactus(rng, x, localY + 1, z);
                        }
                    }
                    case MOUNTAINS -> {
                        if (surface == Blocks.STONE && rng.nextFloat() < 0.04f) {
                            placeRock(rng, x, localY + 1, z);
                        }
                    }
                    case TUNDRA -> {
                        if (surface == Blocks.SNOW && rng.nextFloat() < 0.02f) {
                            placeRock(rng, x, localY + 1, z);
                        }
                    }
                }
            }
        }
    }

    private void placeRock(Random rng, int x, int y, int z) {
        int radius = 1 + rng.nextInt(2);
        for (int dy = 0; dy <= radius; dy++) {
            int ay = y + dy;
            if (ay < 0 || ay >= SY) continue;
            for (int dx = -radius; dx <= radius; dx++) {
                int ax = x + dx;
                if (ax < 0 || ax >= SX) continue;
                for (int dz = -radius; dz <= radius; dz++) {
                    int az = z + dz;
                    if (az < 0 || az >= SZ) continue;
                    int dist2 = dx * dx + dz * dz + dy * dy;
                    if (dist2 <= radius * radius + rng.nextInt(2)) {
                        if (get(ax, ay, az) == Blocks.AIR) {
                            set(ax, ay, az, Blocks.STONE);
                        }
                    }
                }
            }
        }
    }

    private void placeShrub(Random rng, int x, int y, int z) {
        if (y <= 0 || y >= SY) return;
        if (get(x, y, z) != Blocks.AIR) return;
        set(x, y, z, Blocks.LOG);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int ax = x + dx;
                int az = z + dz;
                if (ax < 0 || ax >= SX || az < 0 || az >= SZ) continue;
                int ay = y + 1;
                if (ay >= SY) continue;
                if (java.lang.Math.abs(dx) == 1 && java.lang.Math.abs(dz) == 1 && rng.nextBoolean()) {
                    continue;
                }
                if (get(ax, ay, az) == Blocks.AIR) {
                    set(ax, ay, az, Blocks.LEAVES);
                }
            }
        }
        if (y + 2 < SY && get(x, y + 2, z) == Blocks.AIR) {
            set(x, y + 2, z, Blocks.LEAVES);
        }
    }

    private void placeCactus(Random rng, int x, int y, int z) {
        if (x <= 0 || x >= SX - 1 || z <= 0 || z >= SZ - 1) {
            return;
        }
        int height = 2 + rng.nextInt(3);
        if (y + height >= SY) {
            return;
        }
        for (int i = 0; i < height; i++) {
            if (get(x, y + i, z) != Blocks.AIR) {
                return;
            }
        }
        for (int i = 0; i < height; i++) {
            set(x, y + i, z, Blocks.CACTUS);
        }
    }

    private void placeTree(Random rng, int x, int y, int z) {
        if (x <= 1 || x >= SX - 2 || z <= 1 || z >= SZ - 2) {
            return;
        }
        int height = 4 + rng.nextInt(3);
        if (y + height + 2 >= SY) {
            return;
        }
        for (int i = 0; i < height + 2; i++) {
            int ay = y + i;
            if (get(x, ay, z) != Blocks.AIR) {
                return;
            }
        }

        int leafBase = y + height - 2;
        for (int dy = -2; dy <= 2; dy++) {
            int ay = leafBase + dy;
            if (ay < 0 || ay >= SY) continue;
            int radius = 2 - java.lang.Math.abs(dy);
            for (int dx = -radius; dx <= radius; dx++) {
                int ax = x + dx;
                if (ax < 0 || ax >= SX) return;
                for (int dz = -radius; dz <= radius; dz++) {
                    int az = z + dz;
                    if (az < 0 || az >= SZ) return;
                    if (java.lang.Math.abs(dx) == radius && java.lang.Math.abs(dz) == radius && rng.nextBoolean()) {
                        continue;
                    }
                    int existing = get(ax, ay, az);
                    if (existing != Blocks.AIR && existing != Blocks.LEAVES) {
                        return;
                    }
                }
            }
        }

        for (int i = 0; i < height; i++) {
            set(x, y + i, z, Blocks.LOG);
        }

        for (int dy = -2; dy <= 2; dy++) {
            int ay = leafBase + dy;
            if (ay < 0 || ay >= SY) continue;
            int radius = 2 - java.lang.Math.abs(dy);
            for (int dx = -radius; dx <= radius; dx++) {
                int ax = x + dx;
                if (ax < 0 || ax >= SX) continue;
                for (int dz = -radius; dz <= radius; dz++) {
                    int az = z + dz;
                    if (az < 0 || az >= SZ) continue;
                    if (java.lang.Math.abs(dx) == radius && java.lang.Math.abs(dz) == radius && rng.nextBoolean()) {
                        continue;
                    }
                    if (get(ax, ay, az) == Blocks.AIR) {
                        set(ax, ay, az, Blocks.LEAVES);
                    }
                }
            }
        }

        if (y + height < SY) {
            set(x, y + height, z, Blocks.LEAVES);
        }
    }

    private void clearSections() {
        for (int i = 0; i < sections.length; i++) {
            Section section = sections[i];
            if (section != null) {
                section.clear();
            }
        }
        markMeshDirty();
    }

    void prepareForPool() {
        clearSections();
        this.pos = null;
        this.mesh = null;
        this.meshDirty = true;
    }

    public boolean isMeshDirty() {
        return meshDirty;
    }

    public void markMeshDirty() {
        meshDirty = true;
    }

    public void clearMeshDirty() {
        meshDirty = false;
    }

    public ChunkMesh mesh() {
        return mesh;
    }

    public void setMesh(ChunkMesh mesh) {
        this.mesh = mesh;
    }

    public void releaseMesh() {
        ChunkMesh current = this.mesh;
        if (current != null) {
            current.destroy();
            this.mesh = null;
        }
        meshDirty = true;
    }

    private static final class Section {
        final byte[] voxels = new byte[SX * SECTION_HEIGHT * SZ];
        int nonAir = 0;

        void clear() {
            nonAir = 0;
            java.util.Arrays.fill(voxels, (byte) 0);
        }
    }

    public record DenseData(byte[] voxels, int nonAir) {
    }
}
