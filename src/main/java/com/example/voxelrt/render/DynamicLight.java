package com.example.voxelrt.render;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Represents a single dynamic light source in the world. Lights can be positioned anywhere,
 * animated every frame and optionally removed after a finite lifetime (useful for explosions).
 */
public final class DynamicLight {
    private final Vector3f position = new Vector3f();
    private final Vector3f color = new Vector3f(1f, 1f, 1f);

    private float intensity = 0f;
    private float radius = 0f;
    private float range = 0f;
    private boolean enabled = true;
    private float timeToLive = Float.POSITIVE_INFINITY;

    public Vector3f position() {
        return position;
    }

    public DynamicLight setPosition(Vector3fc pos) {
        this.position.set(pos);
        return this;
    }

    public DynamicLight setPosition(float x, float y, float z) {
        this.position.set(x, y, z);
        return this;
    }

    public Vector3f color() {
        return color;
    }

    public DynamicLight setColor(Vector3fc color) {
        this.color.set(color);
        return this;
    }

    public DynamicLight setColor(float r, float g, float b) {
        this.color.set(r, g, b);
        return this;
    }

    public float intensity() {
        return intensity;
    }

    public DynamicLight setIntensity(float intensity) {
        this.intensity = Math.max(0f, intensity);
        return this;
    }

    public float radius() {
        return radius;
    }

    public DynamicLight setRadius(float radius) {
        this.radius = Math.max(0f, radius);
        return this;
    }

    public float range() {
        return range;
    }

    /**
     * Sets the maximum effective range of the light. A value of {@code 0} disables range limiting.
     */
    public DynamicLight setRange(float range) {
        this.range = Math.max(0f, range);
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public DynamicLight setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public float timeToLive() {
        return timeToLive;
    }

    /**
     * Assigns a finite lifetime in seconds. Once the timer reaches zero the light is culled from
     * the manager. Use {@link #resetLifetime()} for persistent lights.
     */
    public DynamicLight setTimeToLive(float seconds) {
        if (Float.isInfinite(seconds)) {
            this.timeToLive = Float.POSITIVE_INFINITY;
        } else {
            this.timeToLive = Math.max(0f, seconds);
        }
        return this;
    }

    public DynamicLight resetLifetime() {
        this.timeToLive = Float.POSITIVE_INFINITY;
        return this;
    }

    boolean hasFiniteLifetime() {
        return Float.isFinite(timeToLive);
    }

    void advanceTime(float dt) {
        if (!hasFiniteLifetime()) {
            return;
        }
        timeToLive -= dt;
    }

    boolean isExpired() {
        return hasFiniteLifetime() && timeToLive <= 0f;
    }
}
