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

/** Fast contract for the relational BUILD_NEW subtranche. */
public final class DelosArchitectureFitnessBuildNewAContract {
    private static final String PREFIX = "delosdb.benchmark.fitnessBuildNewA.";

    private DelosArchitectureFitnessBuildNewAContract() {
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
            if (Integer.parseInt(fields[1]) != spec.familyId()) {
                throw new IllegalStateException("Family mismatch for " + spec.caseId());
            }
            if (!"RESULT_FETCH".equals(fields[8])) {
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
                    .append("1\t1,8\t10000\tEMBEDDED,SERVER\n");
        }
        Files.writeString(
                reportDirectory.resolve("architecture-fitness-build-new-a-plan.tsv"),
                plan.toString(), StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("architecture-fitness-build-new-a-contract.txt"),
                contractText(), StandardCharsets.UTF_8);
        System.out.println("DelosDB Phase-1 BUILD_NEW-A contract passed: " + reportDirectory);
    }

    private static List<CaseSpec> cases() {
        return List.of(
                new CaseSpec("F04-JOIN-INDEXED-FANOUT", 4, "JOIN_INDEXED_FANOUT"),
                new CaseSpec("F05-JOIN-3WAY-SELECTIVE", 5, "JOIN_3WAY_SELECTIVE"),
                new CaseSpec("F05-JOIN-4WAY-FANOUT", 5, "JOIN_4WAY_FANOUT"));
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
                DelosDB Phase-1 BUILD_NEW-A architecture-fitness contract
                ===========================================================

                Frozen BUILD_NEW cases: 5.
                This subtranche builds only the 3 missing relational join sentinels: F04 fanout and F05 x2.
                F11 x2 mixed reader/writer concurrency remains the immediately following BUILD_NEW-B subtranche.
                Lanes: embedded and server.
                Rows: 10000 core fixture rows.
                Clients: 1 and 8.
                Semantic authority: Phase-0A DelosSqlSemanticOracle.
                Performance substrate: DelosJdbcCrossEngineConcurrency; no second benchmark runner is introduced.
                F04 fanout uses 1000 parent rows, 10 indexed children/parent and a selective 100-parent range,
                producing exactly 1000 ordered pairs.
                F05 3-way uses 1000 customers, 4 orders/customer and 3 lines/order; a selective 100-customer
                range produces exactly 1200 tuples and is validated as an unordered SQL-visible result.
                F05 4-way extends the same fixture with item primary keys and a 10-bucket customer predicate;
                bucket 7 produces exactly 1200 tuples and is independent of physical join order.
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

    private record CaseSpec(String caseId, int familyId, String workload) {
    }
}
