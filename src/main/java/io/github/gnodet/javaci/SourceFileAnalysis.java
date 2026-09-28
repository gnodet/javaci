package io.github.gnodet.javaci;

import java.util.Set;

/**
 * Analysis results for a single type produced during compilation.
 *
 * @param qualifiedName      fully qualified type name (e.g. {@code com.example.Foo})
 * @param sourceFile         path to the source file that defines this type
 * @param signatureDeps      types referenced in the public API surface (extends/implements
 *                           clauses, method signatures, non-private field types) — an ABI
 *                           change in any of these cascades to this type's consumers
 * @param implementationDeps types referenced only in method bodies or private members — an
 *                           ABI change triggers recompilation of this type only, without
 *                           cascading to its consumers
 * @param abiFingerprint     truncated SHA-256 hash of the canonical ABI form
 * @param abiCanonical       human-readable canonical representation of the public API
 * @param annotationTypes    fully qualified names of annotations present on this type,
 *                           used for annotation processor classification decisions
 */
public record SourceFileAnalysis(
    String qualifiedName,
    String sourceFile,
    Set<String> signatureDeps,
    Set<String> implementationDeps,
    String abiFingerprint,
    String abiCanonical,
    Set<String> annotationTypes
) {}
