package io.github.gnodet.javaci;

import com.sun.source.util.*;

import javax.lang.model.element.TypeElement;
import java.util.*;

public class CompilationAnalyzer implements TaskListener {

    private final Trees trees;
    private final Map<String, SourceFileAnalysis> analyses = new LinkedHashMap<>();

    public CompilationAnalyzer(JavacTask task) {
        this.trees = Trees.instance(task);
    }

    @Override
    public void finished(TaskEvent e) {
        if (e.getKind() != TaskEvent.Kind.ANALYZE) return;

        TypeElement typeElement = e.getTypeElement();
        var cu = e.getCompilationUnit();
        if (typeElement == null || cu == null) return;

        String qualifiedName = typeElement.getQualifiedName().toString();
        String sourceFile = cu.getSourceFile().getName();

        var scanner = new DependencyScanner(trees);
        scanner.scan(cu, null);

        String abiFingerprint = AbiExtractor.computeFingerprint(typeElement);
        String abiCanonical = AbiExtractor.canonicalForm(typeElement);

        var sigDeps = new TreeSet<>(scanner.getSignatureDeps());
        var implDeps = new TreeSet<>(scanner.getImplementationDeps());

        sigDeps.remove(qualifiedName);
        implDeps.remove(qualifiedName);
        implDeps.removeAll(sigDeps);
        sigDeps.removeIf(CompilationAnalyzer::isJdkType);
        implDeps.removeIf(CompilationAnalyzer::isJdkType);

        analyses.put(qualifiedName, new SourceFileAnalysis(
            qualifiedName, sourceFile, sigDeps, implDeps, abiFingerprint, abiCanonical));
    }

    public Map<String, SourceFileAnalysis> getResults() {
        return Collections.unmodifiableMap(analyses);
    }

    public void printResults() {
        System.out.println("=== Source-Level Analysis ===\n");
        for (var analysis : analyses.values()) {
            System.out.println("--- " + analysis.qualifiedName() + " ---");
            System.out.println("Source: " + analysis.sourceFile());
            System.out.println("ABI Fingerprint: " + analysis.abiFingerprint());
            System.out.println("ABI Canonical Form:");
            analysis.abiCanonical().lines().forEach(l -> System.out.println("  " + l));

            System.out.println("Signature Dependencies (affect ABI consumers):");
            if (analysis.signatureDeps().isEmpty()) {
                System.out.println("  (none)");
            } else {
                analysis.signatureDeps().forEach(d -> System.out.println("  - " + d));
            }

            System.out.println("Implementation Dependencies (body-only):");
            if (analysis.implementationDeps().isEmpty()) {
                System.out.println("  (none)");
            } else {
                analysis.implementationDeps().forEach(d -> System.out.println("  - " + d));
            }
            System.out.println();
        }
    }

    public void printIncrementalSimulation() {
        System.out.println("=== Incremental Recompilation Simulation ===\n");

        // Reverse maps: "who depends on X?"
        Map<String, Set<String>> sigConsumers = new LinkedHashMap<>();
        Map<String, Set<String>> implConsumers = new LinkedHashMap<>();
        for (var analysis : analyses.values()) {
            String consumer = analysis.qualifiedName();
            for (String dep : analysis.signatureDeps()) {
                sigConsumers.computeIfAbsent(dep, _ -> new TreeSet<>()).add(consumer);
            }
            for (String dep : analysis.implementationDeps()) {
                implConsumers.computeIfAbsent(dep, _ -> new TreeSet<>()).add(consumer);
            }
        }

        for (var analysis : analyses.values()) {
            String type = analysis.qualifiedName();

            System.out.println("If " + type + " changes:");

            // Case 1: body-only change — ABI unchanged, only this file
            System.out.println("  Body-only change (ABI stable):");
            System.out.println("    -> Recompile: [" + type + "]");

            // Case 2: ABI change — cascade through signature consumers,
            // then also pull in implementation consumers of every cascaded type
            var abiCascade = new TreeSet<String>();
            abiCascade.add(type);
            expandSignatureCascade(type, sigConsumers, abiCascade);

            var recompileSet = new TreeSet<>(abiCascade);
            for (String cascaded : abiCascade) {
                recompileSet.addAll(implConsumers.getOrDefault(cascaded, Set.of()));
            }

            System.out.println("  ABI change (signature modified):");
            System.out.println("    -> Recompile: " + recompileSet);
            System.out.println();
        }
    }

    private void expandSignatureCascade(String type,
            Map<String, Set<String>> sigConsumers, Set<String> result) {
        for (String consumer : sigConsumers.getOrDefault(type, Set.of())) {
            if (result.add(consumer)) {
                expandSignatureCascade(consumer, sigConsumers, result);
            }
        }
    }

    static boolean isJdkType(String name) {
        return name.startsWith("java.") || name.startsWith("javax.")
            || name.startsWith("jdk.") || name.startsWith("sun.");
    }
}
