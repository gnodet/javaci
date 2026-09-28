package io.github.gnodet.javaci;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Reads and writes ABI fingerprint manifests for cross-module incremental
 * compilation.
 *
 * <p>After compiling a module, the incremental engine writes a manifest
 * listing every public type and its ABI fingerprint. Downstream modules
 * read this manifest to detect whether their dependency's ABI changed
 * without re-analyzing bytecode.
 *
 * <p>The file format is one {@code qualifiedName=fingerprint} entry per line,
 * sorted lexicographically. Lines starting with {@code #} are comments.
 *
 * @see ExternalAbiResolver
 */
public class AbiManifest {

    public static final String FILENAME = ".abi-fingerprints";

    static final String VERSION_HEADER = "#javaci:v1";

    static final int CURRENT_VERSION = 1;

    public static void write(Path file, Map<String, String> fingerprints) throws IOException {
        Files.createDirectories(file.getParent());
        var sorted = new TreeMap<>(fingerprints);
        try (var writer = Files.newBufferedWriter(file)) {
            writer.write(VERSION_HEADER);
            writer.newLine();
            for (var entry : sorted.entrySet()) {
                writer.write(entry.getKey());
                writer.write('=');
                writer.write(entry.getValue());
                writer.newLine();
            }
        }
    }

    /**
     * Reads a manifest file. Returns an empty map if the file does not exist,
     * is unreadable, or has an unsupported version (newer than this reader
     * understands). An unsupported version causes a safe fallback to bytecode
     * analysis rather than misinterpreting changed fingerprint semantics.
     */
    public static Map<String, String> read(Path file) {
        if (!Files.exists(file)) return Map.of();
        try {
            var lines = Files.readAllLines(file);
            if (!checkVersion(lines)) return Map.of();
            var result = new LinkedHashMap<String, String>();
            for (String line : lines) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    result.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
            return result;
        } catch (IOException e) {
            return Map.of();
        }
    }

    private static boolean checkVersion(List<String> lines) {
        for (String line : lines) {
            line = line.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("#javaci:v")) {
                try {
                    int version = Integer.parseInt(line.substring("#javaci:v".length()));
                    return version <= CURRENT_VERSION;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }
}
