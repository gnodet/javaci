package io.github.gnodet.javaci;

import com.sun.source.util.JavacTask;

import javax.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

public class IncrementalCompiler {

    private final Path sourceDir;
    private final Path outputDir;
    private final Path stateFile;

    public IncrementalCompiler(Path sourceDir, Path outputDir) {
        this.sourceDir = sourceDir;
        this.outputDir = outputDir;
        this.stateFile = outputDir.resolve(".incremental-state");
    }

    public static void main(String[] args) throws Exception {
        boolean analyze = false;
        boolean clean = false;
        boolean useSpi = false;
        String sourceDirArg = "test-sources";

        for (String arg : args) {
            switch (arg) {
                case "--analyze" -> analyze = true;
                case "--clean" -> clean = true;
                case "--spi" -> useSpi = true;
                default -> sourceDirArg = arg;
            }
        }

        Path sourceDir = Path.of(sourceDirArg);
        Path outputDir = Path.of("target/test-output");

        if (analyze) {
            runAnalysis(sourceDir, outputDir);
        } else if (useSpi) {
            if (clean) Files.deleteIfExists(outputDir.resolve(".incremental-state"));
            runViaSpi(sourceDir, outputDir);
        } else {
            var compiler = new IncrementalCompiler(sourceDir, outputDir);
            if (clean) {
                Files.deleteIfExists(outputDir.resolve(".incremental-state"));
            }
            compiler.compile();
        }
    }

    private static void runViaSpi(Path sourceDir, Path outputDir) throws Exception {
        Files.createDirectories(outputDir);

        List<Path> sourceFiles;
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            sourceFiles = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }

        // Use IncrementalJavaCompiler exactly like you'd use ToolProvider.getSystemJavaCompiler()
        JavaCompiler compiler = new IncrementalJavaCompiler();
        try (var fm = compiler.getStandardFileManager(null, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            var units = fm.getJavaFileObjectsFromPaths(sourceFiles);

            var sw = new StringWriter();
            boolean success = compiler.getTask(sw, fm, null, null, null, units).call();

            String log = sw.toString();
            if (!log.isEmpty()) System.out.println(log);
            System.out.println("Compilation " + (success ? "succeeded" : "FAILED"));
        }
    }

    public void compile() throws Exception {
        Files.createDirectories(outputDir);

        Map<String, String> currentHashes = scanAndHash(sourceDir);
        IncrementalState previousState = IncrementalState.load(stateFile);

        if (previousState == null) {
            fullCompile(currentHashes);
        } else {
            incrementalCompile(previousState, currentHashes);
        }
    }

    private void fullCompile(Map<String, String> sourceHashes) throws Exception {
        List<Path> allFiles = sourceHashes.keySet().stream().map(Path::of).sorted().toList();
        System.out.println("=== Full Build ===");
        System.out.println("Compiling " + allFiles.size() + " source files\n");

        var results = compileFiles(allFiles, false);

        var state = IncrementalState.from(sourceHashes, results);
        state.save(stateFile);

        System.out.println("Types analyzed: " + results.size());
        System.out.println("State saved to " + stateFile + "\n");

        for (var analysis : results.values()) {
            printTypeAnalysis(analysis);
        }
    }

