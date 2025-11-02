package com.example.voxelrt;

import org.joml.Vector3f;

public class Physics {
    public static boolean isSolid(ChunkManager cm, int x, int y, int z) {
        if (y < 0 || y >= Chunk.SY) return true;
        return cm.sample(x, y, z) != Blocks.AIR;
    }

    public static void collideAABB(ChunkManager cm, Vector3f pos, Vector3f vel, float w, float h, float dt) {
        int steps = 4;
        Vector3f step = new Vector3f(vel).mul(dt / steps);
        for (int i = 0; i < steps; i++) {
            pos.x += step.x;
            if (overlaps(cm, pos, w, h)) {
                pos.x -= step.x;
                step.x = 0;
                vel.x = 0;
            }
            pos.y += step.y;
            if (overlaps(cm, pos, w, h)) {
                pos.y -= step.y;
                step.y = 0;
                vel.y = 0;
            }
            pos.z += step.z;
            if (overlaps(cm, pos, w, h)) {
                pos.z -= step.z;
                step.z = 0;
                vel.z = 0;
            }
        }
    }

    private static boolean overlaps(ChunkManager cm, Vector3f pos, float w, float h) {
        float r = w * 0.5f;
        int minX = (int) java.lang.Math.floor(pos.x - r), maxX = (int) java.lang.Math.floor(pos.x + r);
        int minY = (int) java.lang.Math.floor(pos.y), maxY = (int) java.lang.Math.floor(pos.y + h);
        int minZ = (int) java.lang.Math.floor(pos.z - r), maxZ = (int) java.lang.Math.floor(pos.z + r);
        for (int z = minZ; z <= maxZ; z++)
            for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) if (isSolid(cm, x, y, z)) return true;
        return false;
    }
}