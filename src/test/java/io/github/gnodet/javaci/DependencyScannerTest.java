package io.github.gnodet.javaci;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DependencyScannerTest {

    @TempDir Path tmp;

    private Map<String, SourceFileAnalysis> compileAll(Map<String, String> sources) throws Exception {
        var result = CompilerTestSupport.compileAndAnalyze(sources, tmp);
        assertTrue(result.success(), "Compilation should succeed");
        return result.analyses();
    }

    @Test
    void extendsIsSignatureDep() throws Exception {
        var results = compileAll(Map.of(
            "pkg.Base", """
                package pkg;
                public class Base {}
                """,
            "pkg.Child", """
                package pkg;
                public class Child extends Base {}
                """
        ));

        var child = results.get("pkg.Child");
        assertNotNull(child);
        assertTrue(child.signatureDeps().contains("pkg.Base"),
            "extends type should be a signature dep");
    }

    @Test
    void implementsIsSignatureDep() throws Exception {
        var results = compileAll(Map.of(
            "pkg.Iface", """
                package pkg;
                public interface Iface { void run(); }
                """,
            "pkg.Impl", """
                package pkg;
                public class Impl implements Iface {
                    public void run() {}
                }
                """
        ));

        var impl = results.get("pkg.Impl");
        assertNotNull(impl);
        assertTrue(impl.signatureDeps().contains("pkg.Iface"));
    }

    @Test
    void methodReturnTypeIsSignatureDep() throws Exception {
        var results = compileAll(Map.of(
            "pkg.Result", """
                package pkg;
                public class Result {}
                """,
            "pkg.Factory", """
                package pkg;
                public class Factory {
                    public Result create() { return new Result(); }
                }
                """
        ));

        var factory = results.get("pkg.Factory");
        assertNotNull(factory);
        assertTrue(factory.signatureDeps().contains("pkg.Result"));
    }

    @Test
    void methodParameterTypeIsSignatureDep() throws Exception {
        var results = compileAll(Map.of(
            "pkg.Param", """
                package pkg;
                public class Param {}
                """,
            "pkg.Svc", """
                package pkg;
                public class Svc {
                    public void process(Param p) {}
                }
                """
        ));

        var svc = results.get("pkg.Svc");
        assertNotNull(svc);
        assertTrue(svc.signatureDeps().contains("pkg.Param"));
    }

    @Test
    void throwsTypeIsSignatureDep() throws Exception {
        var results = compileAll(Map.of(
            "pkg.MyException", """
                package pkg;
                public class MyException extends Exception {}
                """,
            "pkg.Svc", """
                package pkg;
                public class Svc {
                    public void run() throws MyException {}
                }
                """
        ));

        var svc = results.get("pkg.Svc");
        assertNotNull(svc);
        assertTrue(svc.signatureDeps().contains("pkg.MyException"));
    }

    @Test
    void typeUsedOnlyInBodyIsImplementationDep() throws Exception {
        var results = compileAll(Map.of(
            "pkg.Helper", """
                package pkg;
                public class Helper {
                    public static int compute() { return 42; }
                }
                """,
            "pkg.Main", """
                package pkg;
                public class Main {
                    public void run() {
                        int x = Helper.compute();
                    }
                }
                """
        ));

        var main = results.get("pkg.Main");
        assertNotNull(main);
        assertTrue(main.implementationDeps().contains("pkg.Helper"),
            "Type used only in method body should be an implementation dep");
        assertFalse(main.signatureDeps().contains("pkg.Helper"),
            "Type used only in method body should NOT be a signature dep");
    }

    @Test
    void privateFieldTypeIsImplementationDep() throws Exception {
        var results = compileAll(Map.of(
            "pkg.Dep", """
                package pkg;
                public class Dep {}
                """,
            "pkg.Owner", """
                package pkg;
                public class Owner {
                    private Dep dep = new Dep();
                    public void run() {}
                }
                """
        ));

        var owner = results.get("pkg.Owner");
        assertNotNull(owner);
        assertTrue(owner.implementationDeps().contains("pkg.Dep"),
            "Private field type should be implementation dep");
        assertFalse(owner.signatureDeps().contains("pkg.Dep"));
    }

    @Test
    void publicFieldTypeIsSignatureDep() throws Exception {
        var results = compileAll(Map.of(
            "pkg.FieldType", """
                package pkg;
                public class FieldType {}
                """,
            "pkg.Owner", """
                package pkg;
                public class Owner {
                    public FieldType field;
                }
                """
        ));

        var owner = results.get("pkg.Owner");
        assertNotNull(owner);
        assertTrue(owner.signatureDeps().contains("pkg.FieldType"));
    }

    @Test
    void importsDoNotCreateFalseDeps() throws Exception {
        var results = compileAll(Map.of(
            "pkg.Unused", """
                package pkg;
                public class Unused {}
                """,
            "pkg.Used", """
                package pkg;
                public class Used {}
                """,
            "pkg.Consumer", """
                package pkg;
                import pkg.Unused;
                import pkg.Used;
                public class Consumer {
                    public void run() { Used u = new Used(); }
                }
                """
        ));

        var consumer = results.get("pkg.Consumer");
        assertNotNull(consumer);
        assertFalse(consumer.signatureDeps().contains("pkg.Unused"),
            "Unused import should not appear as a dependency");
        assertFalse(consumer.implementationDeps().contains("pkg.Unused"),
            "Unused import should not appear as a dependency");
    }

    @Test
    void fieldInitializerIsImplementationDep() throws Exception {
        var results = compileAll(Map.of(
            "pkg.Init", """
                package pkg;
                public class Init {
                    public static Init create() { return new Init(); }
                }
                """,
            "pkg.Owner", """
                package pkg;
                public class Owner {
                    public String name = Init.create().toString();
                }
                """
        ));

        var owner = results.get("pkg.Owner");
        assertNotNull(owner);
        assertTrue(owner.implementationDeps().contains("pkg.Init"),
            "Type used in field initializer should be implementation dep");
    }

    @Test
    void noSelfReferences() throws Exception {
        var results = compileAll(Map.of(
            "pkg.Solo", """
                package pkg;
                public class Solo {
                    public Solo self() { return this; }
                }
                """
        ));

        var solo = results.get("pkg.Solo");
        assertNotNull(solo);
        assertFalse(solo.signatureDeps().contains("pkg.Solo"));
        assertFalse(solo.implementationDeps().contains("pkg.Solo"));
    }

    @Test
    void jdkTypesAreFiltered() throws Exception {
        var results = compileAll(Map.of(
            "pkg.Svc", """
                package pkg;
                import java.util.List;
                import java.util.ArrayList;
                public class Svc {
                    public List<String> items() {
                        return new ArrayList<>();
                    }
                }
                """
        ));

        var svc = results.get("pkg.Svc");
        assertNotNull(svc);
        assertTrue(svc.signatureDeps().isEmpty(), "JDK types should be filtered");
        assertTrue(svc.implementationDeps().isEmpty(), "JDK types should be filtered");
    }
}