    private void incrementalCompile(IncrementalState previousState,
            Map<String, String> currentHashes) throws Exception {

        var changedFiles = new TreeSet<String>();
        var newFiles = new TreeSet<String>();
        var deletedFiles = new TreeSet<>(previousState.getSourceHashes().keySet());

        for (var entry : currentHashes.entrySet()) {
            String path = entry.getKey();
            String hash = entry.getValue();
            deletedFiles.remove(path);

            String previousHash = previousState.getSourceHash(path);
            if (previousHash == null) {
                newFiles.add(path);
            } else if (!hash.equals(previousHash)) {
                changedFiles.add(path);
            }
        }

        if (changedFiles.isEmpty() && newFiles.isEmpty() && deletedFiles.isEmpty()) {
            System.out.println("=== Incremental Build ===");
            System.out.println("No changes detected. Nothing to compile.\n");
            return;
        }

        System.out.println("=== Incremental Build ===");
        System.out.println("Previous state: " + previousState.getSourceHashes().size()
            + " sources, " + previousState.getTypes().size() + " types\n");

        System.out.println("Changes detected:");
        newFiles.forEach(f -> System.out.println("  + NEW      " + f));
        changedFiles.forEach(f -> System.out.println("  ~ MODIFIED " + f));
        deletedFiles.forEach(f -> System.out.println("  - DELETED  " + f));
        System.out.println();

        // Initial recompilation set: changed + new files
        var toRecompile = new TreeSet<String>();
        toRecompile.addAll(changedFiles);
        toRecompile.addAll(newFiles);

        // Add source files of consumers of deleted types
        for (String deleted : deletedFiles) {
            for (String type : previousState.getTypesFromSource(deleted)) {
                for (String consumer : previousState.getAllConsumers(type)) {
                    String sf = previousState.sourceFileFor(consumer);
                    if (sf != null) toRecompile.add(sf);
                }
            }
        }

        // Build working state (start from previous, remove deleted)
        var state = previousState.copy();
        for (String deleted : deletedFiles) {
            for (String type : previousState.getTypesFromSource(deleted)) {
                deleteClassFile(type);
            }
            state.removeSource(deleted);
        }

        var allCompiled = new TreeSet<String>();
        int round = 0;

        while (!toRecompile.isEmpty()) {
            round++;
            List<Path> filesToCompile = toRecompile.stream().map(Path::of).sorted().toList();
            System.out.println("Round " + round + ": compiling " + filesToCompile.size() + " file(s)");
            filesToCompile.forEach(f -> System.out.println("  " + f));

            var results = compileFiles(filesToCompile, true);
            allCompiled.addAll(toRecompile);

            // Detect ABI changes
            var abiChanged = new TreeSet<String>();
            var abiStable = new TreeSet<String>();
            for (var result : results.values()) {
                String prevAbi = previousState.getAbiFingerprint(result.qualifiedName());
                if (prevAbi == null || !prevAbi.equals(result.abiFingerprint())) {
                    abiChanged.add(result.qualifiedName());
                } else {
                    abiStable.add(result.qualifiedName());
                }
            }

            // Update state
            for (String path : toRecompile) {
                String hash = currentHashes.get(path);
                if (hash != null) state.setSourceHash(path, hash);
            }
            for (var result : results.values()) {
                state.setType(result.qualifiedName(), new IncrementalState.TypeInfo(
                    result.sourceFile(), result.abiFingerprint(),
                    result.signatureDeps(), result.implementationDeps()));
            }

            if (!abiStable.isEmpty()) {
                System.out.println("  ABI stable: " + abiStable);
            }

            if (abiChanged.isEmpty()) {
                if (!abiStable.isEmpty()) {
                    System.out.println("  No ABI changes -> fixpoint reached.");
                }
                System.out.println();
                break;
            }

            System.out.println("  ABI CHANGED: " + abiChanged);

            // Compute transitive signature cascade from all ABI-changed types
            var abiCascade = new TreeSet<>(abiChanged);
            for (String type : abiChanged) {
                expandSignatureCascade(type, state, abiCascade);
            }

            // All consumers (sig + impl) of any cascaded type need recompilation
            var additionalFiles = new TreeSet<String>();
            for (String cascadedType : abiCascade) {
                for (String consumer : state.getAllConsumers(cascadedType)) {
                    String sf = state.sourceFileFor(consumer);
                    if (sf != null && !allCompiled.contains(sf)) {
                        additionalFiles.add(sf);
                    }
                }
            }

            toRecompile = additionalFiles;
            if (!toRecompile.isEmpty()) {
                System.out.println("  Cascading to consumers...");
            } else {
                System.out.println("  No further cascade needed.");
            }
            System.out.println();
        }

        state.save(stateFile);

        int total = currentHashes.size();
        System.out.println("=== Summary ===");
        System.out.println("  " + allCompiled.size() + " file(s) compiled, "
            + (total - allCompiled.size()) + " unchanged");
        System.out.println("  State saved.\n");
    }

