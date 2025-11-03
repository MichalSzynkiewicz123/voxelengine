package com.example.voxelrt;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Simple first-person camera that stores position and orientation in Euler angles.
 */
public class Camera {
    public Vector3f position;
    private float yawDeg = -90f, pitchDeg = 0f;

    public Camera(Vector3f start) {
        position = new Vector3f(start);
    }

    /**
     * Applies mouse deltas to the camera and clamps the pitch so the player cannot flip upside down.
     */
    public void addYawPitch(float dx, float dy) {
        yawDeg += dx * 0.15f;
        pitchDeg -= dy * 0.15f;
        if (pitchDeg > 89) pitchDeg = 89;
        if (pitchDeg < -89) pitchDeg = -89;
    }

    public float yawDeg() {
        return yawDeg;
    }

    public float pitchDeg() {
        return pitchDeg;
    }

    /**
     * Builds a look-at matrix using the current position and forward direction.
     */
    public Matrix4f viewMatrix() {
        Vector3f f = getForward();
        Vector3f t = new Vector3f(position).add(f);
        return new Matrix4f().lookAt(position, t, new Vector3f(0, 1, 0));
    }

    /**
     * Calculates a normalized forward direction vector from the yaw and pitch angles.
     */
    public Vector3f getForward() {
        float yaw = (float) java.lang.Math.toRadians(yawDeg);
        float pit = (float) java.lang.Math.toRadians(pitchDeg);
        float x = (float) (java.lang.Math.cos(yaw) * java.lang.Math.cos(pit));
        float y = (float) (java.lang.Math.sin(pit));
        float z = (float) (java.lang.Math.sin(yaw) * java.lang.Math.cos(pit));
        return new Vector3f(x, y, z).normalize();
    }
}
