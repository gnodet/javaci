package io.github.gnodet.javaci;

import java.util.Set;

public record SourceFileAnalysis(
    String qualifiedName,
    String sourceFile,
    Set<String> signatureDeps,
    Set<String> implementationDeps,
    String abiFingerprint,
    String abiCanonical
) {}
