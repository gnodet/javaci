package io.github.gnodet.javaci;

import javax.lang.model.element.*;
import javax.lang.model.type.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.*;

public class AbiExtractor {

    public static String computeFingerprint(TypeElement type) {
        return sha256(canonicalForm(type));
    }

    public static String canonicalForm(TypeElement type) {
        var sb = new StringBuilder();
        appendType(sb, type, 0);
        return sb.toString();
    }

    private static void appendType(StringBuilder sb, TypeElement type, int indent) {
        String prefix = "  ".repeat(indent);

        sb.append(prefix);
        appendModifiers(sb, type.getModifiers());
        sb.append(type.getKind().toString().toLowerCase()).append(' ');
        sb.append(type.getQualifiedName());

        var typeParams = type.getTypeParameters();
        if (!typeParams.isEmpty()) {
            sb.append('<');
            sb.append(typeParams.stream().map(tp -> {
                var tpSb = new StringBuilder(tp.getSimpleName());
                var bounds = tp.getBounds().stream()
                    .filter(b -> !"java.lang.Object".equals(b.toString()))
                    .toList();
                if (!bounds.isEmpty()) {
                    tpSb.append(" extends ");
                    tpSb.append(bounds.stream()
                        .map(TypeMirror::toString)
                        .collect(Collectors.joining(" & ")));
                }
                return tpSb.toString();
            }).collect(Collectors.joining(", ")));
            sb.append('>');
        }
        sb.append('\n');

        TypeMirror superclass = type.getSuperclass();
        if (superclass.getKind() != TypeKind.NONE
                && !"java.lang.Object".equals(superclass.toString())) {
            sb.append(prefix).append("  extends ").append(superclass).append('\n');
        }

        for (TypeMirror iface : type.getInterfaces()) {
            sb.append(prefix).append("  implements ").append(iface).append('\n');
        }

        var members = type.getEnclosedElements().stream()
            .filter(e -> !e.getModifiers().contains(Modifier.PRIVATE))
            .sorted(Comparator.<Element, Integer>comparing(e -> switch (e.getKind()) {
                case FIELD, ENUM_CONSTANT -> 0;
                case CONSTRUCTOR -> 1;
                case METHOD -> 2;
                default -> e.getKind().isClass() || e.getKind().isInterface() ? 3 : 4;
            }).thenComparing(e -> e.getSimpleName().toString())
              .thenComparing(e -> e instanceof ExecutableElement ee
                  ? ee.getParameters().stream()
                      .map(p -> p.asType().toString())
                      .collect(Collectors.joining(","))
                  : ""))
            .toList();

        for (Element member : members) {
            switch (member) {
                case VariableElement ve -> appendField(sb, ve, indent + 1);
                case ExecutableElement ee -> appendMethod(sb, ee, indent + 1);
                case TypeElement te -> appendType(sb, te, indent + 1);
                default -> {}
            }
        }
    }

    private static void appendField(StringBuilder sb, VariableElement field, int indent) {
        sb.append("  ".repeat(indent));
        appendModifiers(sb, field.getModifiers());
        sb.append(field.asType()).append(' ');
        sb.append(field.getSimpleName());
        Object constValue = field.getConstantValue();
        if (constValue != null) {
            if (constValue instanceof String s) {
                sb.append(" = \"").append(s).append('"');
            } else {
                sb.append(" = ").append(constValue);
            }
        }
        sb.append('\n');
    }

    private static void appendMethod(StringBuilder sb, ExecutableElement method, int indent) {
        sb.append("  ".repeat(indent));
        appendModifiers(sb, method.getModifiers());

        var typeParams = method.getTypeParameters();
        if (!typeParams.isEmpty()) {
            sb.append('<');
            sb.append(typeParams.stream()
                .map(tp -> tp.getSimpleName().toString())
                .collect(Collectors.joining(", ")));
            sb.append("> ");
        }

        if (method.getKind() != ElementKind.CONSTRUCTOR) {
            sb.append(method.getReturnType()).append(' ');
        }

        sb.append(method.getSimpleName()).append('(');
        sb.append(method.getParameters().stream()
            .map(p -> p.asType().toString())
            .collect(Collectors.joining(", ")));
        sb.append(')');

        var thrown = method.getThrownTypes();
        if (!thrown.isEmpty()) {
            sb.append(" throws ");
            sb.append(thrown.stream()
                .map(TypeMirror::toString)
                .collect(Collectors.joining(", ")));
        }
        sb.append('\n');
    }

    private static void appendModifiers(StringBuilder sb, Set<Modifier> modifiers) {
        for (Modifier m : List.of(
                Modifier.PUBLIC, Modifier.PROTECTED,
                Modifier.ABSTRACT, Modifier.STATIC, Modifier.FINAL,
                Modifier.SYNCHRONIZED, Modifier.NATIVE, Modifier.STRICTFP,
                Modifier.DEFAULT, Modifier.SEALED, Modifier.NON_SEALED)) {
            if (modifiers.contains(m)) {
                sb.append(m).append(' ');
            }
        }
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
