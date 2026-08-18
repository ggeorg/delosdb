/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Phase 0B.1 static tripwire for the Derby-compatible Heap source boundary.
 *
 * <p>This is change control, not a substitute for the dynamic on-disk
 * interoperability proof. Production Java files classified as unmodified
 * inherited Derby are frozen by exact SHA-256. Changing one requires explicit
 * compatibility review and reclassification before the baseline can move.</p>
 */
public final class DelosHeapDerbyStaticCompatibilityContract {
    private static final HexFormat HEX = HexFormat.of();
    private static final String PREFIX = "delosdb.compatibility.heapStatic.";

    private DelosHeapDerbyStaticCompatibilityContract() {
    }

    public static void main(String[] args) throws Exception {
        Path root = requiredPath("projectDirectory");
        Path reportDirectory = requiredPath("reportDirectory");
        Path baseline = root.resolve("gradle/compatibility/derby-10.17.1.0-unmodified-production-baseline.tsv");
        Path authored = root.resolve("gradle/static-analysis/delosdb-authored-production-files.txt");
        Path modified = root.resolve("gradle/static-analysis/delosdb-modified-inherited-production-files.txt");
        Path moduleParity = root.resolve("gradle/static-analysis/delosdb-derby-module-parity.txt");

        Files.createDirectories(reportDirectory);
        Set<String> authoredPaths = loadPathManifest(authored);
        Set<String> modifiedPaths = loadPathManifest(modified);
        Map<String, String> baselineHashes = loadHashBaseline(baseline);
        Set<String> currentProduction = collectProductionJava(root);
        List<String> issues = new ArrayList<>();

        Set<String> overlap = new HashSet<>(authoredPaths);
        overlap.retainAll(modifiedPaths);
        if (!overlap.isEmpty()) {
            issues.add("Paths classified as both Delos-authored and modified inherited: " + overlap);
        }
        requireManifestPathsExist("Delos-authored", authoredPaths, currentProduction, issues);
        requireManifestPathsExist("modified inherited", modifiedPaths, currentProduction, issues);

        Set<String> currentUnmodified = new HashSet<>(currentProduction);
        currentUnmodified.removeAll(authoredPaths);
        currentUnmodified.removeAll(modifiedPaths);

        for (String path : baselineHashes.keySet()) {
            if (!currentUnmodified.contains(path)) {
                issues.add("Frozen unmodified-inherited path is no longer in that classification: " + path);
                continue;
            }
            String actual = sha256(root.resolve(path));
            String expected = baselineHashes.get(path);
            if (!expected.equals(actual)) {
                issues.add("Previously unmodified inherited Derby file changed bytes: " + path
                        + " expected=" + expected + " actual=" + actual
                        + ". Review Heap compatibility and reclassify explicitly before re-baselining.");
            }
        }
        for (String path : currentUnmodified) {
            if (!baselineHashes.containsKey(path)) {
                issues.add("Production Java path is neither authored/modified nor in the frozen inherited baseline: " + path);
            }
        }

        List<String> requiredPeers = List.of(
                "DERBY-MOD-001", "DERBY-MOD-002", "DERBY-MOD-003", "DERBY-MOD-004",
                "DERBY-MOD-005", "DERBY-MOD-006", "DERBY-MOD-007", "DERBY-MOD-008",
                "DERBY-MOD-009", "DERBY-MOD-010", "DERBY-MOD-011", "DERBY-MOD-012",
                "DERBY-MOD-013", "DERBY-MOD-014");
        Map<String, String> parity = loadModuleParity(moduleParity);
        for (String id : requiredPeers) {
            String decision = parity.get(id);
            if (!"KEEP_DERBY_PEER".equals(decision)) {
                issues.add("Required Derby module peer is missing or no longer KEEP_DERBY_PEER: " + id
                        + " decision=" + decision);
            }
        }

        String baselineDigest = aggregateBaselineDigest(baselineHashes);
        Path report = reportDirectory.resolve("heap-derby-static-compatibility.txt");
        StringBuilder text = new StringBuilder();
        text.append("DelosDB v1 Heap Derby static compatibility contract\n")
                .append("================================================\n\n")
                .append("Derby provenance baseline: Apache Derby 10.17.1.0\n")
                .append("Role: exact-byte change-control tripwire; dynamic interoperability remains the physical-format authority.\n\n")
                .append("Production Java files: ").append(currentProduction.size()).append('\n')
                .append("Delos-authored: ").append(authoredPaths.size()).append('\n')
                .append("Modified inherited Derby: ").append(modifiedPaths.size()).append('\n')
                .append("Frozen unmodified inherited Derby: ").append(currentUnmodified.size()).append('\n')
                .append("Frozen baseline entries: ").append(baselineHashes.size()).append('\n')
                .append("Frozen baseline aggregate SHA-256: ").append(baselineDigest).append('\n')
                .append("Required Derby module peers: ").append(requiredPeers.size()).append(" KEEP_DERBY_PEER\n\n")
                .append("Decision:\n")
                .append("- A change to a frozen unmodified inherited file is not automatically incompatible.\n")
                .append("- It is an explicit compatibility-review obligation and must be reclassified before the baseline moves.\n")
                .append("- The dynamic stock-Derby/Delos/stock-Derby reopen proof is the on-disk compatibility authority.\n\n")
                .append("Issues: ").append(issues.size()).append('\n');
        for (String issue : issues) {
            text.append("- ").append(issue).append('\n');
        }
        Files.writeString(report, text, StandardCharsets.UTF_8);

        if (!issues.isEmpty()) {
            throw new IllegalStateException("Heap Derby static compatibility failures (" + issues.size()
                    + "); see " + report);
        }
        System.out.println("DelosDB Heap Derby static compatibility contract passed: " + report);
    }

