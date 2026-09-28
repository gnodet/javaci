package io.github.gnodet.javaci;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ProcessorClassificationTest {

    @Test
    void emptyProcessorPathClassifiesAllAsIsolating() {
        var pc = new ProcessorClassification(List.of());
        assertEquals(ProcessorType.UNKNOWN, pc.classify("com.example.MyProcessor"));
        assertEquals(ProcessorType.ISOLATING, pc.worstCase(List.of()));
    }

    @Test
    void readFromJavaCiMetaInfInDirectory(@TempDir Path tmp) throws Exception {
        Path metaInf = tmp.resolve("META-INF/javaci");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("incremental.annotation.processors"),
            "com.example.IsolatingProc,ISOLATING\n" +
            "com.example.AggregatingProc,AGGREGATING\n");

        var pc = new ProcessorClassification(List.of(tmp));

        assertEquals(ProcessorType.ISOLATING, pc.classify("com.example.IsolatingProc"));
        assertEquals(ProcessorType.AGGREGATING, pc.classify("com.example.AggregatingProc"));
        assertEquals(ProcessorType.UNKNOWN, pc.classify("com.example.Unknown"));
    }

    @Test
    void readFromGradleMetaInfInDirectory(@TempDir Path tmp) throws Exception {
        Path metaInf = tmp.resolve("META-INF/gradle");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("incremental.annotation.processors"),
            "com.example.GradleProc,ISOLATING\n");

        var pc = new ProcessorClassification(List.of(tmp));
        assertEquals(ProcessorType.ISOLATING, pc.classify("com.example.GradleProc"));
    }

    @Test
    void javaCiTakesPrecedenceOverGradle(@TempDir Path tmp) throws Exception {
        Path javaCiDir = tmp.resolve("META-INF/javaci");
        Path gradleDir = tmp.resolve("META-INF/gradle");
        Files.createDirectories(javaCiDir);
        Files.createDirectories(gradleDir);

        Files.writeString(javaCiDir.resolve("incremental.annotation.processors"),
            "com.example.Proc,ISOLATING\n");
        Files.writeString(gradleDir.resolve("incremental.annotation.processors"),
            "com.example.Proc,AGGREGATING\n");

        var pc = new ProcessorClassification(List.of(tmp));
        assertEquals(ProcessorType.ISOLATING, pc.classify("com.example.Proc"));
    }

    @Test
    void readFromJar(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("processor.jar");
        try (var jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/gradle/incremental.annotation.processors"));
            jos.write("com.example.JarProc,AGGREGATING\n".getBytes());
            jos.closeEntry();
        }

        var pc = new ProcessorClassification(List.of(jar));
        assertEquals(ProcessorType.AGGREGATING, pc.classify("com.example.JarProc"));
    }

    @Test
    void commentsAndBlankLinesIgnored(@TempDir Path tmp) throws Exception {
        Path metaInf = tmp.resolve("META-INF/javaci");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("incremental.annotation.processors"),
            "# This is a comment\n" +
            "\n" +
            "com.example.Proc,ISOLATING\n" +
            "  \n" +
            "# Another comment\n");

        var pc = new ProcessorClassification(List.of(tmp));
        assertEquals(ProcessorType.ISOLATING, pc.classify("com.example.Proc"));
        assertEquals(1, pc.getClassifications().size());
    }

    @Test
    void caseInsensitiveType(@TempDir Path tmp) throws Exception {
        Path metaInf = tmp.resolve("META-INF/javaci");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("incremental.annotation.processors"),
            "com.example.Proc,isolating\n");

        var pc = new ProcessorClassification(List.of(tmp));
        assertEquals(ProcessorType.ISOLATING, pc.classify("com.example.Proc"));
    }

    @Test
    void worstCaseWithAllIsolating(@TempDir Path tmp) throws Exception {
        Path metaInf = tmp.resolve("META-INF/javaci");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("incremental.annotation.processors"),
            "proc.A,ISOLATING\nproc.B,ISOLATING\n");

        var pc = new ProcessorClassification(List.of(tmp));
        assertEquals(ProcessorType.ISOLATING,
            pc.worstCase(List.of("proc.A", "proc.B")));
    }

    @Test
    void worstCaseWithAggregating(@TempDir Path tmp) throws Exception {
        Path metaInf = tmp.resolve("META-INF/javaci");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("incremental.annotation.processors"),
            "proc.A,ISOLATING\nproc.B,AGGREGATING\n");

        var pc = new ProcessorClassification(List.of(tmp));
        assertEquals(ProcessorType.AGGREGATING,
            pc.worstCase(List.of("proc.A", "proc.B")));
    }

    @Test
    void worstCaseWithUnknown(@TempDir Path tmp) throws Exception {
        Path metaInf = tmp.resolve("META-INF/javaci");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("incremental.annotation.processors"),
            "proc.A,ISOLATING\n");

        var pc = new ProcessorClassification(List.of(tmp));
        assertEquals(ProcessorType.UNKNOWN,
            pc.worstCase(List.of("proc.A", "proc.Unknown")));
    }

    @Test
    void nonexistentPathIgnored() {
        var pc = new ProcessorClassification(List.of(Path.of("/nonexistent/path")));
        assertEquals(ProcessorType.UNKNOWN, pc.classify("anything"));
    }

    @Test
    void multipleClasspathEntries(@TempDir Path tmp) throws Exception {
        Path dir1 = tmp.resolve("dir1");
        Path dir2 = tmp.resolve("dir2");
        Path metaInf1 = dir1.resolve("META-INF/javaci");
        Path metaInf2 = dir2.resolve("META-INF/javaci");
        Files.createDirectories(metaInf1);
        Files.createDirectories(metaInf2);

        Files.writeString(metaInf1.resolve("incremental.annotation.processors"),
            "proc.A,ISOLATING\n");
        Files.writeString(metaInf2.resolve("incremental.annotation.processors"),
            "proc.B,AGGREGATING\n");

        var pc = new ProcessorClassification(List.of(dir1, dir2));
        assertEquals(ProcessorType.ISOLATING, pc.classify("proc.A"));
        assertEquals(ProcessorType.AGGREGATING, pc.classify("proc.B"));
    }

    @Test
    void malformedLinesSkipped(@TempDir Path tmp) throws Exception {
        Path metaInf = tmp.resolve("META-INF/javaci");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("incremental.annotation.processors"),
            "nocomma\n" +
            ",ISOLATING\n" +
            "proc.A,INVALID_TYPE\n" +
            "proc.B,ISOLATING\n");

        var pc = new ProcessorClassification(List.of(tmp));
        assertEquals(1, pc.getClassifications().size());
        assertEquals(ProcessorType.ISOLATING, pc.classify("proc.B"));
    }
}
