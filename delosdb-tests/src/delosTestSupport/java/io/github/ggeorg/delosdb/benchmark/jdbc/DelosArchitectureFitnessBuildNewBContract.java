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

/** Fast contract for the mixed reader/writer BUILD_NEW subtranche. */
public final class DelosArchitectureFitnessBuildNewBContract {
    private static final String PREFIX = "delosdb.benchmark.fitnessBuildNewB.";

    private DelosArchitectureFitnessBuildNewBContract() {
    }

    public static void main(String[] args) throws Exception {
        Path matrixTsv = Path.of(required(PREFIX + "matrixTsv"));
        Path reportDirectory = Path.of(required(PREFIX + "reportDirectory"));
        Files.createDirectories(reportDirectory);

        Map<String, String[]> matrix = readMatrix(matrixTsv);
        Set<String> buildNew = new LinkedHashSet<>();
        for (String[] fields : matrix.values()) {
            if (fields.length >= 5 && "BUILD_NEW".equals(fields[4])) {
                buildNew.add(fields[0]);
            }
        }
        Set<String> expectedAll = Set.of(
                "F04-JOIN-INDEXED-FANOUT",
                "F05-JOIN-3WAY-SELECTIVE",
                "F05-JOIN-4WAY-FANOUT",
                "F11-MIXED-80R20W",
                "F11-MIXED-50R50W-HOT");
        if (!buildNew.equals(expectedAll)) {
            throw new IllegalStateException(
                    "BUILD_NEW matrix drift: expected=" + expectedAll + ", actual=" + buildNew);
        }

        for (CaseSpec spec : cases()) {
            String[] fields = matrix.get(spec.caseId());
            if (fields == null || fields.length < 10) {
                throw new IllegalStateException("Missing frozen sentinel matrix row: " + spec.caseId());
            }
            if (!"BOTH".equals(fields[3]) || !"BUILD_NEW".equals(fields[4])) {
                throw new IllegalStateException(
                        "Unexpected lane/readiness for " + spec.caseId() + ": "
                                + fields[3] + '/' + fields[4]);
            }
            if (Integer.parseInt(fields[1]) != 11) {
                throw new IllegalStateException("Family mismatch for " + spec.caseId());
            }
            if (!"MIXED_TRANSACTION".equals(fields[8])) {
                throw new IllegalStateException(
                        "Protocol mismatch for " + spec.caseId() + ": " + fields[8]);
            }
        }

        StringBuilder plan = new StringBuilder(
                "caseId\tfamilyId\tworkload\toperationsPerTransaction\tclients\trows\ttransactionsPerClient\tlanes\n");
        for (CaseSpec spec : cases()) {
            plan.append(spec.caseId()).append('\t')
                    .append("11\t")
                    .append(spec.workload()).append('\t')
                    .append("1\t8\t10000\t50\tEMBEDDED,SERVER\n");
        }
        Files.writeString(
                reportDirectory.resolve("architecture-fitness-build-new-b-plan.tsv"),
                plan.toString(), StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("architecture-fitness-build-new-b-contract.txt"),
                contractText(), StandardCharsets.UTF_8);
        System.out.println("DelosDB Phase-1 BUILD_NEW-B contract passed: " + reportDirectory);
    }

    private static List<CaseSpec> cases() {
        return List.of(
                new CaseSpec("F11-MIXED-80R20W", "MIXED_80R20W"),
                new CaseSpec("F11-MIXED-50R50W-HOT", "MIXED_50R50W_HOT"));
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
                DelosDB Phase-1 BUILD_NEW-B architecture-fitness contract
                ===========================================================

                Frozen BUILD_NEW cases: 5.
                BUILD_NEW-A owns the 3 relational join sentinels.
                BUILD_NEW-B owns the final 2 F11 mixed reader/writer sentinels.
                Lanes: embedded and server.
                Rows: 10000.
                Clients: exactly 8.
                Operations per transaction: 1 SQL operation.
                Transactions per client: positive multiple of 10; Phase-1 task uses 50.
                Semantic authority: Phase-0A DelosSqlSemanticOracle plus per-read validity checks.
                Performance substrate: DelosJdbcCrossEngineConcurrency; no second runner is introduced.
                F11 80R20W executes four deterministic stable point reads then one disjoint indexed update
                per five-transaction cycle. Each client owns a distinct writer key and a separate stable read key.
                F11 50R50W-HOT alternates point reads and indexed updates over a four-key hot set. The observed
                read quantity is scheduling-dependent, so validity is bounded by the SQL baseline/final state and
                is deliberately excluded from the cross-engine legacy fingerprint. Final committed increments are
                exact and therefore detect lost updates. Retryable rollback accounting remains visible.
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
