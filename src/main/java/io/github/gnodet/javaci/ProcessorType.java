package io.github.gnodet.javaci;

/**
 * Classification of annotation processors for incremental compilation.
 *
 * <p>Follows the same taxonomy as Gradle's incremental annotation processing
 * for ecosystem compatibility.
 *
 * @see ProcessorClassification
 */
public enum ProcessorType {

    /**
     * Each annotated input type produces independent generated outputs.
     * Safe for incremental compilation — only the changed annotated type
     * and its generated outputs need reprocessing.
     */
    ISOLATING,

    /**
     * Generated outputs may depend on the full set of annotated types
     * (e.g., Dagger component graphs, ServiceLoader registrations).
     * When any source carrying the processor's trigger annotations changes,
     * all sources with those annotations must be included in the compilation.
     */
    AGGREGATING,

    /**
     * Processor has not declared its incremental behavior. When any annotated
     * source changes, all sources are recompiled as a conservative fallback.
     */
    UNKNOWN
}
