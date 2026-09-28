package io.github.gnodet.javaci;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class IncrementalStateTest {

    @Test
    void loadNonexistentFileReturnsNull(@TempDir Path tmp) {
        assertNull(IncrementalState.load(tmp.resolve("does-not-exist")));
    }

    @Test
    void loadCorruptedFileReturnsNull(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("corrupt");
        Files.write(file, new byte[]{0, 0, 0, 99});
        assertNull(IncrementalState.load(file));
    }

    @Test
    void loadTruncatedFileReturnsNull(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("truncated");
        Files.write(file, new byte[]{0, 0, 0, 1, 0, 0});
        assertNull(IncrementalState.load(file));
    }

    @Test
    void saveAndLoadRoundTrip(@TempDir Path tmp) throws IOException {
        var state = new IncrementalState();
        state.setSourceHash("src/Foo.java", "abc123");
        state.setSourceHash("src/Bar.java", "def456");
        state.setType("pkg.Foo", new IncrementalState.TypeInfo(
            "src/Foo.java", "fp1",
            new TreeSet<>(Set.of("pkg.Bar")),
            new TreeSet<>(Set.of("pkg.Helper")),
            new TreeSet<>()
        ));
        state.setType("pkg.Bar", new IncrementalState.TypeInfo(
            "src/Bar.java", "fp2",
            new TreeSet<>(),
            new TreeSet<>(),
            new TreeSet<>()
        ));

        Path file = tmp.resolve("state.bin");
        state.save(file);
        assertTrue(Files.exists(file));

        var loaded = IncrementalState.load(file);
        assertNotNull(loaded);

        assertEquals("abc123", loaded.getSourceHash("src/Foo.java"));
        assertEquals("def456", loaded.getSourceHash("src/Bar.java"));
        assertNull(loaded.getSourceHash("nonexistent"));

        var fooType = loaded.getType("pkg.Foo");
        assertNotNull(fooType);
        assertEquals("src/Foo.java", fooType.sourceFile());
        assertEquals("fp1", fooType.abiFingerprint());
        assertEquals(Set.of("pkg.Bar"), fooType.signatureDeps());
        assertEquals(Set.of("pkg.Helper"), fooType.implementationDeps());

        var barType = loaded.getType("pkg.Bar");
        assertNotNull(barType);
        assertTrue(barType.signatureDeps().isEmpty());
        assertTrue(barType.implementationDeps().isEmpty());
    }

    @Test
    void getAbiFingerprint() {
        var state = new IncrementalState();
        state.setType("A", new IncrementalState.TypeInfo("a.java", "hash1",
            new TreeSet<>(), new TreeSet<>(), new TreeSet<>()));
        assertEquals("hash1", state.getAbiFingerprint("A"));
        assertNull(state.getAbiFingerprint("Unknown"));
    }

    @Test
    void removeSource() {
        var state = new IncrementalState();
        state.setSourceHash("a.java", "h1");
        state.setSourceHash("b.java", "h2");
        state.setType("A", new IncrementalState.TypeInfo("a.java", "fp",
            new TreeSet<>(), new TreeSet<>(), new TreeSet<>()));
        state.setType("B", new IncrementalState.TypeInfo("b.java", "fp2",
            new TreeSet<>(), new TreeSet<>(), new TreeSet<>()));

        state.removeSource("a.java");
        assertNull(state.getSourceHash("a.java"));
        assertNull(state.getType("A"));
        assertNotNull(state.getType("B"));
    }

    @Test
    void getTypesFromSource() {
        var state = new IncrementalState();
        state.setType("A", new IncrementalState.TypeInfo("a.java", "fp1",
            new TreeSet<>(), new TreeSet<>(), new TreeSet<>()));
        state.setType("B", new IncrementalState.TypeInfo("a.java", "fp2",
            new TreeSet<>(), new TreeSet<>(), new TreeSet<>()));
        state.setType("C", new IncrementalState.TypeInfo("c.java", "fp3",
            new TreeSet<>(), new TreeSet<>(), new TreeSet<>()));

        var fromA = state.getTypesFromSource("a.java");
        assertEquals(2, fromA.size());
        assertTrue(fromA.contains("A"));
        assertTrue(fromA.contains("B"));
        assertTrue(state.getTypesFromSource("none.java").isEmpty());
    }

    @Test
    void signatureAndImplementationConsumers() {
        var state = new IncrementalState();
        state.setType("A", new IncrementalState.TypeInfo("a.java", "fp",
            new TreeSet<>(), new TreeSet<>(), new TreeSet<>()));
        state.setType("B", new IncrementalState.TypeInfo("b.java", "fp",
            new TreeSet<>(Set.of("A")), new TreeSet<>(), new TreeSet<>()));
        state.setType("C", new IncrementalState.TypeInfo("c.java", "fp",
            new TreeSet<>(), new TreeSet<>(Set.of("A")), new TreeSet<>()));

        assertEquals(Set.of("B"), state.getSignatureConsumers("A"));
        assertEquals(Set.of("C"), state.getImplementationConsumers("A"));
        assertEquals(Set.of("B", "C"), state.getAllConsumers("A"));
        assertTrue(state.getSignatureConsumers("B").isEmpty());
    }

    @Test
    void sourceFileFor() {
        var state = new IncrementalState();
        state.setType("X", new IncrementalState.TypeInfo("x.java", "fp",
            new TreeSet<>(), new TreeSet<>(), new TreeSet<>()));
        assertEquals("x.java", state.sourceFileFor("X"));
        assertNull(state.sourceFileFor("Y"));
    }

    @Test
    void copy() {
        var state = new IncrementalState();
        state.setSourceHash("a.java", "h1");
        state.setType("A", new IncrementalState.TypeInfo("a.java", "fp",
            new TreeSet<>(), new TreeSet<>(), new TreeSet<>()));

        var copy = state.copy();
        assertEquals("h1", copy.getSourceHash("a.java"));
        assertNotNull(copy.getType("A"));

        copy.setSourceHash("b.java", "h2");
        assertNull(state.getSourceHash("b.java"));
    }
}
