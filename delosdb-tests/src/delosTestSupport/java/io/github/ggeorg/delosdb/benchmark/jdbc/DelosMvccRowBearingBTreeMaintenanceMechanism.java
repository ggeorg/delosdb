/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.derby.impl.store.access.mvcc.MvccCurrentRowTopologyTestSupport;
import org.apache.derby.impl.store.access.mvcc.MvccCurrentRowTopologyTestSupport.Algorithm;
import org.apache.derby.impl.store.access.mvcc.MvccRowBearingBTreeMechanismTestSupport;
import org.apache.derby.impl.store.access.mvcc.MvccRowBearingBTreeMechanismTestSupport.Entry;
import org.apache.derby.impl.store.access.mvcc.MvccRowBearingBTreeMechanismTestSupport.MaintenanceAlgorithm;
import org.apache.derby.impl.store.access.mvcc.MvccRowBearingBTreeMechanismTestSupport.MutationCounts;
import org.apache.derby.impl.store.access.mvcc.MvccRowBearingBTreeMechanismTestSupport.Tree;
import org.apache.derby.impl.store.access.mvcc.MvccRowBearingBTreeMechanismTestSupport.TreeSpace;

/**
 * Adversarial write-maintenance proof for the row-bearing MVCC architecture.
 *
 * <p>Two test-only persistent RawStore B-trees start from the same committed current rows.
 * The production delos_mvcc mutation is timed first. The resulting committed MVCC metadata
 * is then mirrored into each alternative tree and that physical B-tree maintenance plus
 * commit is timed separately. The mirror cost is therefore not a production prediction;
 * it isolates the additional tree work while preserving the real production history store
 * as the semantic authority.</p>
 */
public final class DelosMvccRowBearingBTreeMaintenanceMechanism {
    private static final String TABLE_PREFIX = "MVCC_C_MAINT_";
    private static volatile long blackhole;

    private DelosMvccRowBearingBTreeMaintenanceMechanism() {
    }

    public static void main(String[] args) throws Exception {
        Path database = Path.of(property(
                "database", "build/tmp/delos-mvcc-row-bearing-btree-maintenance"));
        Path reportDirectory = Path.of(property(
                "reportDirectory",
                "build/reports/delosdb/benchmarks/mvcc-row-bearing-btree-maintenance"));
        boolean validationOnly = Boolean.parseBoolean(property("validationOnly", "false"));
        int rows = intProperty("rows", validationOnly ? 2000 : 10000);
        int mutationRows = intProperty("mutationRows", validationOnly ? 200 : 1000);
        int runs = intProperty("runs", validationOnly ? 1 : 4);
        int warmups = intProperty("warmups", validationOnly ? 0 : 1);
        int readIterations = intProperty("readIterations", validationOnly ? 1 : 2);
        int readTargetRows = intProperty("readTargetRows", validationOnly ? 1000 : 250000);
        if (rows < 2 * mutationRows || mutationRows <= 0) {
            throw new IllegalArgumentException(
                    "rows must be at least 2*mutationRows; rows=" + rows
                            + " mutationRows=" + mutationRows);
        }

        DelosBenchmarkSupport.prepareOutput(database, reportDirectory);
        Results results = DelosBenchmarkSupport.withFreshEmbeddedDatabase(
                database,
                connection -> run(
                        connection,
                        database,
                        rows,
                        mutationRows,
                        runs,
                        warmups,
                        readIterations,
                        readTargetRows));
        writeMutationRuns(reportDirectory, results.mutations());
        writeMutationComparison(reportDirectory, results.mutations());
        writeReadRuns(reportDirectory, results.reads());
        writeReadComparison(reportDirectory, results.reads());
        writeValidation(reportDirectory, results.validations(), validationOnly);
        writeDecision(reportDirectory, results, validationOnly);
        System.out.println(
                "MVCC row-bearing B-tree maintenance mechanism complete: " + reportDirectory);
    }

