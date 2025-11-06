package com.example.voxelrt.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Lightweight CPU profiler that measures the duration of named sections within a frame.
 * <p>
 * The profiler uses {@link System#nanoTime()} for timing and keeps a smoothed average for both
 * frame time and section durations so that the overlay presents stable numbers.
 */
public final class Profiler {
    private static final double SMOOTHING = 0.2;

    private final LinkedHashMap<String, SectionStats> sections = new LinkedHashMap<>();
    private long frameStartNanos = System.nanoTime();
    private double lastFrameMs;
    private double averageFrameMs;
    private double lastFps;
    private double averageFps;

    /** Marks the beginning of a new frame and resets the frame timer. */
    public void beginFrame() {
        frameStartNanos = System.nanoTime();
    }

    /** Completes the current frame, recording frame timing statistics. */
    public void endFrame() {
        long frameDuration = System.nanoTime() - frameStartNanos;
        lastFrameMs = frameDuration / 1_000_000.0;
        if (averageFrameMs == 0.0) {
            averageFrameMs = lastFrameMs;
        } else {
            averageFrameMs = smooth(averageFrameMs, lastFrameMs);
        }
        lastFps = lastFrameMs > 0.0 ? 1000.0 / lastFrameMs : 0.0;
        if (averageFps == 0.0) {
            averageFps = lastFps;
        } else {
            averageFps = smooth(averageFps, lastFps);
        }
    }

    /**
     * Starts timing a named section. The returned {@link Sample} must be closed to record the result.
     */
    public Sample sample(String name) {
        SectionStats stats = sections.computeIfAbsent(name, SectionStats::new);
        return new Sample(this, stats);
    }

    private void record(SectionStats stats, long nanos) {
        double ms = nanos / 1_000_000.0;
        stats.lastMs = ms;
        if (!stats.hasAverage) {
            stats.averageMs = ms;
            stats.hasAverage = true;
        } else {
            stats.averageMs = smooth(stats.averageMs, ms);
        }
    }

    private static double smooth(double current, double target) {
        return current + (target - current) * SMOOTHING;
    }

    /** Captures the most recent frame and section statistics. */
    public Snapshot snapshot() {
        List<SectionSnapshot> copy;
        if (sections.isEmpty()) {
            copy = List.of();
        } else {
            copy = new ArrayList<>(sections.size());
            for (SectionStats stats : sections.values()) {
                copy.add(new SectionSnapshot(stats.name, stats.lastMs, stats.averageMs));
            }
            copy = Collections.unmodifiableList(copy);
        }
        return new Snapshot(lastFrameMs, averageFrameMs, lastFps, averageFps, copy);
    }

    /** Immutable view of a frame's profiler measurements. */
    public record Snapshot(double frameTimeMs,
                           double averageFrameTimeMs,
                           double fps,
                           double averageFps,
                           List<SectionSnapshot> sections) {
        public static final Snapshot EMPTY = new Snapshot(0.0, 0.0, 0.0, 0.0, List.of());
    }

    /** Immutable view of an individual section measurement. */
    public record SectionSnapshot(String name, double lastMs, double averageMs) {
    }

    /** Handle that measures the duration of a profiled scope. */
    public static final class Sample implements AutoCloseable {
        private static final Sample NOOP = new Sample();

        private final Profiler profiler;
        private final SectionStats stats;
        private final long startNanos;

        private Sample() {
            this.profiler = null;
            this.stats = null;
            this.startNanos = 0L;
        }

        private Sample(Profiler profiler, SectionStats stats) {
            this.profiler = profiler;
            this.stats = stats;
            this.startNanos = System.nanoTime();
        }

        public static Sample noop() {
            return NOOP;
        }

        @Override
        public void close() {
            if (profiler != null && stats != null) {
                long duration = System.nanoTime() - startNanos;
                profiler.record(stats, duration);
            }
        }
    }

    private static final class SectionStats {
        final String name;
        double lastMs;
        double averageMs;
        boolean hasAverage;

        SectionStats(String name) {
            this.name = name;
        }
    }
}
