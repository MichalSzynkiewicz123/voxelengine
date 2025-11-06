package com.example.voxelrt.render;

import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Central registry for dynamic lights. The manager keeps track of all active lights, removes
 * expired ones and exposes a culled list limited to the most relevant lights near the camera.
 */
public final class LightManager {
    private final List<DynamicLight> lights = new ArrayList<>();
    private final List<DynamicLight> scratch = new ArrayList<>();

    private Vector3fc lastReferencePosition = null;

    private final Comparator<DynamicLight> scoreComparator = (a, b) -> {
        float scoreA = brightnessScore(a, lastReferencePosition);
        float scoreB = brightnessScore(b, lastReferencePosition);
        return Float.compare(scoreB, scoreA);
    };

    public DynamicLight addLight(DynamicLight light) {
        if (light == null) {
            throw new IllegalArgumentException("light cannot be null");
        }
        lights.add(light);
        return light;
    }

    public boolean removeLight(DynamicLight light) {
        return lights.remove(light);
    }

    public void clear() {
        lights.clear();
    }

    public List<DynamicLight> lights() {
        return Collections.unmodifiableList(lights);
    }

    public void update(float dt) {
        if (dt <= 0f || lights.isEmpty()) {
            return;
        }
        Iterator<DynamicLight> it = lights.iterator();
        while (it.hasNext()) {
            DynamicLight light = it.next();
            light.advanceTime(dt);
            if (light.isExpired()) {
                it.remove();
            }
        }
    }

    public void gatherActiveLights(Vector3fc referencePosition, int maxLights, List<DynamicLight> out) {
        if (out == null) {
            throw new IllegalArgumentException("out list cannot be null");
        }
        out.clear();
        if (referencePosition == null || maxLights <= 0 || lights.isEmpty()) {
            return;
        }
        scratch.clear();
        for (DynamicLight light : lights) {
            if (light == null || !light.isEnabled() || light.intensity() <= 0f) {
                continue;
            }
            float range = light.range();
            if (range > 0f) {
                float distSq = light.position().distanceSquared(referencePosition);
                if (distSq > range * range) {
                    continue;
                }
            }
            scratch.add(light);
        }
        if (scratch.isEmpty()) {
            return;
        }
        lastReferencePosition = referencePosition;
        scratch.sort(scoreComparator);
        int limit = Math.min(maxLights, scratch.size());
        for (int i = 0; i < limit; i++) {
            out.add(scratch.get(i));
        }
    }

    public DynamicLight spawnLight(Vector3fc position, Vector3fc color, float intensity, float radius, float range, float ttlSeconds) {
        DynamicLight light = new DynamicLight()
                .setPosition(position)
                .setColor(color)
                .setIntensity(intensity)
                .setRadius(radius)
                .setRange(range)
                .setTimeToLive(ttlSeconds);
        addLight(light);
        return light;
    }

    private static float brightnessScore(DynamicLight light, Vector3fc referencePosition) {
        if (referencePosition == null) {
            return light.intensity();
        }
        float distSq = light.position().distanceSquared(referencePosition);
        return light.intensity() / (distSq + 1f);
    }
}
