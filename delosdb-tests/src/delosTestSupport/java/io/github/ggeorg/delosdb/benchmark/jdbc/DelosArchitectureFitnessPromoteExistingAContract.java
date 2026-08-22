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

/** Fast contract for the first PROMOTE_EXISTING executable subtranche. */
public final class DelosArchitectureFitnessPromoteExistingAContract {
    private static final String PREFIX = "delosdb.benchmark.fitnessPromoteExistingA.";

    private DelosArchitectureFitnessPromoteExistingAContract() {
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

        List<CaseSpec> cases = cases();
        for (CaseSpec spec : cases) {
            String[] fields = matrix.get(spec.caseId());
            if (fields == null || fields.length < 10) {
                throw new IllegalStateException("Missing frozen sentinel matrix row: " + spec.caseId());
            }
            if (!"BOTH".equals(fields[3]) || !"PROMOTE_EXISTING".equals(fields[4])) {
                throw new IllegalStateException(
                        "Unexpected lane/readiness for " + spec.caseId() + ": " + fields[3] + '/' + fields[4]);
            }
            if (Integer.parseInt(fields[1]) != spec.familyId()) {
                throw new IllegalStateException("Family mismatch for " + spec.caseId());
            }
        }

        StringBuilder plan = new StringBuilder(
                "caseId\tfamilyId\tworkload\toperationsPerTransaction\tclients\trows\tlanes\n");
        for (CaseSpec spec : cases) {
            plan.append(spec.caseId()).append('\t')
                    .append(spec.familyId()).append('\t')
                    .append(spec.workload()).append('\t')
                    .append(spec.operationsPerTransaction()).append('\t')
                    .append("1,8\t10000\tEMBEDDED,SERVER\n");
        }
        Files.writeString(
                reportDirectory.resolve("architecture-fitness-promote-existing-a-plan.tsv"),
                plan.toString(), StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("architecture-fitness-promote-existing-a-contract.txt"),
                contractText(), StandardCharsets.UTF_8);
        System.out.println("DelosDB Phase-1 PROMOTE_EXISTING-A contract passed: " + reportDirectory);
    }

    private static List<CaseSpec> cases() {
        return List.of(
                new CaseSpec("F03-PROJECT-COVERED", 3, "PROJECTION_COVERED", 1),
                new CaseSpec("F03-PROJECT-TWO-COLUMN", 3, "PROJECTION_TWO_COLUMN", 1),
                new CaseSpec("F03-PROJECT-FULL-ROW", 3, "PROJECTION_FULL_ROW", 1),
                new CaseSpec("F06-GROUP-LOW-CARD", 6, "GROUP_LOW_CARD", 1),
                new CaseSpec("F07-ORDER-SATISFIED", 7, "RANGE_SCAN_1000", 1),
                new CaseSpec("F07-SORT-FULL", 7, "SORT_FULL", 1),
                new CaseSpec("F10-DELETE-REINSERT-1", 10, "DELETE_REINSERT", 1),
                new CaseSpec("F10-DELETE-REINSERT-10", 10, "DELETE_REINSERT", 10));
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
                DelosDB Phase-1 PROMOTE_EXISTING-A architecture-fitness contract
                ===============================================================

                Frozen PROMOTE_EXISTING cases: 10.
                This subtranche promotes 8 homogeneous-client cases: F03 x3, F06 x1, F07 x2, F10 x2.
                F12 x2 remains PROMOTE_EXISTING and is the immediately following long-reader/writer subtranche.
                Lanes: embedded and server.
                Rows: 10000.
                Clients: 1 and 8.
                Semantic authority: Phase-0A DelosSqlSemanticOracle.
                Performance substrate: existing DelosJdbcCrossEngineConcurrency; no second benchmark engine.
                F07-ORDER-SATISFIED reuses RANGE_SCAN_1000 exactly.
                F10 width 1 and 10 execute delete+identical reinsert pairs in one transaction and require
                exact SQL-visible full-row post-state equality after every interval.
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

    private record CaseSpec(String caseId, int familyId, String workload, int operationsPerTransaction) {
    }
}
