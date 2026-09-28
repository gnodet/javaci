package io.github.gnodet.javaci;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.*;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IncrementalCompilationTest {

    @TempDir Path sourceDir;
    @TempDir Path outputDir;

    @BeforeEach
    void setUp() throws Exception {
        CompilerTestSupport.writeSource(sourceDir, "api", "Model", """
            package api;
            public class Model {
                private String name;
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
            }
            """);
        CompilerTestSupport.writeSource(sourceDir, "impl", "Helper", """
            package impl;
            public class Helper {
                public String normalize(String s) {
                    return s == null ? "" : s.trim();
                }
            }
            """);
        CompilerTestSupport.writeSource(sourceDir, "impl", "Service", """
            package impl;
            import api.Model;
            public class Service {
                private final Helper helper = new Helper();
                public Model process(String input) {
                    Model m = new Model();
                    m.setName(helper.normalize(input));
                    return m;
                }
            }
            """);
    }

    private String compile() throws Exception {
        var sourceFiles = Files.walk(sourceDir)
            .filter(p -> p.toString().endsWith(".java"))
            .sorted()
            .toList();

        var compiler = new IncrementalJavaCompiler();
        var sw = new StringWriter();
        try (var fm = compiler.getStandardFileManager(null, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            var units = fm.getJavaFileObjectsFromPaths(sourceFiles);
            boolean success = compiler.getTask(sw, fm, null, null, null, units).call();
            assertTrue(success, "Compilation should succeed: " + sw);
        }
        return sw.toString();
    }

    @Test
    void fullBuildCompilesEverything() throws Exception {
        String log = compile();
        assertTrue(log.contains("full build"), "First run should be a full build: " + log);
        assertTrue(Files.exists(outputDir.resolve(".incremental-state")));
        assertTrue(Files.exists(outputDir.resolve(".abi-fingerprints")));
    }

    @Test
    void noChangeBuildsNothing() throws Exception {
        compile();
        String log = compile();
        assertTrue(log.contains("no changes"), "Second identical run should detect no changes: " + log);
    }

    @Test
    void bodyOnlyChangeRecompilesOnlyChangedFile() throws Exception {
        compile();

        CompilerTestSupport.writeSource(sourceDir, "impl", "Helper", """
            package impl;
            public class Helper {
                public String normalize(String s) {
                    return s == null ? "" : s.strip().toLowerCase();
                }
            }
            """);

        String log = compile();
        assertTrue(log.contains("1 file(s) compiled") && log.contains("2 unchanged"),
            "Body-only change should compile only 1 file: " + log);
    }

    @Test
    void abiChangeCascadesToConsumers() throws Exception {
        compile();

        CompilerTestSupport.writeSource(sourceDir, "api", "Model", """
            package api;
            public class Model {
                private String name;
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
                public boolean isValid() { return name != null; }
            }
            """);

        String log = compile();
        // ABI change should cascade: Model changed → Service depends on Model → both recompiled
        assertFalse(log.contains("1 file(s) compiled, 2 unchanged"),
            "ABI change should cascade beyond just the changed file: " + log);
        assertTrue(log.contains("compiled") && !log.contains("no changes"),
            "Should compile something: " + log);
    }

    @Test
    void newFileIsCompiled() throws Exception {
        compile();

        CompilerTestSupport.writeSource(sourceDir, "api", "Extra", """
            package api;
            public class Extra {
                public String value() { return "extra"; }
            }
            """);

        String log = compile();
        assertFalse(log.contains("no changes"), "Should detect the new file: " + log);
        assertTrue(log.contains("compiled"), "New file should be compiled: " + log);
    }
}
