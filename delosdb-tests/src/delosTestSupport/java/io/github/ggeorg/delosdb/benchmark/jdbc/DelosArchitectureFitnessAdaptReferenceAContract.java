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

/** Fast contract for the homogeneous ADAPT_REFERENCE subtranche. */
public final class DelosArchitectureFitnessAdaptReferenceAContract {
    private static final String PREFIX = "delosdb.benchmark.fitnessAdaptReferenceA.";

    private DelosArchitectureFitnessAdaptReferenceAContract() {
    }

    public static void main(String[] args) throws Exception {
        Path matrixTsv = Path.of(required(PREFIX + "matrixTsv"));
        Path reportDirectory = Path.of(required(PREFIX + "reportDirectory"));
        Files.createDirectories(reportDirectory);

        Map<String, String[]> matrix = readMatrix(matrixTsv);
        Set<String> adapted = new LinkedHashSet<>();
        for (String[] fields : matrix.values()) {
            if (fields.length >= 5 && "ADAPT_REFERENCE".equals(fields[4])) {
                adapted.add(fields[0]);
            }
        }
        Set<String> expectedAll = Set.of(
                "F04-JOIN-INDEXED-1TO1",
                "F06-GROUP-HIGH-CARD",
                "F08-INSERT-1",
                "F08-INSERT-100",
                "F13-BANK-TRANSACTION",
                "F13-ORDER-ENTRY-MIX");
        if (!adapted.equals(expectedAll)) {
            throw new IllegalStateException(
                    "ADAPT_REFERENCE matrix drift: expected=" + expectedAll + ", actual=" + adapted);
        }

        for (CaseSpec spec : cases()) {
            String[] fields = matrix.get(spec.caseId());
            if (fields == null || fields.length < 10) {
                throw new IllegalStateException("Missing frozen sentinel matrix row: " + spec.caseId());
            }
            if (!"BOTH".equals(fields[3]) || !"ADAPT_REFERENCE".equals(fields[4])) {
                throw new IllegalStateException(
                        "Unexpected lane/readiness for " + spec.caseId() + ": "
                                + fields[3] + '/' + fields[4]);
            }
            if (Integer.parseInt(fields[1]) != spec.familyId()) {
                throw new IllegalStateException("Family mismatch for " + spec.caseId());
            }
            if (!spec.protocol().equals(fields[8])) {
                throw new IllegalStateException(
                        "Protocol mismatch for " + spec.caseId() + ": " + fields[8]);
            }
        }

        StringBuilder plan = new StringBuilder(
                "caseId\tfamilyId\tworkload\toperationsPerTransaction\tclients\trows\tlanes\n");
        for (CaseSpec spec : cases()) {
            plan.append(spec.caseId()).append('\t')
                    .append(spec.familyId()).append('\t')
                    .append(spec.workload()).append('\t')
                    .append(spec.operationsPerTransaction()).append('\t')
                    .append("1,8\t10000\tEMBEDDED,SERVER\n");
        }
        Files.writeString(
                reportDirectory.resolve("architecture-fitness-adapt-reference-a-plan.tsv"),
                plan.toString(), StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("architecture-fitness-adapt-reference-a-contract.txt"),
                contractText(), StandardCharsets.UTF_8);
        System.out.println("DelosDB Phase-1 ADAPT_REFERENCE-A contract passed: " + reportDirectory);
    }

    private static List<CaseSpec> cases() {
        return List.of(
                new CaseSpec(
                        "F04-JOIN-INDEXED-1TO1", 4, "JOIN_INDEXED_1TO1", 1,
                        "RESULT_FETCH"),
                new CaseSpec(
                        "F06-GROUP-HIGH-CARD", 6, "GROUP_HIGH_CARD", 1,
                        "RESULT_FETCH"),
                new CaseSpec(
                        "F08-INSERT-1", 8, "INSERT_1", 1,
                        "WRITE_COMMIT"),
                new CaseSpec(
                        "F08-INSERT-100", 8, "INSERT_100", 100,
                        "TRANSACTION_AMORTIZATION"));
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
                DelosDB Phase-1 ADAPT_REFERENCE-A architecture-fitness contract
                ===============================================================

                Frozen ADAPT_REFERENCE cases: 6.
                This subtranche adapts 4 homogeneous/reference-shaped cases: F04 x1, F06-high x1, F08 x2.
                F13 x2 remains ADAPT_REFERENCE and is the immediately following ADAPT_REFERENCE-B realistic-transaction subtranche.
                Lanes: embedded and server.
                Rows: 10000.
                Clients: 1 and 8.
                Semantic authority: Phase-0A DelosSqlSemanticOracle.
                Performance substrate: DelosJdbcCrossEngineConcurrency; inherited workload semantics are adapted,
                while legacy Runner/random/control machinery is not reused.
                F04 uses the core 10k-row fact table plus a 1k primary-key dimension and an indexed 1:1 equality join.
                F06-high uses a materialized 1000-group table with exactly 10 rows/group at the frozen 10k fixture size.
                F08-INSERT-1 inserts one deterministic indexed row per transaction.
                F08-INSERT-100 uses a 100-row JDBC PreparedStatement batch per transaction.
                Insert IDs are disjoint across clients; exact committed row state is verified before cleanup outside
                the timed interval so every interval starts from the same 10k-row baseline.
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

    private record CaseSpec(
            String caseId,
            int familyId,
            String workload,
            int operationsPerTransaction,
            String protocol) {
    }
}
