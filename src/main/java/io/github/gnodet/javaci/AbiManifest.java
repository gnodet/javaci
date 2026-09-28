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

    public static void write(Path file, Map<String, String> fingerprints) throws IOException {
        Files.createDirectories(file.getParent());
        var sorted = new TreeMap<>(fingerprints);
        try (var writer = Files.newBufferedWriter(file)) {
            for (var entry : sorted.entrySet()) {
                writer.write(entry.getKey());
                writer.write('=');
                writer.write(entry.getValue());
                writer.newLine();
            }
        }
    }

    public static Map<String, String> read(Path file) {
        if (!Files.exists(file)) return Map.of();
        try {
            var result = new LinkedHashMap<String, String>();
            for (String line : Files.readAllLines(file)) {
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
}
