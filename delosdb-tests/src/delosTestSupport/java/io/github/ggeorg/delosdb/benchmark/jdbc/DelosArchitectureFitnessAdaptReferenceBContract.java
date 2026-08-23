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

/** Fast contract for the realistic-transaction ADAPT_REFERENCE subtranche. */
public final class DelosArchitectureFitnessAdaptReferenceBContract {
    private static final String PREFIX = "delosdb.benchmark.fitnessAdaptReferenceB.";

    private DelosArchitectureFitnessAdaptReferenceBContract() {
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
            if (Integer.parseInt(fields[1]) != 13) {
                throw new IllegalStateException("Family mismatch for " + spec.caseId());
            }
            if (!"MULTI_STATEMENT_TRANSACTION".equals(fields[8])) {
                throw new IllegalStateException(
                        "Protocol mismatch for " + spec.caseId() + ": " + fields[8]);
            }
            if (!fields[9].contains(spec.referenceToken())) {
                throw new IllegalStateException(
                        "Reference mapping drift for " + spec.caseId() + ": " + fields[9]);
            }
        }

        StringBuilder plan = new StringBuilder(
                "caseId\tfamilyId\tworkload\toperationsPerTransaction\tclients\trows\tlanes\treference\n");
        for (CaseSpec spec : cases()) {
            plan.append(spec.caseId()).append('\t')
                    .append("13\t")
                    .append(spec.workload()).append('\t')
                    .append("1\t1,8\t10000\tEMBEDDED,SERVER\t")
                    .append(spec.referenceToken()).append('\n');
        }
        Files.writeString(
                reportDirectory.resolve("architecture-fitness-adapt-reference-b-plan.tsv"),
                plan.toString(), StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("architecture-fitness-adapt-reference-b-contract.txt"),
                contractText(), StandardCharsets.UTF_8);
        System.out.println("DelosDB Phase-1 ADAPT_REFERENCE-B contract passed: " + reportDirectory);
    }

    private static List<CaseSpec> cases() {
        return List.of(
                new CaseSpec(
                        "F13-BANK-TRANSACTION", "BANK_TRANSACTION", "BankTransactionClient"),
                new CaseSpec(
                        "F13-ORDER-ENTRY-MIX", "ORDER_ENTRY_MIX", "Submitter"));
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
                DelosDB Phase-1 ADAPT_REFERENCE-B architecture-fitness contract
                ===============================================================

                Frozen ADAPT_REFERENCE cases: 6.
                ADAPT_REFERENCE-A already wires F04 x1, F06-high x1 and F08 x2.
                This subtranche adapts the final F13 x2 realistic multi-table transaction sentinels.
                Lanes: embedded and server.
                Rows: 10000 stock/account rows.
                Clients: 1 and 8.
                Width: 1 logical business transaction per benchmark operation.
                Semantic authority: Phase-0A DelosSqlSemanticOracle plus explicit committed-state invariants.
                Performance substrate: DelosJdbcCrossEngineConcurrency; Derby reference workload semantics are
                adapted while legacy random/control/measurement machinery is not reused.
                BANK_TRANSACTION preserves the TPC-B-like shape: account update, history insert, teller update,
                branch update, account read and commit, using a deterministic transaction input stream.
                ORDER_ENTRY_MIX uses a deterministic compact mix of New Order, Payment, Order Status, Delivery,
                Stock Level and intentional New Order rollback over dedicated multi-table fixtures.
                Transaction-local intermediate reads are diagnostics, not the SQL correctness oracle; committed
                database state and invariants define semantic truth.
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

    private record CaseSpec(String caseId, String workload, String referenceToken) {
    }
}
