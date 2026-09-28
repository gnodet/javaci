package io.github.gnodet.javaci;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class IncrementalState {

    private static final int VERSION = 1;

    private final Map<String, String> sourceHashes = new LinkedHashMap<>();
    private final Map<String, TypeInfo> types = new LinkedHashMap<>();

    public record TypeInfo(
        String sourceFile,
        String abiFingerprint,
        Set<String> signatureDeps,
        Set<String> implementationDeps
    ) {}

    public String getSourceHash(String path) {
        return sourceHashes.get(path);
    }

    public Map<String, String> getSourceHashes() {
        return Collections.unmodifiableMap(sourceHashes);
    }

    public TypeInfo getType(String qualifiedName) {
        return types.get(qualifiedName);
    }

    public Map<String, TypeInfo> getTypes() {
        return Collections.unmodifiableMap(types);
    }

    public String getAbiFingerprint(String qualifiedName) {
        TypeInfo info = types.get(qualifiedName);
        return info != null ? info.abiFingerprint() : null;
    }

    public void setSourceHash(String path, String hash) {
        sourceHashes.put(path, hash);
    }

    public void setType(String qualifiedName, TypeInfo info) {
        types.put(qualifiedName, info);
    }

    public void removeSource(String path) {
        sourceHashes.remove(path);
        types.entrySet().removeIf(e -> e.getValue().sourceFile().equals(path));
    }

    public List<String> getTypesFromSource(String sourceFile) {
        return types.entrySet().stream()
            .filter(e -> e.getValue().sourceFile().equals(sourceFile))
            .map(Map.Entry::getKey)
            .toList();
    }

    public Set<String> getSignatureConsumers(String type) {
        var result = new TreeSet<String>();
        for (var entry : types.entrySet()) {
            if (entry.getValue().signatureDeps().contains(type)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public Set<String> getImplementationConsumers(String type) {
        var result = new TreeSet<String>();
        for (var entry : types.entrySet()) {
            if (entry.getValue().implementationDeps().contains(type)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public Set<String> getAllConsumers(String type) {
        var result = getSignatureConsumers(type);
        result.addAll(getImplementationConsumers(type));
        return result;
    }

    public String sourceFileFor(String typeName) {
        TypeInfo info = types.get(typeName);
        return info != null ? info.sourceFile() : null;
    }

    public IncrementalState copy() {
        var copy = new IncrementalState();
        copy.sourceHashes.putAll(this.sourceHashes);
        copy.types.putAll(this.types);
        return copy;
    }

    public static IncrementalState from(Map<String, String> sourceHashes,
            Map<String, SourceFileAnalysis> results) {
        var state = new IncrementalState();
        state.sourceHashes.putAll(sourceHashes);
        for (var result : results.values()) {
            state.types.put(result.qualifiedName(), new TypeInfo(
                result.sourceFile(), result.abiFingerprint(),
                result.signatureDeps(), result.implementationDeps()));
        }
        return state;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(VERSION);
            out.writeInt(sourceHashes.size());
            for (var entry : sourceHashes.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
            }
            out.writeInt(types.size());
            for (var entry : types.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue().sourceFile());
                out.writeUTF(entry.getValue().abiFingerprint());
                writeStringSet(out, entry.getValue().signatureDeps());
                writeStringSet(out, entry.getValue().implementationDeps());
            }
        }
    }

    public static IncrementalState load(Path file) {
        if (!Files.exists(file)) return null;
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int version = in.readInt();
            if (version != VERSION) return null;

            var state = new IncrementalState();
            int sourceCount = in.readInt();
            for (int i = 0; i < sourceCount; i++) {
                state.sourceHashes.put(in.readUTF(), in.readUTF());
            }
            int typeCount = in.readInt();
            for (int i = 0; i < typeCount; i++) {
                String name = in.readUTF();
                String sourceFile = in.readUTF();
                String abi = in.readUTF();
                Set<String> sigDeps = readStringSet(in);
                Set<String> implDeps = readStringSet(in);
                state.types.put(name, new TypeInfo(sourceFile, abi, sigDeps, implDeps));
            }
            return state;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeStringSet(DataOutputStream out, Set<String> set) throws IOException {
        out.writeInt(set.size());
        for (String s : set) out.writeUTF(s);
    }

    private static Set<String> readStringSet(DataInputStream in) throws IOException {
        int count = in.readInt();
        var set = new TreeSet<String>();
        for (int i = 0; i < count; i++) set.add(in.readUTF());
        return set;
    }
}