    private static Results run(
            Connection connection,
            Path database,
            int rows,
            int mutationRows,
            int runs,
            int warmups,
            int readIterations,
            int readTargetRows) throws Exception {
        connection.setAutoCommit(false);
        List<MutationRun> mutationRuns = new ArrayList<>();
        List<ReadRun> readRuns = new ArrayList<>();
        List<String> validations = new ArrayList<>();

        int scenarioOrdinal = 0;
        for (MutationScenario scenario : MutationScenario.values()) {
            for (int run = 1; run <= runs; run++) {
                String table = tableName(scenario, run);
                prepareFixture(connection, table, rows);
                try (MvccRowBearingBTreeMechanismTestSupport.Session rowBearing =
                             MvccRowBearingBTreeMechanismTestSupport.openSession(
                                     connection, table);
                     MvccCurrentRowTopologyTestSupport.Session existing =
                             MvccCurrentRowTopologyTestSupport.openSession(connection, table)) {
                    List<Entry> before = rowBearing.captureLiveEntries();
                    long historicalSnapshot = rowBearing.captureCommittedSequence();
                    Tree replace = rowBearing.createTree(
                            MaintenanceAlgorithm.REPLACE_CURRENT, before);
                    Tree append = rowBearing.createTree(
                            MaintenanceAlgorithm.APPEND_INTERVAL, before);
                    connection.commit();
                    TreeSpace replaceBefore = rowBearing.space(replace);
                    TreeSpace appendBefore = rowBearing.space(append);

                    ProductionResult production = measureProductionMutation(
                            connection,
                            database,
                            rowBearing,
                            table,
                            scenario,
                            rows,
                            mutationRows,
                            historicalSnapshot);
                    MutationMaterial material = captureMutationMaterial(
                            rowBearing,
                            scenario,
                            before,
                            rows,
                            mutationRows,
                            production);

                    TreeMutationResult replaceResult;
                    TreeMutationResult appendResult;
                    boolean appendFirst = Math.floorMod(run + scenarioOrdinal, 2) == 0;
                    if (appendFirst) {
                        appendResult = measureTreeMutation(
                                connection,
                                database,
                                rowBearing,
                                append,
                                scenario,
                                material,
                                mutationRows,
                                appendBefore);
                        replaceResult = measureTreeMutation(
                                connection,
                                database,
                                rowBearing,
                                replace,
                                scenario,
                                material,
                                mutationRows,
                                replaceBefore);
                    } else {
                        replaceResult = measureTreeMutation(
                                connection,
                                database,
                                rowBearing,
                                replace,
                                scenario,
                                material,
                                mutationRows,
                                replaceBefore);
                        appendResult = measureTreeMutation(
                                connection,
                                database,
                                rowBearing,
                                append,
                                scenario,
                                material,
                                mutationRows,
                                appendBefore);
                    }

                    mutationRuns.add(new MutationRun(
                            scenario.name(),
                            run,
                            mutationRows,
                            production.elapsedNanos(),
                            production.commitCount(),
                            production.logBytesDelta(),
                            MaintenanceAlgorithm.REPLACE_CURRENT.name(),
                            replaceResult.elapsedNanos(),
                            replaceResult.commitCount(),
                            replaceResult.logBytesDelta(),
                            replaceResult.counts().inserts(),
                            replaceResult.counts().deletes(),
                            replaceResult.counts().retiredAnchors(),
                            replaceBefore.allocatedPages(),
                            replaceResult.afterSpace().allocatedPages(),
                            replaceResult.afterSpace().unfilledPages()));
                    mutationRuns.add(new MutationRun(
                            scenario.name(),
                            run,
                            mutationRows,
                            production.elapsedNanos(),
                            production.commitCount(),
                            production.logBytesDelta(),
                            MaintenanceAlgorithm.APPEND_INTERVAL.name(),
                            appendResult.elapsedNanos(),
                            appendResult.commitCount(),
                            appendResult.logBytesDelta(),
                            appendResult.counts().inserts(),
                            appendResult.counts().deletes(),
                            appendResult.counts().retiredAnchors(),
                            appendBefore.allocatedPages(),
                            appendResult.afterSpace().allocatedPages(),
                            appendResult.afterSpace().unfilledPages()));

                    validateScenario(
                            existing,
                            rowBearing,
                            replace,
                            append,
                            scenario,
                            historicalSnapshot,
                            production,
                            rows,
                            mutationRows,
                            validations);

                    if (scenario == MutationScenario.UPDATE_PAYLOAD) {
                        readRuns.addAll(measureReadGate(
                                existing,
                                rowBearing,
                                replace,
                                append,
                                run,
                                historicalSnapshot,
                                production.currentSnapshot(),
                                mutationRows,
                                warmups,
                                readIterations,
                                readTargetRows,
                                scenarioOrdinal));
                    }

                    rowBearing.dropTree(replace);
                    rowBearing.dropTree(append);
                    connection.commit();
                } finally {
                    dropFixture(connection, table);
                }
            }
            scenarioOrdinal++;
        }
        connection.rollback();
        return new Results(
                List.copyOf(mutationRuns),
                List.copyOf(readRuns),
                List.copyOf(validations));
    }

    private static ProductionResult measureProductionMutation(
            Connection connection,
            Path database,
            MvccRowBearingBTreeMechanismTestSupport.Session session,
            String table,
            MutationScenario scenario,
            int rows,
            int mutationRows,
            long historicalSnapshot) throws Exception {
        long logBefore = logBytes(database);
        long start = System.nanoTime();
        long deletedSnapshot = 0L;
        int commitCount;
        switch (scenario) {
            case INSERT -> {
                insertRange(connection, table, rows * 2 + 1, mutationRows, 5);
                connection.commit();
                commitCount = 1;
            }
            case UPDATE_PAYLOAD -> {
                try (PreparedStatement update = connection.prepareStatement(
                        "update " + table
                                + " set quantity = quantity + 1 where id >= 1 and id < ?")) {
                    update.setInt(1, mutationRows + 1);
                    requireChanged(update.executeUpdate(), mutationRows, scenario);
                }
                connection.commit();
                commitCount = 1;
            }
            case UPDATE_KEY -> {
                try (PreparedStatement update = connection.prepareStatement(
                        "update " + table
                                + " set id = id + ? where id >= 1 and id < ?")) {
                    update.setInt(1, rows * 2);
                    update.setInt(2, mutationRows + 1);
                    requireChanged(update.executeUpdate(), mutationRows, scenario);
                }
                connection.commit();
                commitCount = 1;
            }
            case DELETE -> {
                deleteRange(connection, table, mutationRows, scenario);
                connection.commit();
                commitCount = 1;
            }
            case DELETE_REINSERT -> {
                deleteRange(connection, table, mutationRows, scenario);
                connection.commit();
                deletedSnapshot = session.captureCommittedSequence();
                insertRange(connection, table, 1, mutationRows, 7);
                connection.commit();
                commitCount = 2;
            }
            default -> throw new IllegalStateException("Unhandled scenario " + scenario);
        }
        long elapsed = System.nanoTime() - start;
        long currentSnapshot = session.captureCommittedSequence();
        if (currentSnapshot <= historicalSnapshot) {
            throw new IllegalStateException(
                    scenario + " did not advance the committed snapshot");
        }
        if (scenario == MutationScenario.DELETE_REINSERT
                && (deletedSnapshot <= historicalSnapshot
                || currentSnapshot <= deletedSnapshot)) {
            throw new IllegalStateException(
                    "delete/reinsert snapshot ordering is invalid: historical="
                            + historicalSnapshot + " deleted=" + deletedSnapshot
                            + " current=" + currentSnapshot);
        }
        return new ProductionResult(
                elapsed,
                commitCount,
                nonNegativeDelta(logBefore, logBytes(database)),
                currentSnapshot,
                deletedSnapshot);
    }

