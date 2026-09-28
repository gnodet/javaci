package io.github.gnodet.javaci;

import javax.lang.model.SourceVersion;
import javax.tools.*;
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

/**
 * A {@link JavaCompiler} that wraps the system javac with incremental compilation.
 * <p>
 * On the first invocation, it performs a full build and persists a dependency/ABI
 * state file alongside the class output. On subsequent invocations, it detects
 * source changes, determines the minimal recompilation set using ABI fingerprints,
 * and only recompiles what is necessary.
 * <p>
 * Usage:
 * <pre>
 *   JavaCompiler compiler = new IncrementalJavaCompiler();
 *   StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
 *   fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir));
 *   var units = fm.getJavaFileObjectsFromPaths(sourceFiles);
 *   boolean success = compiler.getTask(null, fm, null, null, null, units).call();
 * </pre>
 */
public class IncrementalJavaCompiler implements JavaCompiler {

    private final JavaCompiler delegate;

    public IncrementalJavaCompiler() {
        this.delegate = ToolProvider.getSystemJavaCompiler();
        if (delegate == null) {
            throw new IllegalStateException("No system Java compiler available");
        }
    }

    @Override
    public CompilationTask getTask(Writer out, JavaFileManager fileManager,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            Iterable<String> options, Iterable<String> classes,
            Iterable<? extends JavaFileObject> compilationUnits) {
        return new IncrementalCompilationTask(
            delegate, out, fileManager, diagnosticListener,
            options, classes, compilationUnits);
    }

    @Override
    public StandardJavaFileManager getStandardFileManager(
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            Locale locale, Charset charset) {
        return delegate.getStandardFileManager(diagnosticListener, locale, charset);
    }

    @Override
    public int run(InputStream in, OutputStream out, OutputStream err, String... arguments) {
        return delegate.run(in, out, err, arguments);
    }

    @Override
    public Set<SourceVersion> getSourceVersions() {
        return delegate.getSourceVersions();
    }

    @Override
    public int isSupportedOption(String option) {
        return delegate.isSupportedOption(option);
    }
}