    private static Path requiredPath(String key) {
        String value = System.getProperty(PREFIX + key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing -D" + PREFIX + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static Set<String> collectProductionJava(Path root) throws IOException {
        Set<String> paths = new HashSet<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(path -> !containsExcludedDirectory(root.relativize(path)))
                    .forEach(path -> paths.add(relative(root, path)));
        }
        return paths;
    }

    private static boolean containsExcludedDirectory(Path relative) {
        for (Path element : relative) {
            String value = element.toString();
            if (value.equals("build") || value.equals(".gradle") || value.equals(".git") || value.equals("out")) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> loadPathManifest(Path source) throws IOException {
        Set<String> values = new HashSet<>();
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            String value = line.trim();
            if (!value.isEmpty() && !value.startsWith("#")) {
                if (!values.add(value)) {
                    throw new IllegalStateException("Duplicate path in " + source + ": " + value);
                }
            }
        }
        return values;
    }

    private static Map<String, String> loadHashBaseline(Path source) throws IOException {
        Map<String, String> values = new TreeMap<>();
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] columns = line.split("\\t", -1);
            if (columns.length != 2 || columns[0].length() != 64 || columns[1].isBlank()) {
                throw new IllegalStateException("Malformed compatibility baseline row: " + line);
            }
            if (values.put(columns[1], columns[0]) != null) {
                throw new IllegalStateException("Duplicate compatibility baseline path: " + columns[1]);
            }
        }
        return values;
    }

    private static Map<String, String> loadModuleParity(Path source) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] columns = line.split("\\|", -1);
            if (columns.length < 2) {
                throw new IllegalStateException("Malformed module parity row: " + line);
            }
            values.put(columns[0], columns[1]);
        }
        return values;
    }

    private static void requireManifestPathsExist(
            String name, Set<String> manifest, Set<String> current, List<String> issues) {
        for (String path : manifest) {
            if (!current.contains(path)) {
                issues.add(name + " manifest path is absent from production Java: " + path);
            }
        }
    }

    private static String aggregateBaselineDigest(Map<String, String> baseline) {
        MessageDigest digest = digest();
        for (Map.Entry<String, String> entry : baseline.entrySet()) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.getValue().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
        }
        return HEX.formatHex(digest.digest());
    }

    private static String sha256(Path source) {
        try {
            return HEX.formatHex(digest().digest(Files.readAllBytes(source)));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot hash " + source, failure);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }
}