    private static MutationMaterial captureMutationMaterial(
            MvccRowBearingBTreeMechanismTestSupport.Session session,
            MutationScenario scenario,
            List<Entry> before,
            int rows,
            int mutationRows,
            ProductionResult production) throws Exception {
        List<Entry> affectedBefore = before.stream()
                .filter(entry -> entry.key() >= 1 && entry.key() <= mutationRows)
                .toList();
        if (affectedBefore.size() != mutationRows) {
            throw new IllegalStateException(
                    "Expected " + mutationRows + " affected pre-mutation rows, found "
                            + affectedBefore.size());
        }
        return switch (scenario) {
            case INSERT -> new MutationMaterial(
                    affectedBefore,
                    session.captureLiveEntries(
                            rows * 2 + 1, rows * 2 + mutationRows + 1),
                    List.of(),
                    List.of());
            case UPDATE_PAYLOAD -> new MutationMaterial(
                    affectedBefore,
                    orderedByBeforeRowId(
                            affectedBefore,
                            session.captureLiveEntriesByRowId(rowIds(affectedBefore))),
                    List.of(),
                    List.of());
            case UPDATE_KEY -> new MutationMaterial(
                    affectedBefore,
                    orderedByBeforeRowId(
                            affectedBefore,
                            session.captureLiveEntriesByRowId(rowIds(affectedBefore))),
                    List.of(),
                    List.of());
            case DELETE -> new MutationMaterial(
                    affectedBefore,
                    List.of(),
                    tombstones(session, affectedBefore),
                    List.of());
            case DELETE_REINSERT -> new MutationMaterial(
                    affectedBefore,
                    List.of(),
                    tombstones(session, affectedBefore),
                    session.captureLiveEntries(1, mutationRows + 1));
        };
    }

    private static TreeMutationResult measureTreeMutation(
            Connection connection,
            Path database,
            MvccRowBearingBTreeMechanismTestSupport.Session session,
            Tree tree,
            MutationScenario scenario,
            MutationMaterial material,
            int mutationRows,
            TreeSpace beforeSpace) throws Exception {
        long logBefore = logBytes(database);
        long start = System.nanoTime();
        MutationCounts counts = new MutationCounts(0L, 0L, 0L);
        int commitCount = 1;
        switch (scenario) {
            case INSERT -> {
                requireSize(material.after(), mutationRows, "insert after rows");
                for (Entry entry : material.after()) {
                    counts = counts.plus(session.insert(tree, entry));
                }
                connection.commit();
            }
            case UPDATE_PAYLOAD -> {
                requireSize(material.after(), mutationRows, "payload-update after rows");
                for (int index = 0; index < mutationRows; index++) {
                    counts = counts.plus(session.updatePayload(
                            tree,
                            material.before().get(index),
                            material.after().get(index)));
                }
                connection.commit();
            }
            case UPDATE_KEY -> {
                requireSize(material.after(), mutationRows, "key-update after rows");
                for (int index = 0; index < mutationRows; index++) {
                    counts = counts.plus(session.updateKey(
                            tree,
                            material.before().get(index),
                            material.after().get(index),
                            material.after().get(index).beginSequence()));
                }
                connection.commit();
            }
            case DELETE -> {
                requireSize(material.tombstones(), mutationRows, "delete tombstones");
                for (int index = 0; index < mutationRows; index++) {
                    counts = counts.plus(session.delete(
                            tree,
                            material.before().get(index),
                            material.tombstones().get(index)));
                }
                connection.commit();
            }
            case DELETE_REINSERT -> {
                requireSize(material.tombstones(), mutationRows, "delete/reinsert tombstones");
                requireSize(material.reinserted(), mutationRows, "delete/reinsert live rows");
                for (int index = 0; index < mutationRows; index++) {
                    counts = counts.plus(session.delete(
                            tree,
                            material.before().get(index),
                            material.tombstones().get(index)));
                }
                connection.commit();
                for (Entry entry : material.reinserted()) {
                    counts = counts.plus(session.insert(tree, entry));
                }
                connection.commit();
                commitCount = 2;
            }
            default -> throw new IllegalStateException("Unhandled scenario " + scenario);
        }
        long elapsed = System.nanoTime() - start;
        TreeSpace afterSpace = session.space(tree);
        if (afterSpace.pageSize() != beforeSpace.pageSize()) {
            throw new IllegalStateException("B-tree page size changed during mechanism run");
        }
        return new TreeMutationResult(
                elapsed,
                commitCount,
                nonNegativeDelta(logBefore, logBytes(database)),
                counts,
                afterSpace);
    }

