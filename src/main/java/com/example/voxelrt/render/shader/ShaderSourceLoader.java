package com.example.voxelrt.render.shader;

import com.example.voxelrt.util.ResourceUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Loads GLSL shader sources while supporting lightweight {@code #include "path"} directives.
 */
public final class ShaderSourceLoader {
    private final Map<String, String> cache = new HashMap<>();

    public String load(String path) {
        return cache.computeIfAbsent(path, p -> loadRecursive(p, new ArrayDeque<>(), new HashSet<>()));
    }

    private String loadRecursive(String path, Deque<String> includeStack, Set<String> seen) {
        if (!includeStack.isEmpty() && !seen.add(path)) {
            throw new IllegalStateException("Circular shader include detected: " + includeStack + " -> " + path);
        }
        includeStack.push(path);
        String source = ResourceUtils.loadTextResource(path);
        StringBuilder result = new StringBuilder(source.length() + 64);
        String[] lines = source.split("\\r?\\n", -1);
        String baseDir = baseDirectory(path);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#include")) {
                String includePath = extractIncludePath(trimmed);
                if (includePath == null) {
                    throw new IllegalArgumentException("Invalid include directive in " + path + ": " + line);
                }
                String resolved = resolvePath(baseDir, includePath);
                String included = loadRecursive(resolved, includeStack, seen);
                result.append("// begin include ").append(resolved).append('\n');
                result.append(included);
                if (!included.endsWith("\n")) {
                    result.append('\n');
                }
                result.append("// end include ").append(resolved).append('\n');
            } else {
                result.append(line).append('\n');
            }
        }
        includeStack.pop();
        seen.remove(path);
        return result.toString();
    }

    private static String extractIncludePath(String line) {
        int start = line.indexOf('"');
        int end = line.lastIndexOf('"');
        if (start >= 0 && end > start) {
            return line.substring(start + 1, end).trim();
        }
        return null;
    }

    private static String resolvePath(String baseDir, String include) {
        if (include.startsWith("/")) {
            return include.substring(1);
        }
        if (baseDir.isEmpty()) {
            return include;
        }
        return baseDir + include;
    }

    private static String baseDirectory(String path) {
        int idx = path.lastIndexOf('/') + 1;
        if (idx <= 0) {
            return "";
        }
        return path.substring(0, idx);
    }
}