    private Map<String, SourceFileAnalysis> compileFiles(List<Path> files,
            boolean incremental) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));

            if (incremental) {
                fileManager.setLocation(StandardLocation.CLASS_PATH, List.of(outputDir.toFile()));
                fileManager.setLocation(StandardLocation.SOURCE_PATH, List.of());
            }

            var compilationUnits = fileManager.getJavaFileObjectsFromPaths(files);

            var task = (JavacTask) compiler.getTask(
                null, fileManager, diagnostics, null, null, compilationUnits);

            var analyzer = new CompilationAnalyzer(task);
            task.addTaskListener(analyzer);

            boolean success = task.call();

            if (!success) {
                System.err.println("Compilation failed:");
                for (var d : diagnostics.getDiagnostics()) {
                    System.err.println("  " + d);
                }
                throw new RuntimeException("Compilation failed");
            }

            return analyzer.getResults();
        }
    }

    private void expandSignatureCascade(String type, IncrementalState state, Set<String> result) {
        for (String consumer : state.getSignatureConsumers(type)) {
            if (result.add(consumer)) {
                expandSignatureCascade(consumer, state, result);
            }
        }
    }

    private void deleteClassFile(String qualifiedName) {
        String classFileName = qualifiedName.replace('.', '/') + ".class";
        Path classFile = outputDir.resolve(classFileName);
        try {
            Files.deleteIfExists(classFile);
        } catch (IOException ignored) {}
    }

    private static Map<String, String> scanAndHash(Path sourceDir) throws Exception {
        var hashes = new LinkedHashMap<String, String>();
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            var files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            for (Path file : files) {
                hashes.put(file.toString(), hashFile(file));
            }
        }
        return hashes;
    }

    private static String hashFile(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        var md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(bytes);
        var hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private static void printTypeAnalysis(SourceFileAnalysis a) {
        System.out.println("  " + a.qualifiedName());
        System.out.println("    ABI: " + a.abiFingerprint());
        if (!a.signatureDeps().isEmpty())
            System.out.println("    Sig deps: " + a.signatureDeps());
        if (!a.implementationDeps().isEmpty())
            System.out.println("    Impl deps: " + a.implementationDeps());
    }

    static boolean isJdkType(String name) {
        return name.startsWith("java.") || name.startsWith("javax.")
            || name.startsWith("jdk.") || name.startsWith("sun.");
    }

    // === Analysis mode (original POC) ===

    private static void runAnalysis(Path sourceDir, Path outputDir) throws Exception {
        Files.createDirectories(outputDir);
        List<Path> sourceFiles;
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            sourceFiles = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }

        System.out.println("=== Source Files ===");
        sourceFiles.forEach(f -> System.out.println("  " + f));
        System.out.println();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            var compilationUnits = fileManager.getJavaFileObjectsFromPaths(sourceFiles);

            var task = (JavacTask) compiler.getTask(
                null, fileManager, diagnostics, null, null, compilationUnits);

            var analyzer = new CompilationAnalyzer(task);
            task.addTaskListener(analyzer);

            boolean success = task.call();
            System.out.println("=== Compilation " + (success ? "SUCCEEDED" : "FAILED") + " ===\n");

            if (!success) {
                for (var d : diagnostics.getDiagnostics()) System.err.println(d);
                System.exit(1);
            }

            analyzer.printResults();

            System.out.println("=== Bytecode Analysis (java.lang.classfile) ===\n");
            try (Stream<Path> classWalk = Files.walk(outputDir)) {
                var classFiles = classWalk
                    .filter(p -> p.toString().endsWith(".class")).sorted().toList();
                for (Path cf : classFiles) {
                    var ba = BytecodeAnalyzer.analyze(cf);
                    System.out.println("--- " + ba.className() + " (bytecode) ---");
                    System.out.println("ABI Fingerprint: " + ba.abiFingerprint());
                    System.out.println("ABI Canonical Form:");
                    ba.abiCanonical().lines().forEach(l -> System.out.println("  " + l));
                    var nonJdk = ba.referencedTypes().stream()
                        .filter(t -> !isJdkType(t)).toList();
                    System.out.println("Referenced Types (non-JDK):");
                    if (nonJdk.isEmpty()) {
                        System.out.println("  (none)");
                    } else {
                        nonJdk.forEach(t -> System.out.println("  - " + t));
                    }
                    System.out.println();
                }
            }

            analyzer.printIncrementalSimulation();
        }
    }
}