    private static void validateScenario(
            MvccCurrentRowTopologyTestSupport.Session existing,
            MvccRowBearingBTreeMechanismTestSupport.Session rowBearing,
            Tree replace,
            Tree append,
            MutationScenario scenario,
            long historicalSnapshot,
            ProductionResult production,
            int rows,
            int mutationRows,
            List<String> validations) throws Exception {
        List<ValidationCase> cases = switch (scenario) {
            case INSERT -> List.of(
                    new ValidationCase(
                            "INSERT_HISTORICAL_NEW_RANGE",
                            rows * 2 + 1,
                            rows * 2 + mutationRows + 1,
                            historicalSnapshot),
                    new ValidationCase(
                            "INSERT_CURRENT_NEW_RANGE",
                            rows * 2 + 1,
                            rows * 2 + mutationRows + 1,
                            production.currentSnapshot()));
            case UPDATE_PAYLOAD -> List.of(
                    new ValidationCase(
                            "UPDATE_PAYLOAD_HISTORICAL",
                            1,
                            mutationRows + 1,
                            historicalSnapshot),
                    new ValidationCase(
                            "UPDATE_PAYLOAD_CURRENT",
                            1,
                            mutationRows + 1,
                            production.currentSnapshot()));
            case UPDATE_KEY -> List.of(
                    new ValidationCase(
                            "UPDATE_KEY_HISTORICAL_OLD_RANGE",
                            1,
                            mutationRows + 1,
                            historicalSnapshot),
                    new ValidationCase(
                            "UPDATE_KEY_CURRENT_OLD_RANGE",
                            1,
                            mutationRows + 1,
                            production.currentSnapshot()),
                    new ValidationCase(
                            "UPDATE_KEY_HISTORICAL_NEW_RANGE",
                            rows * 2 + 1,
                            rows * 2 + mutationRows + 1,
                            historicalSnapshot),
                    new ValidationCase(
                            "UPDATE_KEY_CURRENT_NEW_RANGE",
                            rows * 2 + 1,
                            rows * 2 + mutationRows + 1,
                            production.currentSnapshot()));
            case DELETE -> List.of(
                    new ValidationCase(
                            "DELETE_HISTORICAL",
                            1,
                            mutationRows + 1,
                            historicalSnapshot),
                    new ValidationCase(
                            "DELETE_CURRENT",
                            1,
                            mutationRows + 1,
                            production.currentSnapshot()));
            case DELETE_REINSERT -> List.of(
                    new ValidationCase(
                            "DELETE_REINSERT_HISTORICAL",
                            1,
                            mutationRows + 1,
                            historicalSnapshot),
                    new ValidationCase(
                            "DELETE_REINSERT_DELETED_SNAPSHOT",
                            1,
                            mutationRows + 1,
                            production.deletedSnapshot()),
                    new ValidationCase(
                            "DELETE_REINSERT_CURRENT",
                            1,
                            mutationRows + 1,
                            production.currentSnapshot()));
        };
        for (ValidationCase validation : cases) {
            MvccCurrentRowTopologyTestSupport.Measurement expected = existing.measure(
                    null,
                    Algorithm.EXISTING,
                    validation.start(),
                    validation.endExclusive(),
                    validation.snapshot(),
                    true);
            for (Tree tree : List.of(replace, append)) {
                MvccRowBearingBTreeMechanismTestSupport.Measurement actual = rowBearing.measure(
                        tree,
                        validation.start(),
                        validation.endExclusive(),
                        validation.snapshot(),
                        true);
                if (actual.rows() != expected.rows()
                        || actual.fingerprint() != expected.fingerprint()) {
                    throw new IllegalStateException(
                            validation.name() + " " + tree.algorithm()
                                    + " differs from production: expectedRows=" + expected.rows()
                                    + " actualRows=" + actual.rows()
                                    + " expectedFingerprint="
                                    + Long.toUnsignedString(expected.fingerprint())
                                    + " actualFingerprint="
                                    + Long.toUnsignedString(actual.fingerprint()));
                }
                validations.add(validation.name() + "," + tree.algorithm()
                        + ",rows=" + actual.rows()
                        + ",fingerprint=" + Long.toUnsignedString(actual.fingerprint())
                        + ",PASS");
            }
        }
    }

    private static List<ReadRun> measureReadGate(
            MvccCurrentRowTopologyTestSupport.Session existing,
            MvccRowBearingBTreeMechanismTestSupport.Session rowBearing,
            Tree replace,
            Tree append,
            int run,
            long historicalSnapshot,
            long currentSnapshot,
            int mutationRows,
            int warmups,
            int iterations,
            int targetRows,
            int orderSeed) throws Exception {
        int probe = Math.max(1, mutationRows / 2);
        List<ReadScenario> scenarios = List.of(
                new ReadScenario(
                        "CURRENT_POINT",
                        probe,
                        probe + 1,
                        currentSnapshot,
                        1),
                new ReadScenario(
                        "CURRENT_RANGE_" + mutationRows,
                        1,
                        mutationRows + 1,
                        currentSnapshot,
                        mutationRows),
                new ReadScenario(
                        "HISTORICAL_RANGE_" + mutationRows,
                        1,
                        mutationRows + 1,
                        historicalSnapshot,
                        mutationRows));
        List<ReadRun> result = new ArrayList<>();
        int scenarioIndex = 0;
        for (ReadScenario scenario : scenarios) {
            ReadArm[] order = rotatedReadArms(run + scenarioIndex++ + orderSeed);
            for (ReadArm arm : order) {
                for (int warmup = 0; warmup < warmups; warmup++) {
                    ReadMeasurement measurement = measureReadOnce(
                            existing, rowBearing, replace, append, arm, scenario);
                    blackhole ^= measurement.fingerprint();
                }
                int scans = Math.max(
                        1,
                        (targetRows + scenario.expectedRows() - 1) / scenario.expectedRows());
                long elapsed = 0L;
                long returned = 0L;
                long localVisible = 0L;
                long historyFallbacks = 0L;
                long versionReads = 0L;
                long entriesVisited = 0L;
                long fingerprint = 0L;
                for (int iteration = 0; iteration < iterations; iteration++) {
                    long start = System.nanoTime();
                    for (int scan = 0; scan < scans; scan++) {
                        ReadMeasurement measurement = measureReadOnce(
                                existing, rowBearing, replace, append, arm, scenario);
                        returned += measurement.rows();
                        localVisible += measurement.localVisible();
                        historyFallbacks += measurement.historyFallbacks();
                        versionReads += measurement.versionSlotFetches();
                        entriesVisited += measurement.entriesVisited();
                        fingerprint ^= measurement.fingerprint();
                    }
                    elapsed += System.nanoTime() - start;
                }
                blackhole ^= fingerprint;
                double seconds = elapsed / 1_000_000_000.0d;
                result.add(new ReadRun(
                        scenario.name(),
                        arm.name(),
                        run,
                        returned / seconds,
                        elapsed / (double) Math.max(1L, returned),
                        perRow(localVisible, returned),
                        perRow(historyFallbacks, returned),
                        perRow(versionReads, returned),
                        perRow(entriesVisited, returned),
                        fingerprint));
            }
        }
        return result;
    }

