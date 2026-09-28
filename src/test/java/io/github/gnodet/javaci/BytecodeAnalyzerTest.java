package io.github.gnodet.javaci;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BytecodeAnalyzerTest {

    @Test
    void descriptorToReadableVoid() {
        assertEquals("void", BytecodeAnalyzer.descriptorToReadable("V"));
    }

    @Test
    void descriptorToReadablePrimitives() {
        assertEquals("boolean", BytecodeAnalyzer.descriptorToReadable("Z"));
        assertEquals("byte", BytecodeAnalyzer.descriptorToReadable("B"));
        assertEquals("char", BytecodeAnalyzer.descriptorToReadable("C"));
        assertEquals("short", BytecodeAnalyzer.descriptorToReadable("S"));
        assertEquals("int", BytecodeAnalyzer.descriptorToReadable("I"));
        assertEquals("long", BytecodeAnalyzer.descriptorToReadable("J"));
        assertEquals("float", BytecodeAnalyzer.descriptorToReadable("F"));
        assertEquals("double", BytecodeAnalyzer.descriptorToReadable("D"));
    }

    @Test
    void descriptorToReadableObjectType() {
        assertEquals("java.lang.String", BytecodeAnalyzer.descriptorToReadable("Ljava/lang/String;"));
        assertEquals("com.example.Foo", BytecodeAnalyzer.descriptorToReadable("Lcom/example/Foo;"));
    }

    @Test
    void descriptorToReadableArrayTypes() {
        assertEquals("int[]", BytecodeAnalyzer.descriptorToReadable("[I"));
        assertEquals("java.lang.String[]", BytecodeAnalyzer.descriptorToReadable("[Ljava/lang/String;"));
        assertEquals("double[][]", BytecodeAnalyzer.descriptorToReadable("[[D"));
    }

    @Test
    void descriptorToReadableEmptyString() {
        assertEquals("", BytecodeAnalyzer.descriptorToReadable(""));
    }

    @Test
    void parseParamsNoArgs() {
        assertEquals("", BytecodeAnalyzer.parseParams("()V"));
    }

    @Test
    void parseParamsSinglePrimitive() {
        assertEquals("int", BytecodeAnalyzer.parseParams("(I)V"));
    }

    @Test
    void parseParamsMultipleTypes() {
        assertEquals("int, java.lang.String, double",
            BytecodeAnalyzer.parseParams("(ILjava/lang/String;D)V"));
    }

    @Test
    void parseParamsArrayArg() {
        assertEquals("java.lang.String[]",
            BytecodeAnalyzer.parseParams("([Ljava/lang/String;)V"));
    }

    @Test
    void parseReturnVoid() {
        assertEquals("void", BytecodeAnalyzer.parseReturn("()V"));
    }

    @Test
    void parseReturnPrimitive() {
        assertEquals("int", BytecodeAnalyzer.parseReturn("()I"));
    }

    @Test
    void parseReturnObjectType() {
        assertEquals("java.lang.String",
            BytecodeAnalyzer.parseReturn("()Ljava/lang/String;"));
    }

    @Test
    void parseReturnArray() {
        assertEquals("byte[]", BytecodeAnalyzer.parseReturn("()[B"));
    }

    @Test
    void parseParamsAndReturnComplex() {
        String desc = "(Ljava/util/List;[BILjava/lang/String;)Ljava/util/Map;";
        assertEquals("java.util.List, byte[], int, java.lang.String",
            BytecodeAnalyzer.parseParams(desc));
        assertEquals("java.util.Map", BytecodeAnalyzer.parseReturn(desc));
    }
}
