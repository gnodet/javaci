package io.github.gnodet.javaci;

import com.sun.source.util.JavacTask;

import javax.annotation.processing.Processor;
import javax.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class IncrementalCompilationTask implements JavaCompiler.CompilationTask {

    private final JavaCompiler delegate;
    private final Writer out;
    private final JavaFileManager fileManager;
    private final DiagnosticListener<? super JavaFileObject> diagnosticListener;
    private final List<String> options;
    private final List<String> classes;
    private final Map<String, JavaFileObject> unitsByName;
    private Iterable<? extends Processor> processors;
    private Locale locale;
    private final List<String> modules = new ArrayList<>();

    IncrementalCompilationTask(JavaCompiler delegate, Writer out,
            JavaFileManager fileManager,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            Iterable<String> options, Iterable<String> classes,
            Iterable<? extends JavaFileObject> compilationUnits) {
        this.delegate = delegate;
        this.out = out;
        this.fileManager = fileManager;
        this.diagnosticListener = diagnosticListener;
        this.options = toList(options);
        this.classes = toList(classes);
        this.unitsByName = new LinkedHashMap<>();
        if (compilationUnits != null) {
            for (JavaFileObject unit : compilationUnits) {
                unitsByName.put(unit.getName(), unit);
            }
        }
    }

    @Override
    public void setProcessors(Iterable<? extends Processor> processors) {
        this.processors = processors;
    }

    @Override
    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    @Override
    public void addModules(Iterable<String> moduleNames) {
        if (moduleNames != null) {
            moduleNames.forEach(this.modules::add);
        }
    }

    @Override
    public Boolean call() {
        if (unitsByName.isEmpty()) {
            return delegateCompile(List.of());
        }

        Path outputPath = getOutputPath();
        if (outputPath == null) {
            log("Incremental: no CLASS_OUTPUT set, falling back to full javac");
            return delegateCompile(unitsByName.values());
        }

        try {
            Path stateFile = outputPath.resolve(".incremental-state");

            var currentHashes = new LinkedHashMap<String, String>();
            for (var entry : unitsByName.entrySet()) {
                currentHashes.put(entry.getKey(), hashContent(entry.getValue()));
            }

            IncrementalState previousState = IncrementalState.load(stateFile);

            if (previousState == null) {
                return fullCompile(currentHashes, outputPath, stateFile);
            } else {
                return incrementalCompile(previousState, currentHashes, outputPath, stateFile);
            }
        } catch (Exception e) {
            log("Incremental: error (%s), falling back to full javac", e.getMessage());
            return delegateCompile(unitsByName.values());
        }
    }

    // --- Full build ---

    private Boolean fullCompile(Map<String, String> hashes, Path outputPath,
            Path stateFile) throws Exception {
        log("Incremental: full build (%d source files)", unitsByName.size());

        var results = compileAndAnalyze(unitsByName.values(), outputPath, false);
        if (results == null) return false;

        var state = IncrementalState.from(hashes, results);
        state.save(stateFile);

        log("Incremental: state saved (%d types analyzed)", results.size());
        return true;
    }

    // --- Incremental build ---

    private Boolean incrementalCompile(IncrementalState previousState,
            Map<String, String> currentHashes, Path outputPath,
            Path stateFile) throws Exception {

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
            log("Incremental: no changes detected");
            return true;
        }

        log("Incremental: %d changed, %d new, %d deleted",
            changedFiles.size(), newFiles.size(), deletedFiles.size());

        // Initial recompilation set
        var toRecompile = new TreeSet<String>();
        toRecompile.addAll(changedFiles);
        toRecompile.addAll(newFiles);

        for (String deleted : deletedFiles) {
            for (String type : previousState.getTypesFromSource(deleted)) {
                for (String consumer : previousState.getAllConsumers(type)) {
                    String sf = previousState.sourceFileFor(consumer);
                    if (sf != null) toRecompile.add(sf);
                }
            }
        }

        var state = previousState.copy();
        for (String deleted : deletedFiles) {
            for (String type : previousState.getTypesFromSource(deleted)) {
                deleteClassFile(type, outputPath);
            }
            state.removeSource(deleted);
        }

        var allCompiled = new TreeSet<String>();
        int round = 0;

        while (!toRecompile.isEmpty()) {
            round++;
            var roundUnits = new ArrayList<JavaFileObject>();
            for (String name : toRecompile) {
                JavaFileObject unit = unitsByName.get(name);
                if (unit != null) roundUnits.add(unit);
            }

            if (roundUnits.isEmpty()) break;

            log("Incremental: round %d, compiling %d file(s)", round, roundUnits.size());

            var results = compileAndAnalyze(roundUnits, outputPath, true);
            if (results == null) return false;

            allCompiled.addAll(toRecompile);

            // Detect ABI changes
            var abiChanged = new TreeSet<String>();
            for (var result : results.values()) {
                String prevAbi = previousState.getAbiFingerprint(result.qualifiedName());
                if (prevAbi == null || !prevAbi.equals(result.abiFingerprint())) {
                    abiChanged.add(result.qualifiedName());
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

            if (abiChanged.isEmpty()) {
                log("Incremental: fixpoint reached (no ABI changes)");
                break;
            }

            log("Incremental: ABI changed: %s", abiChanged);

            var abiCascade = new TreeSet<>(abiChanged);
            for (String type : abiChanged) {
                expandSignatureCascade(type, state, abiCascade);
            }

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
        }

        state.save(stateFile);

        log("Incremental: %d file(s) compiled, %d unchanged",
            allCompiled.size(), currentHashes.size() - allCompiled.size());
        return true;
    }

    // --- Compilation helpers ---

    private Map<String, SourceFileAnalysis> compileAndAnalyze(
            Collection<JavaFileObject> units, Path outputPath,
            boolean incremental) throws IOException {

        if (incremental && fileManager instanceof StandardJavaFileManager sfm) {
            var classPath = new ArrayList<File>();
            var existing = sfm.getLocation(StandardLocation.CLASS_PATH);
            if (existing != null) {
                for (File f : existing) classPath.add(f);
            }
            File outDir = outputPath.toFile();
            if (!classPath.contains(outDir)) {
                classPath.add(outDir);
            }
            sfm.setLocation(StandardLocation.CLASS_PATH, classPath);
            sfm.setLocation(StandardLocation.SOURCE_PATH, List.of());
        }

        var task = (JavacTask) delegate.getTask(
            out, fileManager, diagnosticListener,
            options, classes.isEmpty() ? null : classes, units);

        if (processors != null) task.setProcessors(processors);
        if (locale != null) task.setLocale(locale);

        var analyzer = new CompilationAnalyzer(task);
        task.addTaskListener(analyzer);

        applySettings(task);
        boolean success = task.call();
        return success ? analyzer.getResults() : null;
    }

    private Boolean delegateCompile(Collection<JavaFileObject> units) {
        var task = delegate.getTask(
            out, fileManager, diagnosticListener, options, null, units);
        applySettings(task);
        return task.call();
    }

    private void applySettings(JavaCompiler.CompilationTask task) {
        if (processors != null) task.setProcessors(processors);
        if (locale != null) task.setLocale(locale);
        if (!modules.isEmpty()) task.addModules(modules);
    }

    // --- Utility ---

    private void expandSignatureCascade(String type, IncrementalState state,
            Set<String> result) {
        for (String consumer : state.getSignatureConsumers(type)) {
            if (result.add(consumer)) {
                expandSignatureCascade(consumer, state, result);
            }
        }
    }

    private void deleteClassFile(String qualifiedName, Path outputPath) {
        Path classFile = outputPath.resolve(qualifiedName.replace('.', '/') + ".class");
        try { Files.deleteIfExists(classFile); } catch (IOException ignored) {}
    }

    private Path getOutputPath() {
        if (fileManager instanceof StandardJavaFileManager sfm) {
            try {
                var location = sfm.getLocation(StandardLocation.CLASS_OUTPUT);
                if (location != null) {
                    for (File f : location) return f.toPath();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String hashContent(JavaFileObject unit) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        try (var is = unit.openInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) md.update(buf, 0, n);
        }
        byte[] hash = md.digest();
        var hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private void log(String format, Object... args) {
        String msg = String.format(format, args);
        if (out != null) {
            try {
                out.write(msg + "\n");
                out.flush();
            } catch (IOException ignored) {}
        } else {
            System.err.println(msg);
        }
    }

    private static <T> List<T> toList(Iterable<T> iterable) {
        if (iterable == null) return List.of();
        var list = new ArrayList<T>();
        iterable.forEach(list::add);
        return Collections.unmodifiableList(list);
    }
}
