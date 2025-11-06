package com.example.voxelrt.camera;

import com.example.voxelrt.world.Blocks;
import com.example.voxelrt.world.Chunk;
import com.example.voxelrt.world.ChunkManager;
import org.joml.Vector3f;

/**
 * Performs voxel-accurate ray marching using a 3D DDA (Digital Differential Analyzer) algorithm.
 */
public class Raycast {
    public static class Hit {
        public int x, y, z;
        public int nx, ny, nz;
    }

    /**
     * Casts a ray through the world and returns the first non-air block along the ray.
     *
     * @param cm      chunk manager used to sample blocks along the ray
     * @param origin  starting position in world space
     * @param dir     direction of the ray (does not need to be normalized)
     * @param maxDist maximum distance to march before giving up
     * @return details about the block that was hit or {@code null} when no solid block was found
     */
    public static Hit raycast(ChunkManager cm, Vector3f origin, Vector3f dir, float maxDist) {
        Vector3f rd = new Vector3f(dir).normalize();
        int x = (int) java.lang.Math.floor(origin.x);
        int y = (int) java.lang.Math.floor(origin.y);
        int z = (int) java.lang.Math.floor(origin.z);
        int sx = rd.x > 0 ? 1 : -1;
        int sy = rd.y > 0 ? 1 : -1;
        int sz = rd.z > 0 ? 1 : -1;
        float tMaxX = intBound(origin.x, rd.x);
        float tMaxY = intBound(origin.y, rd.y);
        float tMaxZ = intBound(origin.z, rd.z);
        float tDeltaX = sx / rd.x;
        float tDeltaY = sy / rd.y;
        float tDeltaZ = sz / rd.z;
        int nx = 0, ny = 0, nz = 0;
        float t = 0f;
        while (t <= maxDist) {
            if (y >= 0 && y < Chunk.SY && cm.sample(x, y, z) != Blocks.AIR) {
                Hit h = new Hit();
                h.x = x;
                h.y = y;
                h.z = z;
                h.nx = nx;
                h.ny = ny;
                h.nz = nz;
                return h;
            }
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    x += sx;
                    t = tMaxX;
                    tMaxX += tDeltaX;
                    nx = -sx;
                    ny = 0;
                    nz = 0;
                } else {
                    z += sz;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                    nx = 0;
                    ny = 0;
                    nz = -sz;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    y += sy;
                    t = tMaxY;
                    tMaxY += tDeltaY;
                    nx = 0;
                    ny = -sy;
                    nz = 0;
                } else {
                    z += sz;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                    nx = 0;
                    ny = 0;
                    nz = -sz;
                }
            }
        }
        return null;
    }

    private static float intBound(float s, float ds) {
        return ds > 0
                ? ((float) java.lang.Math.floor(s + 1) - s) / ds
                : (s - (float) java.lang.Math.floor(s)) / (-ds);
    }
}
