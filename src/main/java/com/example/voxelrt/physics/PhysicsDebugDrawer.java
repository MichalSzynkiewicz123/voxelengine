package com.example.voxelrt.physics;

import com.bulletphysics.linearmath.IDebugDraw;
import com.example.voxelrt.render.DebugRenderer;

import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects Bullet debug draw commands and forwards them to the {@link DebugRenderer} as line segments.
 */
public final class PhysicsDebugDrawer extends IDebugDraw {
    public static final int MODE_NONE = 0;
    public static final int MODE_WIREFRAME = 1;
    public static final int MODE_AABB = 2;
    public static final int MODE_WIREFRAME_AABB = MODE_WIREFRAME | MODE_AABB;

    private static final class Segment {
        final float x0, y0, z0;
        final float x1, y1, z1;
        final float r, g, b;

        Segment(Vector3f from, Vector3f to, Vector3f color) {
            this.x0 = from.x;
            this.y0 = from.y;
            this.z0 = from.z;
            this.x1 = to.x;
            this.y1 = to.y;
            this.z1 = to.z;
            this.r = clamp(color.x);
            this.g = clamp(color.y);
            this.b = clamp(color.z);
        }

        private static float clamp(float v) {
            if (v < 0f) return 0f;
            if (v > 1f) return 1f;
            return v;
        }
    }

    private final List<Segment> segments = new ArrayList<>();
    private int debugMode = MODE_NONE;

    public void beginFrame() {
        segments.clear();
    }

    public void flush(DebugRenderer renderer) {
        if (renderer == null || segments.isEmpty()) {
            segments.clear();
            return;
        }
        for (Segment segment : segments) {
            renderer.addLine(segment.x0, segment.y0, segment.z0,
                    segment.x1, segment.y1, segment.z1,
                    segment.r, segment.g, segment.b, 1f);
        }
        segments.clear();
    }

    @Override
    public void drawLine(Vector3f from, Vector3f to, Vector3f color) {
        if (from == null || to == null || color == null) {
            return;
        }
        segments.add(new Segment(from, to, color));
    }

    @Override
    public void drawContactPoint(Vector3f pointOnB, Vector3f normalOnB, float distance, int lifeTime, Vector3f color) {
        if (pointOnB == null || normalOnB == null) {
            return;
        }
        Vector3f end = new Vector3f(normalOnB);
        end.scale(distance * 0.5f);
        end.add(pointOnB);
        drawLine(pointOnB, end, color != null ? color : new Vector3f(1f, 0f, 0f));
    }

    @Override
    public void reportErrorWarning(String warningString) {
        System.err.println("[PhysicsDebug] " + warningString);
    }

    @Override
    public void draw3dText(Vector3f location, String textString) {
        // Not required for now.
    }

    @Override
    public void setDebugMode(int debugMode) {
        this.debugMode = debugMode;
    }

    @Override
    public int getDebugMode() {
        return debugMode;
    }
}
