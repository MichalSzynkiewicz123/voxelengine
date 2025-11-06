package com.example.voxelrt.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Convenience helpers for loading classpath resources. The methods fall back to reading directly from the source
 * tree which simplifies running from an IDE without packaging the resources.
 */
public final class ResourceUtils {
    private ResourceUtils() {
    }

    public static String loadTextResource(String path) {
        try {
            try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
                if (is != null) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return Files.readString(Path.of("src/main/resources/" + path));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load resource " + path, e);
        }
    }
}
