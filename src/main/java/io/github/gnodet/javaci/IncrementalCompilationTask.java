package io.github.gnodet.javaci;

import com.sun.source.util.JavacTask;

import javax.annotation.processing.Processor;
import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * A {@link javax.tools.JavaCompiler.CompilationTask CompilationTask} that adds
 * incremental compilation semantics on top of a delegate javac invocation.
 *
 * <p>This is a thin wrapper around {@link AbiIncrementalBuild} for use through
 * the {@link javax.tools.JavaCompiler} SPI. For direct integration into
 * maven-compiler-plugin, use {@link AbiIncrementalBuild} directly — it
 * provides a cleaner, path-based API without the SPI ceremony.
 *
 * @see IncrementalJavaCompiler
 * @see AbiIncrementalBuild
 */
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
    private List<Path> classpathEntries;
    private Set<Path> reactorModulePaths;

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

    public void setClasspathEntries(List<Path> entries) {
        this.classpathEntries = entries;
    }

    public void setReactorModulePaths(Set<Path> paths) {
        this.reactorModulePaths = paths;
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
            var abi = new AbiIncrementalBuild(outputPath);
            abi.setClasspathEntries(classpathEntries != null
                    ? classpathEntries : resolveClasspath(outputPath));
            abi.setReactorModulePaths(reactorModulePaths);

            // Map JavaFileObjects to Paths for the engine
            var allPaths = new ArrayList<Path>();
            for (String name : unitsByName.keySet()) {
                allPaths.add(Path.of(name));
            }

            Set<Path> toCompile = abi.initialize(allPaths);

            if (toCompile.isEmpty()) {
                log("Incremental: no changes detected");
                abi.finish();
                return true;
            }

            if (abi.isFullBuild()) {
                log("Incremental: full build (%d source files)", toCompile.size());
            } else {
                log("Incremental: incremental build, %d file(s) to recompile", toCompile.size());
            }

            int round = 0;
            while (!toCompile.isEmpty()) {
                round++;
                var roundUnits = new ArrayList<JavaFileObject>();
                for (Path p : toCompile) {
                    JavaFileObject unit = unitsByName.get(p.toString());
                    if (unit != null) roundUnits.add(unit);
                }
                if (roundUnits.isEmpty()) break;

                log("Incremental: round %d, compiling %d file(s)", round, roundUnits.size());

                if (!compileRound(roundUnits, abi, outputPath)) return false;

                toCompile = abi.processRound();
            }

            abi.finish();
            log("Incremental: %d file(s) compiled, %d unchanged",
                abi.compiledCount(), abi.unchangedCount());
            return true;

        } catch (Exception e) {
            log("Incremental: error (%s), falling back to full javac", e.getMessage());
            return delegateCompile(unitsByName.values());
        }
    }

    private boolean compileRound(List<JavaFileObject> units, AbiIncrementalBuild abi,
            Path outputPath) throws IOException {
        // Ensure output dir is on classpath for incremental rounds
        if (fileManager instanceof StandardJavaFileManager sfm) {
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

        applySettings(task);
        abi.attachTo(task);
        return task.call();
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

    private List<Path> resolveClasspath(Path outputPath) {
        if (fileManager instanceof StandardJavaFileManager sfm) {
            try {
                var entries = new ArrayList<Path>();
                var location = sfm.getLocation(StandardLocation.CLASS_PATH);
                if (location != null) {
                    for (File f : location) entries.add(f.toPath());
                }
                if (!entries.contains(outputPath)) {
                    entries.add(outputPath);
                }
                return entries;
            } catch (Exception ignored) {}
        }
        return List.of(outputPath);
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

    private void log(String format, Object... args) {
        String msg = String.format(format, args);
        if (out != null) {
            try { out.write(msg + "\n"); out.flush(); } catch (IOException ignored) {}
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
