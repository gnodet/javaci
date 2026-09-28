package io.github.gnodet.javaci;

import com.sun.source.util.JavacTask;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Shared helpers for tests that need to compile Java source strings.
 */
class CompilerTestSupport {

    record CompilationResult(
        Map<String, SourceFileAnalysis> analyses,
        boolean success
    ) {}

    static CompilationResult compileAndAnalyze(Map<String, String> sources, Path outputDir)
            throws Exception {
        Files.createDirectories(outputDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));

            var units = sources.entrySet().stream()
                .map(e -> new InMemorySource(e.getKey(), e.getValue()))
                .toList();

            var task = (JavacTask) compiler.getTask(null, fm, diagnostics, null, null, units);
            var analyzer = new CompilationAnalyzer(task);
            task.addTaskListener(analyzer);

            boolean success = task.call();
            return new CompilationResult(analyzer.getResults(), success);
        }
    }

    static SourceFileAnalysis compileSingleType(String qualifiedName, String source, Path outputDir)
            throws Exception {
        var result = compileAndAnalyze(Map.of(qualifiedName, source), outputDir);
        if (!result.success()) {
            throw new RuntimeException("Compilation failed for " + qualifiedName);
        }
        return result.analyses().get(qualifiedName);
    }

    static class InMemorySource extends SimpleJavaFileObject {
        private final String code;

        InMemorySource(String qualifiedName, String code) {
            super(URI.create("string:///" + qualifiedName.replace('.', '/') + ".java"),
                Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    static void writeSource(Path dir, String pkg, String name, String source) throws IOException {
        Path pkgDir = dir.resolve(pkg.replace('.', '/'));
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve(name + ".java"), source);
    }
}
