package io.github.gnodet.javaci;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AbiExtractorTest {

    @TempDir Path tmp;

    @Test
    void bodyOnlyChangeProducesSameFingerprint() throws Exception {
        String v1 = """
            package pkg;
            public class Foo {
                public int compute(int x) { return x + 1; }
            }
            """;
        String v2 = """
            package pkg;
            public class Foo {
                public int compute(int x) { return x * 2 + 3; }
            }
            """;

        var a1 = CompilerTestSupport.compileSingleType("pkg.Foo", v1, tmp.resolve("v1"));
        var a2 = CompilerTestSupport.compileSingleType("pkg.Foo", v2, tmp.resolve("v2"));

        assertEquals(a1.abiFingerprint(), a2.abiFingerprint());
    }

    @Test
    void addingPublicMethodChangesFingerprint() throws Exception {
        String v1 = """
            package pkg;
            public class Foo {
                public int compute(int x) { return x; }
            }
            """;
        String v2 = """
            package pkg;
            public class Foo {
                public int compute(int x) { return x; }
                public String describe() { return "foo"; }
            }
            """;

        var a1 = CompilerTestSupport.compileSingleType("pkg.Foo", v1, tmp.resolve("v1"));
        var a2 = CompilerTestSupport.compileSingleType("pkg.Foo", v2, tmp.resolve("v2"));

        assertNotEquals(a1.abiFingerprint(), a2.abiFingerprint());
    }

    @Test
    void changingConstantValueChangesFingerprint() throws Exception {
        String v1 = """
            package pkg;
            public class Consts {
                public static final int MAX = 100;
            }
            """;
        String v2 = """
            package pkg;
            public class Consts {
                public static final int MAX = 200;
            }
            """;

        var a1 = CompilerTestSupport.compileSingleType("pkg.Consts", v1, tmp.resolve("v1"));
        var a2 = CompilerTestSupport.compileSingleType("pkg.Consts", v2, tmp.resolve("v2"));

        assertNotEquals(a1.abiFingerprint(), a2.abiFingerprint());
        assertTrue(a1.abiCanonical().contains("MAX = 100"));
        assertTrue(a2.abiCanonical().contains("MAX = 200"));
    }

    @Test
    void privateMembersDontAffectFingerprint() throws Exception {
        String v1 = """
            package pkg;
            public class Foo {
                private int secret = 42;
                public int getPublic() { return secret; }
            }
            """;
        String v2 = """
            package pkg;
            public class Foo {
                private String secret = "changed";
                private void helper() {}
                public int getPublic() { return 0; }
            }
            """;

        var a1 = CompilerTestSupport.compileSingleType("pkg.Foo", v1, tmp.resolve("v1"));
        var a2 = CompilerTestSupport.compileSingleType("pkg.Foo", v2, tmp.resolve("v2"));

        assertEquals(a1.abiFingerprint(), a2.abiFingerprint());
    }

    @Test
    void genericTypeParametersIncluded() throws Exception {
        String source = """
            package pkg;
            public interface Container<T extends Comparable<T>> {
                T get();
                void put(T item);
            }
            """;

        var analysis = CompilerTestSupport.compileSingleType("pkg.Container", source, tmp.resolve("out"));

        assertTrue(analysis.abiCanonical().contains("Container<T extends java.lang.Comparable<T>>"));
    }

    @Test
    void changingGenericBoundChangesFingerprint() throws Exception {
        String v1 = """
            package pkg;
            public interface Box<T extends Number> { T get(); }
            """;
        String v2 = """
            package pkg;
            public interface Box<T extends Comparable<T>> { T get(); }
            """;

        var a1 = CompilerTestSupport.compileSingleType("pkg.Box", v1, tmp.resolve("v1"));
        var a2 = CompilerTestSupport.compileSingleType("pkg.Box", v2, tmp.resolve("v2"));

        assertNotEquals(a1.abiFingerprint(), a2.abiFingerprint());
    }

    @Test
    void throwsClauseIncluded() throws Exception {
        String source = """
            package pkg;
            public class Svc {
                public void run() throws java.io.IOException {}
            }
            """;
        var analysis = CompilerTestSupport.compileSingleType("pkg.Svc", source, tmp.resolve("out"));
        assertTrue(analysis.abiCanonical().contains("throws java.io.IOException"));
    }

    @Test
    void superclassAndInterfacesIncluded() throws Exception {
        String source = """
            package pkg;
            public class MyList extends java.util.AbstractList<String>
                    implements java.io.Serializable {
                public String get(int index) { return null; }
                public int size() { return 0; }
            }
            """;
        var analysis = CompilerTestSupport.compileSingleType("pkg.MyList", source, tmp.resolve("out"));
        assertTrue(analysis.abiCanonical().contains("extends java.util.AbstractList<java.lang.String>"));
        assertTrue(analysis.abiCanonical().contains("implements java.io.Serializable"));
    }
}
