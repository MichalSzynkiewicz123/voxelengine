package com.example.voxelrt;

import java.util.Random;

public class Noise {
    private final int[] perm = new int[512];

    public Noise(long seed) {
        Random rnd = new Random(seed);
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double grad(int h, double x, double y) {
        int g = h & 3;
        double u = g < 2 ? x : y, v = g < 2 ? y : x;
        return ((g & 1) == 0 ? u : -u) + ((g & 2) == 0 ? v : -v);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    public double noise2D(double x, double y) {
        int X = (int) java.lang.Math.floor(x) & 255, Y = (int) java.lang.Math.floor(y) & 255;
        x -= java.lang.Math.floor(x);
        y -= java.lang.Math.floor(y);
        double u = fade(x), v = fade(y);
        int aa = perm[X + perm[Y]], ab = perm[X + perm[Y + 1]], ba = perm[X + 1 + perm[Y]], bb = perm[X + 1 + perm[Y + 1]];
        return lerp(v, lerp(u, grad(aa, x, y), grad(ba, x - 1, y)),
                lerp(u, grad(ab, x, y - 1), grad(bb, x - 1, y - 1)));
    }

    public double fractal2D(double x, double y, int oct) {
        double a = 1, f = 1, s = 0, n = 0;
        for (int i = 0; i < oct; i++) {
            s += noise2D(x * f, y * f) * a;
            n += a;
            a *= 0.5;
            f *= 2;
        }
        return s / n;
    }
}