    private static ReadMeasurement measureReadOnce(
            MvccCurrentRowTopologyTestSupport.Session existing,
            MvccRowBearingBTreeMechanismTestSupport.Session rowBearing,
            Tree replace,
            Tree append,
            ReadArm arm,
            ReadScenario scenario) throws Exception {
        if (arm == ReadArm.EXISTING) {
            MvccCurrentRowTopologyTestSupport.Measurement measurement = existing.measure(
                    null,
                    Algorithm.EXISTING,
                    scenario.start(),
                    scenario.endExclusive(),
                    scenario.snapshot(),
                    true);
            return new ReadMeasurement(
                    measurement.rows(),
                    measurement.fingerprint(),
                    measurement.localVisible(),
                    measurement.historyFallbacks(),
                    measurement.versionSlotFetches(),
                    measurement.candidateCount());
        }
        Tree tree = arm == ReadArm.REPLACE_CURRENT ? replace : append;
        MvccRowBearingBTreeMechanismTestSupport.Measurement measurement = rowBearing.measure(
                tree,
                scenario.start(),
                scenario.endExclusive(),
                scenario.snapshot(),
                true);
        return new ReadMeasurement(
                measurement.rows(),
                measurement.fingerprint(),
                measurement.localVisible(),
                measurement.historyFallbacks(),
                measurement.versionSlotFetches(),
                measurement.entriesVisited());
    }

