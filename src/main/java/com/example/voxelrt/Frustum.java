package com.example.voxelrt;

import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * View frustum expressed as a set of plane equations that can be used to cull axis-aligned boxes.
 * <p>
 * The frustum is extracted from a combined view-projection matrix and stores its six clipping planes
 * in world space. Chunks or other voxel structures can be tested against the frustum to determine
 * whether they are potentially visible to the camera.
 */
public final class Frustum {
    private final Vector4f[] planes = new Vector4f[6];

    private Frustum() {
        for (int i = 0; i < planes.length; i++) {
            planes[i] = new Vector4f();
        }
    }

    /**
     * Builds a frustum by extracting the clipping planes from the supplied view-projection matrix.
     *
     * @param viewProjection combined view-projection matrix (projection * view)
     * @return frustum describing the camera's visible volume
     */
    public static Frustum fromMatrix(Matrix4f viewProjection) {
        Frustum f = new Frustum();

        float m00 = viewProjection.m00, m01 = viewProjection.m01, m02 = viewProjection.m02, m03 = viewProjection.m03;
        float m10 = viewProjection.m10, m11 = viewProjection.m11, m12 = viewProjection.m12, m13 = viewProjection.m13;
        float m20 = viewProjection.m20, m21 = viewProjection.m21, m22 = viewProjection.m22, m23 = viewProjection.m23;
        float m30 = viewProjection.m30, m31 = viewProjection.m31, m32 = viewProjection.m32, m33 = viewProjection.m33;

        // Left, Right, Bottom, Top, Near, Far planes
        f.setPlane(0, m03 + m00, m13 + m10, m23 + m20, m33 + m30);
        f.setPlane(1, m03 - m00, m13 - m10, m23 - m20, m33 - m30);
        f.setPlane(2, m03 + m01, m13 + m11, m23 + m21, m33 + m31);
        f.setPlane(3, m03 - m01, m13 - m11, m23 - m21, m33 - m31);
        f.setPlane(4, m03 + m02, m13 + m12, m23 + m22, m33 + m32);
        f.setPlane(5, m03 - m02, m13 - m12, m23 - m22, m33 - m32);

        return f;
    }

    private void setPlane(int idx, float a, float b, float c, float d) {
        Vector4f plane = planes[idx];
        float invLen = (float) (1.0 / java.lang.Math.sqrt(a * a + b * b + c * c));
        plane.set(a * invLen, b * invLen, c * invLen, d * invLen);
    }

    /**
     * Tests whether an axis-aligned bounding box intersects or lies inside the frustum.
     *
     * @param minX minimum x coordinate of the box
     * @param minY minimum y coordinate of the box
     * @param minZ minimum z coordinate of the box
     * @param maxX maximum x coordinate of the box
     * @param maxY maximum y coordinate of the box
     * @param maxZ maximum z coordinate of the box
     * @return {@code true} if the box is potentially visible, {@code false} if it is completely outside
     */
    public boolean intersectsAABB(float minX, float minY, float minZ,
                                  float maxX, float maxY, float maxZ) {
        for (Vector4f plane : planes) {
            float px = plane.x >= 0 ? maxX : minX;
            float py = plane.y >= 0 ? maxY : minY;
            float pz = plane.z >= 0 ? maxZ : minZ;
            if (plane.x * px + plane.y * py + plane.z * pz + plane.w < 0) {
                return false;
            }
        }
        return true;
    }
}
