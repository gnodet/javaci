package io.github.gnodet.javaci;

import java.lang.classfile.*;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Analyzes compiled {@code .class} files using the {@link java.lang.classfile}
 * API to extract type references and compute bytecode-level ABI fingerprints.
 *
 * <p>Type references are extracted from the constant pool's {@code CONSTANT_Class}
 * entries. This captures types used in {@code new}, {@code checkcast},
 * {@code instanceof}, exception handlers, and class hierarchy — but not types
 * that only appear in field/method descriptors. For complete dependency tracking,
 * use the source-level analysis in {@link DependencyScanner} as the primary path
 * and this class for cross-validation.
 *
 * <p>The bytecode ABI reflects erased types (no generics) and is computed from
 * non-private, non-synthetic fields and methods.
 *
 * @see AbiExtractor
 * @see DependencyScanner
 */
public class BytecodeAnalyzer {

    public record ClassAnalysis(
        String className,
        String abiFingerprint,
        String abiCanonical,
        Set<String> referencedTypes
    ) {}

    public static ClassAnalysis analyze(Path classFile) throws Exception {
        return analyze(Files.readAllBytes(classFile));
    }

    public static ClassAnalysis analyze(byte[] classBytes) {
        ClassModel cm = ClassFile.of().parse(classBytes);

        String className = toJavaName(cm.thisClass().asInternalName());
        Set<String> referencedTypes = extractReferencedTypes(cm);
        referencedTypes.remove(className);

        String abiCanonical = canonicalForm(cm);
        String abiFingerprint = sha256(abiCanonical);

        return new ClassAnalysis(className, abiFingerprint, abiCanonical, referencedTypes);
    }

    private static Set<String> extractReferencedTypes(ClassModel cm) {
        var types = new TreeSet<String>();
        var cp = cm.constantPool();
        for (int i = 1; i < cp.size(); i++) {
            try {
                if (cp.entryByIndex(i) instanceof ClassEntry ce) {
                    String name = ce.asInternalName();
                    // Unwrap array descriptors
                    while (name.startsWith("[")) name = name.substring(1);
                    if (name.startsWith("L") && name.endsWith(";")) {
                        name = name.substring(1, name.length() - 1);
                    } else if (name.length() <= 1) {
                        continue; // primitive
                    }
                    types.add(toJavaName(name));
                }
            } catch (Exception ignored) {
                // Skip phantom slots (long/double second entry)
            }
        }
        return types;
    }

    private static String canonicalForm(ClassModel cm) {
        var sb = new StringBuilder();
        var flags = cm.flags();

        appendAccessFlags(sb, flags);
        if (flags.has(AccessFlag.INTERFACE)) {
            sb.append("interface ");
        } else if (flags.has(AccessFlag.ENUM)) {
            sb.append("enum ");
        } else {
            sb.append("class ");
        }
        sb.append(toJavaName(cm.thisClass().asInternalName())).append('\n');

        cm.superclass().ifPresent(sc -> {
            String name = toJavaName(sc.asInternalName());
            if (!"java.lang.Object".equals(name) && !"java.lang.Enum".equals(name)
                    && !"java.lang.Record".equals(name)) {
                sb.append("  extends ").append(name).append('\n');
            }
        });

        for (var iface : cm.interfaces()) {
            sb.append("  implements ").append(toJavaName(iface.asInternalName())).append('\n');
        }

        // Non-private, non-synthetic fields
        cm.fields().stream()
            .filter(f -> !f.flags().has(AccessFlag.PRIVATE))
            .filter(f -> !f.flags().has(AccessFlag.SYNTHETIC))
            .sorted(Comparator.comparing(f -> f.fieldName().stringValue()))
            .forEach(f -> {
                sb.append("  ");
                appendAccessFlags(sb, f.flags());
                sb.append(descriptorToReadable(f.fieldType().stringValue())).append(' ');
                sb.append(f.fieldName().stringValue());
                f.findAttribute(Attributes.constantValue()).ifPresent(cv ->
                    sb.append(" = ").append(cv.constant().constantValue()));
                sb.append('\n');
            });

        // Non-private, non-synthetic methods (excluding <clinit>)
        cm.methods().stream()
            .filter(m -> !m.flags().has(AccessFlag.PRIVATE))
            .filter(m -> !m.flags().has(AccessFlag.SYNTHETIC))
            .filter(m -> !"<clinit>".equals(m.methodName().stringValue()))
            .sorted(Comparator.comparing((MethodModel m) -> m.methodName().stringValue())
                .thenComparing(m -> m.methodType().stringValue()))
            .forEach(m -> {
                sb.append("  ");
                appendAccessFlags(sb, m.flags());
                sb.append(m.methodName().stringValue());
                sb.append('(').append(parseParams(m.methodType().stringValue())).append(')');
                String ret = parseReturn(m.methodType().stringValue());
                if (!"void".equals(ret)) {
                    sb.append(" -> ").append(ret);
                }
                sb.append('\n');
            });

        return sb.toString();
    }

    private static void appendAccessFlags(StringBuilder sb, AccessFlags flags) {
        for (var flag : List.of(AccessFlag.PUBLIC, AccessFlag.PROTECTED,
                AccessFlag.ABSTRACT, AccessFlag.STATIC, AccessFlag.FINAL)) {
            if (flags.has(flag)) {
                sb.append(flag.name().toLowerCase()).append(' ');
            }
        }
    }

    static String descriptorToReadable(String desc) {
        if (desc.isEmpty()) return desc;
        return switch (desc.charAt(0)) {
            case 'V' -> "void";
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'L' -> toJavaName(desc.substring(1, desc.indexOf(';')));
            case '[' -> descriptorToReadable(desc.substring(1)) + "[]";
            default -> desc;
        };
    }

    static String parseParams(String methodDesc) {
        int close = methodDesc.indexOf(')');
        String params = methodDesc.substring(1, close);
        var result = new ArrayList<String>();
        int i = 0;
        while (i < params.length()) {
            int start = i;
            while (i < params.length() && params.charAt(i) == '[') i++;
            if (i < params.length()) {
                if (params.charAt(i) == 'L') {
                    i = params.indexOf(';', i) + 1;
                } else {
                    i++;
                }
            }
            result.add(descriptorToReadable(params.substring(start, i)));
        }
        return String.join(", ", result);
    }

    static String parseReturn(String methodDesc) {
        return descriptorToReadable(methodDesc.substring(methodDesc.indexOf(')') + 1));
    }

    private static String toJavaName(String internalName) {
        return internalName.replace('/', '.');
    }

    private static String sha256(String input) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString().substring(0, 16);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