    private static void prepareFixture(Connection connection, String table, int rows)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + table
                    + " (id int not null primary key, quantity int not null) using delos_mvcc");
        }
        connection.commit();
        insertRange(connection, table, 1, rows, 3);
        connection.commit();
    }

    private static void dropFixture(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("drop table " + table);
        }
        connection.commit();
    }

    private static void insertRange(
            Connection connection,
            String table,
            int startKey,
            int count,
            int multiplier) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + table + " (id, quantity) values (?, ?)")) {
            for (int offset = 0; offset < count; offset++) {
                int key = startKey + offset;
                insert.setInt(1, key);
                insert.setInt(2, key * multiplier);
                insert.addBatch();
                if ((offset + 1) % 100 == 0) {
                    insert.executeBatch();
                }
            }
            insert.executeBatch();
        }
    }

    private static void deleteRange(
            Connection connection,
            String table,
            int mutationRows,
            MutationScenario scenario) throws Exception {
        try (PreparedStatement delete = connection.prepareStatement(
                "delete from " + table + " where id >= 1 and id < ?")) {
            delete.setInt(1, mutationRows + 1);
            requireChanged(delete.executeUpdate(), mutationRows, scenario);
        }
    }

    private static List<Entry> tombstones(
            MvccRowBearingBTreeMechanismTestSupport.Session session,
            List<Entry> before) throws Exception {
        List<Entry> tombstones = new ArrayList<>(before.size());
        for (Entry entry : before) {
            tombstones.add(session.captureTombstone(entry));
        }
        return List.copyOf(tombstones);
    }

    private static Set<Long> rowIds(List<Entry> entries) {
        Set<Long> rowIds = new LinkedHashSet<>();
        for (Entry entry : entries) {
            rowIds.add(entry.rowId());
        }
        return Set.copyOf(rowIds);
    }

    private static List<Entry> orderedByBeforeRowId(
            List<Entry> before,
            Map<Long, Entry> afterByRowId) {
        List<Entry> ordered = new ArrayList<>(before.size());
        for (Entry entry : before) {
            Entry after = afterByRowId.get(entry.rowId());
            if (after == null) {
                throw new IllegalStateException(
                        "Post-mutation row is absent for logical row " + entry.rowId());
            }
            ordered.add(after);
        }
        return List.copyOf(ordered);
    }

    private static void requireChanged(
            int actual,
            int expected,
            MutationScenario scenario) {
        if (actual != expected) {
            throw new IllegalStateException(
                    scenario + " expected " + expected + " changed rows, got " + actual);
        }
    }

    private static void requireSize(List<?> values, int expected, String label) {
        if (values.size() != expected) {
            throw new IllegalStateException(
                    label + " expected " + expected + " rows, got " + values.size());
        }
    }

    private static long logBytes(Path database) throws Exception {
        Path log = database.resolve("log");
        if (!Files.exists(log)) {
            return 0L;
        }
        try (var paths = Files.walk(log)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (Exception failure) {
                            throw new IllegalStateException(
                                    "Could not read log file size: " + path, failure);
                        }
                    })
                    .sum();
        }
    }

    private static long nonNegativeDelta(long before, long after) {
        return Math.max(0L, after - before);
    }

    private static double perRow(long value, long rows) {
        return rows == 0L ? 0.0d : value / (double) rows;
    }

    private static String tableName(MutationScenario scenario, int run) {
        return TABLE_PREFIX + scenario.name() + "_" + run;
    }

    private static ReadArm[] rotatedReadArms(int seed) {
        ReadArm[] arms = ReadArm.values().clone();
        int offset = Math.floorMod(seed - 1, arms.length);
        ReadArm[] rotated = new ReadArm[arms.length];
        for (int index = 0; index < arms.length; index++) {
            rotated[index] = arms[(index + offset) % arms.length];
        }
        return rotated;
    }

    private static void writeMutationRuns(Path reportDirectory, List<MutationRun> results)
            throws Exception {
        StringBuilder csv = new StringBuilder();
        csv.append("scenario,run,logicalRows,productionElapsedNanos,productionCommits,")
                .append("productionLogBytesDelta,algorithm,treeElapsedNanos,treeCommits,")
                .append("treeLogBytesDelta,treeInserts,treeDeletes,retiredAnchors,")
                .append("pagesBefore,pagesAfter,unfilledPagesAfter,productionLogicalRowsPerSecond,")
                .append("treeLogicalRowsPerSecond,treeNsPerLogicalRow,treeToProductionTimeRatio,")
                .append("conservativeAdditiveUpperBound\n");
        for (MutationRun row : results) {
            double productionRate = rate(row.logicalRows(), row.productionElapsedNanos());
            double treeRate = rate(row.logicalRows(), row.treeElapsedNanos());
            double ratio = row.treeElapsedNanos() / (double) row.productionElapsedNanos();
            csv.append(row.scenario()).append(',')
                    .append(row.run()).append(',')
                    .append(row.logicalRows()).append(',')
                    .append(row.productionElapsedNanos()).append(',')
                    .append(row.productionCommits()).append(',')
                    .append(row.productionLogBytesDelta()).append(',')
                    .append(row.algorithm()).append(',')
                    .append(row.treeElapsedNanos()).append(',')
                    .append(row.treeCommits()).append(',')
                    .append(row.treeLogBytesDelta()).append(',')
                    .append(row.treeInserts()).append(',')
                    .append(row.treeDeletes()).append(',')
                    .append(row.retiredAnchors()).append(',')
                    .append(row.pagesBefore()).append(',')
                    .append(row.pagesAfter()).append(',')
                    .append(row.unfilledPagesAfter()).append(',')
                    .append(format(productionRate)).append(',')
                    .append(format(treeRate)).append(',')
                    .append(format(row.treeElapsedNanos() / (double) row.logicalRows())).append(',')
                    .append(format(ratio)).append(',')
                    .append(format(1.0d + ratio)).append('\n');
        }
        DelosBenchmarkSupport.writeUtf8(
                reportDirectory.resolve("mvcc-row-bearing-btree-mutation-runs.csv"),
                csv.toString());
    }

    private static void writeMutationComparison(
            Path reportDirectory,
            List<MutationRun> results) throws Exception {
        StringBuilder csv = new StringBuilder();
        csv.append("scenario,algorithm,medianTreeLogicalRowsPerSecond,iqrToMedian,madToMedian,")
                .append("medianTreeToProductionTimeRatio,medianConservativeAdditiveUpperBound,")
                .append("medianPageDelta,medianTreeInsertsPerLogicalRow,")
                .append("medianTreeDeletesPerLogicalRow,medianRetiredAnchorsPerLogicalRow\n");
        for (MutationScenario scenario : MutationScenario.values()) {
            for (MaintenanceAlgorithm algorithm : MaintenanceAlgorithm.values()) {
                List<MutationRun> matching = results.stream()
                        .filter(row -> row.scenario().equals(scenario.name()))
                        .filter(row -> row.algorithm().equals(algorithm.name()))
                        .toList();
                if (matching.isEmpty()) {
                    continue;
                }
                double[] rates = matching.stream()
                        .mapToDouble(row -> rate(row.logicalRows(), row.treeElapsedNanos()))
                        .toArray();
                double[] ratios = matching.stream()
                        .mapToDouble(row -> row.treeElapsedNanos()
                                / (double) row.productionElapsedNanos())
                        .toArray();
                double[] pageDelta = matching.stream()
                        .mapToDouble(row -> row.pagesAfter() - row.pagesBefore())
                        .toArray();
                double[] inserts = matching.stream()
                        .mapToDouble(row -> row.treeInserts() / (double) row.logicalRows())
                        .toArray();
                double[] deletes = matching.stream()
                        .mapToDouble(row -> row.treeDeletes() / (double) row.logicalRows())
                        .toArray();
                double[] retired = matching.stream()
                        .mapToDouble(row -> row.retiredAnchors() / (double) row.logicalRows())
                        .toArray();
                double medianRate = median(rates);
                csv.append(scenario.name()).append(',')
                        .append(algorithm.name()).append(',')
                        .append(format(medianRate)).append(',')
                        .append(format(iqrToMedian(rates))).append(',')
                        .append(format(madToMedian(rates))).append(',')
                        .append(format(median(ratios))).append(',')
                        .append(format(1.0d + median(ratios))).append(',')
                        .append(format(median(pageDelta))).append(',')
                        .append(format(median(inserts))).append(',')
                        .append(format(median(deletes))).append(',')
                        .append(format(median(retired))).append('\n');
            }
        }
        DelosBenchmarkSupport.writeUtf8(
                reportDirectory.resolve("mvcc-row-bearing-btree-mutation-comparison.csv"),
                csv.toString());
    }

    private static void writeReadRuns(Path reportDirectory, List<ReadRun> results)
            throws Exception {
        StringBuilder csv = new StringBuilder();
        csv.append("scenario,arm,run,rowsPerSecond,nsPerRow,localVisiblePerRow,")
                .append("historyFallbacksPerRow,versionSlotFetchesPerRow,entriesVisitedPerRow,")
                .append("fingerprint\n");
        for (ReadRun row : results) {
            csv.append(row.scenario()).append(',')
                    .append(row.arm()).append(',')
                    .append(row.run()).append(',')
                    .append(format(row.rowsPerSecond())).append(',')
                    .append(format(row.nsPerRow())).append(',')
                    .append(format(row.localVisiblePerRow())).append(',')
                    .append(format(row.historyFallbacksPerRow())).append(',')
                    .append(format(row.versionSlotFetchesPerRow())).append(',')
                    .append(format(row.entriesVisitedPerRow())).append(',')
                    .append(Long.toUnsignedString(row.fingerprint())).append('\n');
        }
        DelosBenchmarkSupport.writeUtf8(
                reportDirectory.resolve("mvcc-row-bearing-btree-read-runs.csv"),
                csv.toString());
    }

    private static void writeReadComparison(Path reportDirectory, List<ReadRun> results)
            throws Exception {
        StringBuilder csv = new StringBuilder();
        csv.append("scenario,arm,medianRowsPerSecond,iqrToMedian,madToMedian,vsExisting,")
                .append("percentChangeVsExisting,medianEntriesVisitedPerRow,")
                .append("medianHistoryFallbacksPerRow,medianVersionSlotFetchesPerRow\n");
        Map<String, Map<String, ReadStats>> summary = summarizeReads(results);
        for (var scenario : summary.entrySet()) {
            ReadStats existing = scenario.getValue().get(ReadArm.EXISTING.name());
            for (ReadArm arm : ReadArm.values()) {
                ReadStats stats = scenario.getValue().get(arm.name());
                if (stats == null || existing == null) {
                    continue;
                }
                double ratio = stats.medianRowsPerSecond() / existing.medianRowsPerSecond();
                csv.append(scenario.getKey()).append(',')
                        .append(arm.name()).append(',')
                        .append(format(stats.medianRowsPerSecond())).append(',')
                        .append(format(stats.iqrToMedian())).append(',')
                        .append(format(stats.madToMedian())).append(',')
                        .append(format(ratio)).append(',')
                        .append(format((ratio - 1.0d) * 100.0d)).append(',')
                        .append(format(stats.entriesVisitedPerRow())).append(',')
                        .append(format(stats.historyFallbacksPerRow())).append(',')
                        .append(format(stats.versionSlotFetchesPerRow())).append('\n');
            }
        }
        DelosBenchmarkSupport.writeUtf8(
                reportDirectory.resolve("mvcc-row-bearing-btree-read-comparison.csv"),
                csv.toString());
    }

    private static void writeValidation(
            Path reportDirectory,
            List<String> validations,
            boolean validationOnly) throws Exception {
        StringBuilder text = new StringBuilder();
        text.append("MVCC row-bearing B-tree semantic validation\n")
                .append("mode=").append(validationOnly ? "validation" : "benchmark").append('\n')
                .append("checks=").append(validations.size()).append('\n');
        for (String validation : validations) {
            text.append(validation).append('\n');
        }
        DelosBenchmarkSupport.writeUtf8(
                reportDirectory.resolve("mvcc-row-bearing-btree-validation.txt"),
                text.toString());
    }

    private static void writeDecision(
            Path reportDirectory,
            Results results,
            boolean validationOnly) throws Exception {
        String text = """
                MVCC row-bearing B-tree maintenance mechanism
                =============================================

                This gate compares two write-maintenance algorithms after the row-bearing
                current-entry access topology won the prior A/B/C leaf-stream proof.

                REPLACE_CURRENT
                  - one current full row per logical row/key incarnation
                  - payload update: delete old current entry + insert new current entry
                  - key update: delete old current, retain a retired-key history anchor,
                    insert new current entry
                  - compact current tree, but mutates/removes committed leaf entries

                APPEND_INTERVAL
                  - committed current entries are not removed on the hot write path
                  - payload update: append a newer current row-bearing entry
                  - key update: append a retired-key interval anchor + new current entry
                  - delete: append a tombstone entry
                  - range reads resolve only the versions for one key group at a time
                  - higher leaf density pressure until snapshot-horizon garbage collection

                Interpretation rules
                --------------------
                1. Any semantic fingerprint mismatch rejects that algorithm immediately.
                2. UPDATE_KEY historical-old/current-old and DELETE_REINSERT three-snapshot
                   cases are architecture gates, not optional diagnostics.
                3. Read results matter because APPEND_INTERVAL intentionally trades write-side
                   immutability for extra versions visited per key.
                4. Mutation tree time is isolated B-tree maintenance + commit. It is NOT a
                   production slowdown prediction. The conservativeAdditiveUpperBound column
                   simply adds measured existing SQL time and isolated tree time and therefore
                   overstates a real replacement that would remove existing topology work and
                   share one transaction commit.
                5. The B2I proxy is conservative: all leaf fields participate in Derby's B-tree
                   physical key and include a RowLocation tail. A Delos-native row-bearing primary
                   tree should keep branch separators narrow and would not require a secondary-index
                   RowLocation field.
                6. logBytesDelta is a filesystem-growth diagnostic only. RawStore log-file
                   preallocation can make it zero even when logged work occurred; do not use it
                   as a byte-accurate WAL measure.
                7. Do not productionize either algorithm from this gate alone. The winner must next
                   survive a true long-reader + concurrent-writer publication/visibility gate,
                   followed by checkpoint/crash-recovery and bounded-GC design.

                """
                + "validationOnly=" + validationOnly + "\n"
                + "mutationMeasurements=" + results.mutations().size() + "\n"
                + "readMeasurements=" + results.reads().size() + "\n"
                + "semanticChecks=" + results.validations().size() + "\n";
        DelosBenchmarkSupport.writeUtf8(
                reportDirectory.resolve("mvcc-row-bearing-btree-decision.txt"), text);
    }

    private static Map<String, Map<String, ReadStats>> summarizeReads(List<ReadRun> results) {
        Map<String, Map<String, List<ReadRun>>> grouped = new LinkedHashMap<>();
        for (ReadRun row : results) {
            grouped.computeIfAbsent(row.scenario(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(row.arm(), ignored -> new ArrayList<>())
                    .add(row);
        }
        Map<String, Map<String, ReadStats>> result = new LinkedHashMap<>();
        for (var scenario : grouped.entrySet()) {
            Map<String, ReadStats> perArm = new LinkedHashMap<>();
            for (var arm : scenario.getValue().entrySet()) {
                double[] rates = arm.getValue().stream()
                        .mapToDouble(ReadRun::rowsPerSecond)
                        .toArray();
                perArm.put(arm.getKey(), new ReadStats(
                        median(rates),
                        iqrToMedian(rates),
                        madToMedian(rates),
                        median(arm.getValue().stream()
                                .mapToDouble(ReadRun::entriesVisitedPerRow).toArray()),
                        median(arm.getValue().stream()
                                .mapToDouble(ReadRun::historyFallbacksPerRow).toArray()),
                        median(arm.getValue().stream()
                                .mapToDouble(ReadRun::versionSlotFetchesPerRow).toArray())));
            }
            result.put(scenario.getKey(), Map.copyOf(perArm));
        }
        return Map.copyOf(result);
    }

    private static double rate(long operations, long elapsedNanos) {
        return operations / (elapsedNanos / 1_000_000_000.0d);
    }

    private static double median(double[] values) {
        return percentile(values, 0.5d);
    }

    private static double percentile(double[] values, double fraction) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        if (sorted.length == 0) {
            return 0.0d;
        }
        if (sorted.length == 1) {
            return sorted[0];
        }
        double position = fraction * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower];
        }
        double weight = position - lower;
        return sorted[lower] * (1.0d - weight) + sorted[upper] * weight;
    }

    private static double iqrToMedian(double[] values) {
        double median = median(values);
        return median == 0.0d
                ? 0.0d
                : (percentile(values, 0.75d) - percentile(values, 0.25d)) / median;
    }

    private static double madToMedian(double[] values) {
        double median = median(values);
        if (median == 0.0d) {
            return 0.0d;
        }
        double[] deviations = Arrays.stream(values)
                .map(value -> Math.abs(value - median))
                .toArray();
        return median(deviations) / median;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String property(String suffix, String defaultValue) {
        return System.getProperty(
                "delosdb.benchmark.mvccRowBearingBTreeMaintenance." + suffix,
                defaultValue);
    }

    private static int intProperty(String suffix, int defaultValue) {
        return Integer.parseInt(property(suffix, Integer.toString(defaultValue)));
    }

    private enum MutationScenario {
        INSERT,
        UPDATE_PAYLOAD,
        UPDATE_KEY,
        DELETE,
        DELETE_REINSERT
    }

    private enum ReadArm {
        EXISTING,
        REPLACE_CURRENT,
        APPEND_INTERVAL
    }

    private record Results(
            List<MutationRun> mutations,
            List<ReadRun> reads,
            List<String> validations) {
    }

    private record ProductionResult(
            long elapsedNanos,
            int commitCount,
            long logBytesDelta,
            long currentSnapshot,
            long deletedSnapshot) {
    }

    private record MutationMaterial(
            List<Entry> before,
            List<Entry> after,
            List<Entry> tombstones,
            List<Entry> reinserted) {
    }

    private record TreeMutationResult(
            long elapsedNanos,
            int commitCount,
            long logBytesDelta,
            MutationCounts counts,
            TreeSpace afterSpace) {
    }

    private record MutationRun(
            String scenario,
            int run,
            int logicalRows,
            long productionElapsedNanos,
            int productionCommits,
            long productionLogBytesDelta,
            String algorithm,
            long treeElapsedNanos,
            int treeCommits,
            long treeLogBytesDelta,
            long treeInserts,
            long treeDeletes,
            long retiredAnchors,
            long pagesBefore,
            long pagesAfter,
            long unfilledPagesAfter) {
    }

    private record ValidationCase(
            String name,
            int start,
            int endExclusive,
            long snapshot) {
    }

    private record ReadScenario(
            String name,
            int start,
            int endExclusive,
            long snapshot,
            int expectedRows) {
    }

    private record ReadMeasurement(
            long rows,
            long fingerprint,
            long localVisible,
            long historyFallbacks,
            long versionSlotFetches,
            long entriesVisited) {
    }

    private record ReadRun(
            String scenario,
            String arm,
            int run,
            double rowsPerSecond,
            double nsPerRow,
            double localVisiblePerRow,
            double historyFallbacksPerRow,
            double versionSlotFetchesPerRow,
            double entriesVisitedPerRow,
            long fingerprint) {
    }

    private record ReadStats(
            double medianRowsPerSecond,
            double iqrToMedian,
            double madToMedian,
            double entriesVisitedPerRow,
            double historyFallbacksPerRow,
            double versionSlotFetchesPerRow) {
    }
}
