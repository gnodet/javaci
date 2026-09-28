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
        assertTrue(log.contains("full build"), "First run should be a full build");
        assertTrue(log.contains("3 source files") || log.contains("3 types"),
            "Should report compiling 3 files or 3 types");
        assertTrue(Files.exists(outputDir.resolve(".incremental-state")));
    }

    @Test
    void noChangeBuildsNothing() throws Exception {
        compile();
        String log = compile();
        assertTrue(log.contains("no changes"), "Second identical run should detect no changes");
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
        assertTrue(log.contains("1 changed"), "Should detect 1 changed file");
        assertTrue(log.contains("1 file(s) compiled, 2 unchanged"),
            "Body-only change should compile only 1 file, got: " + log);
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
        assertTrue(log.contains("1 changed"), "Should detect 1 changed file");
        assertTrue(log.contains("ABI changed"), "Should detect ABI change");
        assertFalse(log.contains("1 file(s) compiled, 2 unchanged"),
            "ABI change should cascade beyond just the changed file");
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
        assertTrue(log.contains("1 new") || log.contains("1 changed"),
            "Should detect the new file");
    }
}
