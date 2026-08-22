/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fast contract for the heterogeneous-role F12 PROMOTE_EXISTING subtranche. */
public final class DelosArchitectureFitnessPromoteExistingBContract {
    private static final String PREFIX = "delosdb.benchmark.fitnessPromoteExistingB.";

    private DelosArchitectureFitnessPromoteExistingBContract() {
    }

    public static void main(String[] args) throws Exception {
        Path matrixTsv = Path.of(required(PREFIX + "matrixTsv"));
        Path reportDirectory = Path.of(required(PREFIX + "reportDirectory"));
        Files.createDirectories(reportDirectory);

        Map<String, String[]> matrix = readMatrix(matrixTsv);
        Set<String> promoted = new LinkedHashSet<>();
        for (String[] fields : matrix.values()) {
            if (fields.length >= 5 && "PROMOTE_EXISTING".equals(fields[4])) {
                promoted.add(fields[0]);
            }
        }
        Set<String> expectedAll = Set.of(
                "F03-PROJECT-COVERED",
                "F03-PROJECT-TWO-COLUMN",
                "F03-PROJECT-FULL-ROW",
                "F06-GROUP-LOW-CARD",
                "F07-ORDER-SATISFIED",
                "F07-SORT-FULL",
                "F10-DELETE-REINSERT-1",
                "F10-DELETE-REINSERT-10",
                "F12-LONG-READER-DISJOINT-WRITER",
                "F12-LONG-READER-HOT-WRITER");
        if (!promoted.equals(expectedAll)) {
            throw new IllegalStateException(
                    "PROMOTE_EXISTING matrix drift: expected=" + expectedAll + ", actual=" + promoted);
        }

        List<CaseSpec> cases = List.of(
                new CaseSpec(
                        "F12-LONG-READER-DISJOINT-WRITER",
                        "LONG_READER_DISJOINT_WRITER"),
                new CaseSpec(
                        "F12-LONG-READER-HOT-WRITER",
                        "LONG_READER_HOT_WRITER"));
        for (CaseSpec spec : cases) {
            String[] fields = matrix.get(spec.caseId());
            if (fields == null || fields.length < 10) {
                throw new IllegalStateException("Missing frozen sentinel matrix row: " + spec.caseId());
            }
            if (!"12".equals(fields[1])
                    || !"BOTH".equals(fields[3])
                    || !"PROMOTE_EXISTING".equals(fields[4])) {
                throw new IllegalStateException(
                        "Unexpected F12 family/lane/readiness for " + spec.caseId());
            }
            if (!"CONCURRENT_LONG_RESULT".equals(fields[8])) {
                throw new IllegalStateException(
                        "Unexpected F12 protocol classification for " + spec.caseId() + ": " + fields[8]);
            }
            if (!fields[9].contains("MvccSqlLongReaderPurgeStressTest")) {
                throw new IllegalStateException(
                        "F12 reuse source drift for " + spec.caseId() + ": " + fields[9]);
            }
        }

        StringBuilder plan = new StringBuilder(
                "caseId\tfamilyId\tworkload\treaderIsolation\twriters\trows\treaderRange\tlanes\n");
        for (CaseSpec spec : cases) {
            plan.append(spec.caseId()).append('\t')
                    .append("12\t")
                    .append(spec.workload()).append('\t')
                    .append("REPEATABLE_READ\t4\t10000\tmiddle-50-percent\tEMBEDDED,SERVER\n");
        }
        Files.writeString(
                reportDirectory.resolve("architecture-fitness-promote-existing-b-plan.tsv"),
                plan.toString(), StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("architecture-fitness-promote-existing-b-contract.txt"),
                contractText(), StandardCharsets.UTF_8);
        System.out.println("DelosDB Phase-1 PROMOTE_EXISTING-B contract passed: " + reportDirectory);
    }

    private static Map<String, String[]> readMatrix(Path matrixTsv) throws Exception {
        List<String> lines = Files.readAllLines(matrixTsv, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().startsWith("caseId\tfamilyId\t")) {
            throw new IllegalStateException("Unexpected sentinel matrix TSV: " + matrixTsv);
        }
        Map<String, String[]> matrix = new HashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            if (!lines.get(index).isBlank()) {
                String[] fields = lines.get(index).split("\\t", -1);
                matrix.put(fields[0], fields);
            }
        }
        return Map.copyOf(matrix);
    }

    private static String contractText() {
        return """
                DelosDB Phase-1 PROMOTE_EXISTING-B architecture-fitness contract
                ===============================================================

                Frozen PROMOTE_EXISTING cases: 10.
                Step 8A promotes F03 x3, F06 x1, F07 x2, F10 x2.
                Step 8B promotes the remaining F12 x2 heterogeneous-role cases.
                Topology: one REPEATABLE READ long reader plus four READ COMMITTED writers.
                Reader shape: ordered middle-50-percent range over the 10k-row fixture.
                DISJOINT writer keys: two outside and two inside the held reader range.
                HOT writer shape: all four writers contend on one key inside the held reader range.
                Reader transaction remains open for a fixed 25 ms overlap window after its snapshot scan.
                Semantic authority: reader snapshot fingerprint + exact writer post-state via Phase-0A oracle.
                Interference evidence: writer throughput, retryable rollback count, and per-interval
                writers-completed-before-reader-release log evidence.
                Performance substrate: DelosJdbcCrossEngineConcurrency with a heterogeneous F12 interval path;
                no second cross-engine runner.
                Lanes: embedded and server.
                This contract is a normal-development wiring gate; performance acceptance remains separate.
                """;
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property: " + property);
        }
        return value;
    }

    private record CaseSpec(String caseId, String workload) {
    }
}
