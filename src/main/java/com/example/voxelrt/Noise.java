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

    private static double grad(int h, double x, double y, double z) {
        int g = h & 15;
        double u = g < 8 ? x : y;
        double v = g < 4 ? y : (g == 12 || g == 14 ? x : z);
        return ((g & 1) == 0 ? u : -u) + ((g & 2) == 0 ? v : -v);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    public double noise2D(double x, double y) {
        int X = (int) java.lang.Math.floor(x) & 255;
        int Y = (int) java.lang.Math.floor(y) & 255;
        x -= java.lang.Math.floor(x);
        y -= java.lang.Math.floor(y);
        double u = fade(x);
        double v = fade(y);

        int a = perm[X] + Y;
        int b = perm[X + 1] + Y;

        int aa = perm[a];
        int ab = perm[a + 1];
        int ba = perm[b];
        int bb = perm[b + 1];

        return lerp(v,
                lerp(u, grad(aa, x, y), grad(ba, x - 1, y)),
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

    public double noise3D(double x, double y, double z) {
        int X = (int) java.lang.Math.floor(x) & 255;
        int Y = (int) java.lang.Math.floor(y) & 255;
        int Z = (int) java.lang.Math.floor(z) & 255;
        x -= java.lang.Math.floor(x);
        y -= java.lang.Math.floor(y);
        z -= java.lang.Math.floor(z);
        double u = fade(x);
        double v = fade(y);
        double w = fade(z);

        int A = perm[X] + Y;
        int AA = perm[A] + Z;
        int AB = perm[A + 1] + Z;
        int B = perm[X + 1] + Y;
        int BA = perm[B] + Z;
        int BB = perm[B + 1] + Z;

        return lerp(w,
                lerp(v,
                        lerp(u, grad(perm[AA], x, y, z), grad(perm[BA], x - 1, y, z)),
                        lerp(u, grad(perm[AB], x, y - 1, z), grad(perm[BB], x - 1, y - 1, z))),
                lerp(v,
                        lerp(u, grad(perm[AA + 1], x, y, z - 1), grad(perm[BA + 1], x - 1, y, z - 1)),
                        lerp(u, grad(perm[AB + 1], x, y - 1, z - 1), grad(perm[BB + 1], x - 1, y - 1, z - 1))));
    }

    public double fractal3D(double x, double y, double z, int oct) {
        double a = 1, f = 1, s = 0, n = 0;
        for (int i = 0; i < oct; i++) {
            s += noise3D(x * f, y * f, z * f) * a;
            n += a;
            a *= 0.5;
            f *= 2;
        }
        return s / n;
    }
}