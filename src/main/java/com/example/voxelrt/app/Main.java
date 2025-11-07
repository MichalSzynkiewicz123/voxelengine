package com.example.voxelrt.app;

/**
 * Entry point for the standalone voxel renderer application.
 * <p>
 * The class simply bootstraps the {@link Engine} which owns the game loop and render state.
 */
public class Main {
    private Main() {
    }

    public static void main(String[] args) {
        new Engine().run();
    }
}