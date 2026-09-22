/*
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements. See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.
*/
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.log.LogFactory;
import org.apache.derby.iapi.store.raw.xact.RawTransaction;
import org.apache.derby.iapi.store.types.DelosRawStoreIoSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.store.raw.data.RawStoreWalAccountingTestSupport;
import org.apache.derby.impl.store.raw.log.LogCounter;
import org.apache.derby.shared.common.error.StandardException;

/**
 * F08-B/F08-C/F08-D/F08-E diagnostic: separates JDBC batch execution from transaction
 * commit and compares scalar commit stamping, page reuse, page-level RawStore WAL batching,
 * and a deliberately non-semantic no-stamp upper-bound control.
 */
public final class F08InsertCommitDecompositionTest extends MvccSqlTestSupport {
    private static final String REPORT_DIRECTORY_PROPERTY =
            "delosdb.benchmark.f08InsertCommit.reportDirectory";
    private static final String FIXTURE_ROWS_PROPERTY =
            "delosdb.benchmark.f08InsertCommit.fixtureRows";
    private static final String REPETITIONS_PROPERTY =
            "delosdb.benchmark.f08InsertCommit.repetitions";
    private static final String BATCH_CONTROL_PROPERTY =
            "delosdb.benchmark.f08InsertCommit.batchControl";
    private static final String WAL_BATCH_CONTROL_PROPERTY =
            "delosdb.benchmark.f08InsertCommit.walBatchControl";
    private static final String NO_STAMP_CONTROL_PROPERTY =
            "delosdb.benchmark.f08InsertCommit.noStampControl";
    private static final String COMMIT_STAMP_BATCH_PROPERTY =
            "delosdb.experimental.mvccGen2CommitStampBatch.enabled";
    private static final String COMMIT_STAMP_WAL_BATCH_PROPERTY =
            "delosdb.experimental.mvccGen2CommitStampWalBatch.enabled";
    private static final String COMMIT_STAMP_ELISION_CONTROL_PROPERTY =
            "delosdb.experimental.mvccGen2CommitStampElisionControl.enabled";
    private static final int PAYLOAD_WIDTH = 96;

    public void testInsertCommitDecomposition() throws Exception {
        int fixtureRows = Integer.getInteger(FIXTURE_ROWS_PROPERTY, 1_000);
        int repetitions = Integer.getInteger(REPETITIONS_PROPERTY, 7);
        assertTrue("F08-B fixture needs at least 100 rows", fixtureRows >= 100);
        assertTrue("F08-B needs at least 3 repetitions", repetitions >= 3);

        boolean batchControl = Boolean.getBoolean(BATCH_CONTROL_PROPERTY);
        boolean walBatchControl = Boolean.getBoolean(WAL_BATCH_CONTROL_PROPERTY);
        boolean noStampControl = Boolean.getBoolean(NO_STAMP_CONTROL_PROPERTY);
        List<Observation> observations = new ArrayList<>();
        for (int width : new int[] {1, 100}) {
            observations.add(measure(Provider.HEAP, Shape.BARE, width, fixtureRows, repetitions));
            observations.add(measure(Provider.GEN2_A1, Shape.BARE, width, fixtureRows, repetitions));
            if (noStampControl) {
                observations.add(measure(
                        Provider.GEN2_A1_NO_STAMP, Shape.BARE, width, fixtureRows, repetitions));
            }
            if (batchControl) {
                observations.add(measure(
                        Provider.GEN2_A1_BATCHED, Shape.BARE, width, fixtureRows, repetitions));
            }
            if (walBatchControl) {
                observations.add(measure(
                        Provider.GEN2_A1_WAL_BATCHED,
                        Shape.BARE,
                        width,
                        fixtureRows,
                        repetitions));
            }
            observations.add(measure(Provider.HEAP, Shape.PRIMARY_KEY, width, fixtureRows, repetitions));
            observations.add(measure(Provider.GEN2_B, Shape.PRIMARY_KEY, width, fixtureRows, repetitions));
            if (noStampControl) {
                observations.add(measure(
                        Provider.GEN2_B_NO_STAMP,
                        Shape.PRIMARY_KEY,
                        width,
                        fixtureRows,
                        repetitions));
            }
            if (batchControl) {
                observations.add(measure(
                        Provider.GEN2_B_BATCHED, Shape.PRIMARY_KEY, width, fixtureRows, repetitions));
            }
            if (walBatchControl) {
                observations.add(measure(
                        Provider.GEN2_B_WAL_BATCHED,
                        Shape.PRIMARY_KEY,
                        width,
                        fixtureRows,
                        repetitions));
            }
        }
        if (batchControl) {
            assertCommitStampWalParity(observations);
        }
        if (walBatchControl) {
            assertCommitStampWalBatching(observations);
        }
        if (noStampControl) {
            assertCommitStampElided(observations);
        }
        writeReports(fixtureRows, repetitions, observations);
    }

    private static void assertCommitStampWalParity(List<Observation> observations) {
        for (Shape shape : Shape.values()) {
            Provider scalar = shape == Shape.BARE ? Provider.GEN2_A1 : Provider.GEN2_B;
            Provider batched = shape == Shape.BARE
                    ? Provider.GEN2_A1_BATCHED
                    : Provider.GEN2_B_BATCHED;
            for (int width : new int[] {1, 100}) {
                Observation scalarObservation = observation(observations, scalar, shape, width);
                Observation batchedObservation = observation(observations, batched, shape, width);
                WalSummary scalarUpdates = scalarObservation.wal().get("UpdateFieldOperation");
                WalSummary batchedUpdates = batchedObservation.wal().get("UpdateFieldOperation");
                assertNotNull("scalar commit-stamp WAL is missing", scalarUpdates);
                assertNotNull("batched commit-stamp WAL is missing", batchedUpdates);
                assertEquals("page reuse must not change commit-stamp WAL record count",
                        scalarUpdates.count(), batchedUpdates.count());
                assertEquals("page reuse must not change commit-stamp WAL bytes",
                        scalarUpdates.bytes(), batchedUpdates.bytes());
            }
        }
    }

    private static void assertCommitStampWalBatching(List<Observation> observations) {
        for (Shape shape : Shape.values()) {
            Provider pageReuse = shape == Shape.BARE
                    ? Provider.GEN2_A1_BATCHED
                    : Provider.GEN2_B_BATCHED;
            Provider walBatched = shape == Shape.BARE
                    ? Provider.GEN2_A1_WAL_BATCHED
                    : Provider.GEN2_B_WAL_BATCHED;
            Observation pageReuseObservation =
                    observation(observations, pageReuse, shape, 100);
            Observation walBatchedObservation =
                    observation(observations, walBatched, shape, 100);

            WalSummary scalarUpdates =
                    pageReuseObservation.wal().get("UpdateFieldOperation");
            WalSummary batchedUpdates =
                    walBatchedObservation.wal().get("UpdateFieldsOperation");
            assertNotNull("page-reuse commit-stamp WAL is missing", scalarUpdates);
            assertNotNull("page-level batched commit-stamp WAL is missing", batchedUpdates);
            assertTrue(
                    "page-level WAL batching must collapse commit-stamp log records",
                    batchedUpdates.count() < scalarUpdates.count());

            WalSummary residualScalar =
                    walBatchedObservation.wal().get("UpdateFieldOperation");
            long residualCount = residualScalar == null ? 0L : residualScalar.count();
            assertTrue(
                    "page-level WAL batching must reduce scalar field-update records",
                    residualCount < scalarUpdates.count());
        }
    }

    private static void assertCommitStampElided(List<Observation> observations) {
        for (Shape shape : Shape.values()) {
            Provider noStamp = shape == Shape.BARE
                    ? Provider.GEN2_A1_NO_STAMP
                    : Provider.GEN2_B_NO_STAMP;
            for (int width : new int[] {1, 100}) {
                Observation observation = observation(observations, noStamp, shape, width);
                WalSummary scalar = observation.wal().get("UpdateFieldOperation");
                WalSummary batched = observation.wal().get("UpdateFieldsOperation");
                assertTrue(
                        "no-stamp upper-bound control must emit no scalar commit-stamp WAL",
                        scalar == null || scalar.count() == 0L);
                assertTrue(
                        "no-stamp upper-bound control must emit no batched commit-stamp WAL",
                        batched == null || batched.count() == 0L);
            }
        }
    }

    private static Observation observation(
            List<Observation> observations, Provider provider, Shape shape, int width) {
        return observations.stream()
                .filter(observation -> observation.provider() == provider
                        && observation.shape() == shape
                        && observation.width() == width)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing F08 observation for " + provider + '/' + shape + '/' + width));
    }

    private Observation measure(
            Provider provider,
            Shape shape,
            int width,
            int fixtureRows,
            int repetitions) throws Exception {
        String database = databaseName("f08-insert-commit-"
                + provider.name().toLowerCase(Locale.ROOT) + '-'
                + shape.name().toLowerCase(Locale.ROOT) + '-' + width);
        String previousA1 = System.getProperty("delosdb.experimental.mvccGen2A1.enabled");
        String previousB = System.getProperty("delosdb.experimental.mvccGen2B.pk.enabled");
        String previousC1 = System.getProperty("delosdb.experimental.mvccGen2C1.history.enabled");
        String previousCommitStampBatch = System.getProperty(COMMIT_STAMP_BATCH_PROPERTY);
        String previousCommitStampWalBatch =
                System.getProperty(COMMIT_STAMP_WAL_BATCH_PROPERTY);
        String previousCommitStampElision =
                System.getProperty(COMMIT_STAMP_ELISION_CONTROL_PROPERTY);
        configure(provider);
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, createTableSql(provider, shape));
            populate(connection, fixtureRows);
            connection.commit();

            List<Long> executeSamples = new ArrayList<>();
            List<Long> commitSamples = new ArrayList<>();
            int nextId = fixtureRows + 1;

            // Two untimed warmups keep class loading/statement initialization out of medians.
            for (int warmup = 0; warmup < 2; warmup++) {
                executeInsertBatch(connection, nextId, width);
                nextId += width;
                connection.commit();
            }

            for (int repetition = 0; repetition < repetitions; repetition++) {
                long executeStart = System.nanoTime();
                executeInsertBatch(connection, nextId, width);
                long executeEnd = System.nanoTime();
                nextId += width;
                long commitStart = System.nanoTime();
                connection.commit();
                long commitEnd = System.nanoTime();
                executeSamples.add(executeEnd - executeStart);
                commitSamples.add(commitEnd - commitStart);
            }

            // One separate physical sample. Do not force or flush between executeBatch and commit;
            // that would change the commit path we are trying to observe.
            DelosDeleteReinsertPageTopologyTestSupport.flushPageCache(connection);
            DelosRawStoreIoSnapshot beforeIo = rawStoreSnapshot(provider, database);
            long walBefore = flushedWalInstant(connection);
            long physicalExecuteStart = System.nanoTime();
            executeInsertBatch(connection, nextId, width);
            long physicalExecuteEnd = System.nanoTime();
            DelosRawStoreIoSnapshot afterExecuteIo = rawStoreSnapshot(provider, database);
            long physicalCommitStart = System.nanoTime();
            connection.commit();
            long physicalCommitEnd = System.nanoTime();
            long walAfter = flushedWalInstant(connection);
            DelosRawStoreIoSnapshot afterCommitIo = rawStoreSnapshot(provider, database);

            Map<String, WalSummary> wal = walSummary(connection, walBefore, walAfter);
            return new Observation(
                    provider,
                    shape,
                    width,
                    median(executeSamples),
                    median(commitSamples),
                    physicalExecuteEnd - physicalExecuteStart,
                    physicalCommitEnd - physicalCommitStart,
                    delta(beforeIo.pageReadOperations(), afterExecuteIo.pageReadOperations()),
                    delta(afterExecuteIo.pageReadOperations(), afterCommitIo.pageReadOperations()),
                    delta(beforeIo.pageWriteOperations(), afterExecuteIo.pageWriteOperations()),
                    delta(afterExecuteIo.pageWriteOperations(), afterCommitIo.pageWriteOperations()),
                    walBytes(walBefore, walAfter),
                    wal);
        } finally {
            restore("delosdb.experimental.mvccGen2A1.enabled", previousA1);
            restore("delosdb.experimental.mvccGen2B.pk.enabled", previousB);
            restore("delosdb.experimental.mvccGen2C1.history.enabled", previousC1);
            restore(COMMIT_STAMP_BATCH_PROPERTY, previousCommitStampBatch);
            restore(COMMIT_STAMP_WAL_BATCH_PROPERTY, previousCommitStampWalBatch);
            restore(COMMIT_STAMP_ELISION_CONTROL_PROPERTY, previousCommitStampElision);
            shutdownDatabase(database);
        }
    }

    private static void configure(Provider provider) {
        System.clearProperty("delosdb.experimental.mvccGen2C1.history.enabled");
        if (provider == Provider.GEN2_A1
                || provider == Provider.GEN2_A1_NO_STAMP
                || provider == Provider.GEN2_A1_BATCHED
                || provider == Provider.GEN2_A1_WAL_BATCHED) {
            System.setProperty("delosdb.experimental.mvccGen2A1.enabled", "true");
            System.clearProperty("delosdb.experimental.mvccGen2B.pk.enabled");
        } else if (provider == Provider.GEN2_B
                || provider == Provider.GEN2_B_NO_STAMP
                || provider == Provider.GEN2_B_BATCHED
                || provider == Provider.GEN2_B_WAL_BATCHED) {
            System.clearProperty("delosdb.experimental.mvccGen2A1.enabled");
            System.setProperty("delosdb.experimental.mvccGen2B.pk.enabled", "true");
        } else {
            System.clearProperty("delosdb.experimental.mvccGen2A1.enabled");
            System.clearProperty("delosdb.experimental.mvccGen2B.pk.enabled");
        }
        if (provider.pageReuseCommitStamp) {
            System.setProperty(COMMIT_STAMP_BATCH_PROPERTY, "true");
        } else {
            System.clearProperty(COMMIT_STAMP_BATCH_PROPERTY);
        }
        if (provider.walBatchedCommitStamp) {
            System.setProperty(COMMIT_STAMP_WAL_BATCH_PROPERTY, "true");
        } else {
            System.clearProperty(COMMIT_STAMP_WAL_BATCH_PROPERTY);
        }
        if (provider.elideCommitStamp) {
            System.setProperty(COMMIT_STAMP_ELISION_CONTROL_PROPERTY, "true");
        } else {
            System.clearProperty(COMMIT_STAMP_ELISION_CONTROL_PROPERTY);
        }
    }

    private static String createTableSql(Provider provider, Shape shape) {
        String primaryKey = shape == Shape.PRIMARY_KEY ? " primary key" : "";
        String mvcc = provider == Provider.HEAP ? "" : " using delos_mvcc";
        return "create table T (id int not null" + primaryKey
                + ", payload varchar(128) not null)" + mvcc;
    }

    private static void populate(Connection connection, int rows) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into T (id, payload) values (?, ?)")) {
            String payload = "x".repeat(PAYLOAD_WIDTH);
            for (int id = 1; id <= rows; id++) {
                statement.setInt(1, id);
                statement.setString(2, payload);
                statement.addBatch();
                if (id % 100 == 0) {
                    statement.executeBatch();
                    connection.commit();
                }
            }
            if (rows % 100 != 0) {
                statement.executeBatch();
                connection.commit();
            }
        }
    }

    private static void executeInsertBatch(Connection connection, int firstId, int width)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into T (id, payload) values (?, ?)")) {
            String payload = "y".repeat(PAYLOAD_WIDTH);
            if (width == 1) {
                statement.setInt(1, firstId);
                statement.setString(2, payload);
                assertEquals(1, statement.executeUpdate());
                return;
            }
            for (int offset = 0; offset < width; offset++) {
                statement.setInt(1, firstId + offset);
                statement.setString(2, payload);
                statement.addBatch();
            }
            int[] counts = statement.executeBatch();
            assertEquals(width, counts.length);
            for (int count : counts) {
                assertTrue("unexpected INSERT batch count: " + count,
                        count == 1 || count == Statement.SUCCESS_NO_INFO);
            }
        }
    }

    private static Map<String, WalSummary> walSummary(
            Connection connection, long before, long after) throws Exception {
        RawTransaction raw = rawTransaction(connection);
        Map<String, long[]> mutable = new LinkedHashMap<>();
        for (RawStoreWalAccountingTestSupport.RecordEvidence record
                : RawStoreWalAccountingTestSupport.read(raw.getLogFactory(), before, after)) {
            long[] aggregate = mutable.computeIfAbsent(record.operation(), ignored -> new long[2]);
            aggregate[0]++;
            aggregate[1] += record.recordBytes();
        }
        Map<String, WalSummary> result = new LinkedHashMap<>();
        mutable.forEach((operation, aggregate) ->
                result.put(operation, new WalSummary(aggregate[0], aggregate[1])));
        return Map.copyOf(result);
    }

    private static DelosRawStoreIoSnapshot rawStoreSnapshot(Provider provider, String database) {
        Path path = databasePath(database);
        return provider == Provider.HEAP
                ? DelosStorageDiagnosticsRegistry.heapDatabaseRawStoreIoSnapshot(path)
                : DelosStorageDiagnosticsRegistry.mvccDatabaseRawStoreIoSnapshot(path);
    }

    private static long flushedWalInstant(Connection connection) throws Exception {
        return rawTransaction(connection).getLogFactory().getFirstUnflushedInstantAsLong();
    }

    private static RawTransaction rawTransaction(Connection connection) throws StandardException {
        if (!(connection instanceof EmbedConnection embedded)) {
            throw new AssertionError("embedded connection required for F08-B accounting");
        }
        LanguageConnectionContext lcc = embedded.getLanguageConnection();
        TransactionController controller = lcc.getTransactionExecute();
        if (!(controller instanceof TransactionManager manager)) {
            throw new AssertionError("Derby transaction manager required for F08-B accounting");
        }
        if (!(manager.getRawStoreXact() instanceof RawTransaction raw)) {
            throw new AssertionError("RawStore transaction required for F08-B accounting");
        }
        return raw;
    }

    private static long walBytes(long before, long after) {
        long beforeFile = LogCounter.getLogFileNumber(before);
        long afterFile = LogCounter.getLogFileNumber(after);
        if (beforeFile != afterFile) {
            throw new AssertionError("F08-B sample crossed a WAL file boundary");
        }
        return LogCounter.getLogFilePosition(after) - LogCounter.getLogFilePosition(before);
    }

    private static long delta(long before, long after) {
        if (after < before) {
            throw new AssertionError("counter regressed: before=" + before + ", after=" + after);
        }
        return after - before;
    }

    private static long median(List<Long> samples) {
        List<Long> ordered = new ArrayList<>(samples);
        ordered.sort(Comparator.naturalOrder());
        int middle = ordered.size() / 2;
        if ((ordered.size() & 1) != 0) {
            return ordered.get(middle);
        }
        return (ordered.get(middle - 1) + ordered.get(middle)) / 2L;
    }

    private static void writeReports(
            int fixtureRows, int repetitions, List<Observation> observations) throws Exception {
        Path directory = Path.of(System.getProperty(
                REPORT_DIRECTORY_PROPERTY,
                "build/reports/delosdb/benchmarks/f08-insert-commit-decomposition"));
        Files.createDirectories(directory);

        List<String> csv = new ArrayList<>();
        csv.add("shape,engine,width,metric,value,authority");
        for (Observation observation : observations) {
            add(csv, observation, "median.executeBatchNanos", observation.executeNanos(),
                    "median of timed JDBC mutation phase");
            add(csv, observation, "median.commitNanos", observation.commitNanos(),
                    "median of timed Connection.commit phase");
            add(csv, observation, "physicalSample.executeBatchNanos",
                    observation.physicalExecuteNanos(), "single physical-accounting sample");
            add(csv, observation, "physicalSample.commitNanos",
                    observation.physicalCommitNanos(), "single physical-accounting sample");
            add(csv, observation, "physicalSample.pageReads.execute",
                    observation.executePageReads(), "RawStore database I/O counter delta");
            add(csv, observation, "physicalSample.pageReads.commit",
                    observation.commitPageReads(), "RawStore database I/O counter delta");
            add(csv, observation, "physicalSample.pageWritesObservedBeforeCommit",
                    observation.executePageWrites(), "RawStore physical write counter delta; buffered writes may remain zero");
            add(csv, observation, "physicalSample.pageWritesObservedDuringCommit",
                    observation.commitPageWrites(), "RawStore physical write counter delta; buffered writes may remain zero");
            add(csv, observation, "physicalSample.flushedWalSpanBytes",
                    observation.walBytes(), "RawStore flushed WAL span across executeBatch+commit");
            observation.wal().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        add(csv, observation, "walRecords." + entry.getKey(),
                                entry.getValue().count(), "decoded RawStore WAL operation count");
                        add(csv, observation, "walBytes." + entry.getKey(),
                                entry.getValue().bytes(), "decoded RawStore WAL operation bytes");
                    });
        }
        Files.write(directory.resolve("f08-insert-commit-decomposition.csv"), csv,
                StandardCharsets.UTF_8);

        List<String> md = new ArrayList<>();
        md.add("# F08 INSERT execution / commit decomposition");
        md.add("");
        md.add("Fixture rows: " + fixtureRows + "; timed repetitions: " + repetitions + ".");
        md.add("");
        md.add("INSERT_100 uses the same JDBC PreparedStatement executeBatch shape as the permanent F08 workload. Timings are diagnostic, not a throughput replacement.");
        md.add("");
        md.add("| Shape | Engine | Width | executeBatch median µs | commit median µs | commit share | execute reads | commit reads | WAL bytes |");
        md.add("|---|---|---:|---:|---:|---:|---:|---:|---:|");
        for (Observation o : observations) {
            long total = o.executeNanos() + o.commitNanos();
            double share = total == 0L ? 0.0 : (100.0 * o.commitNanos() / total);
            md.add(String.format(Locale.ROOT,
                    "| %s | %s | %d | %.1f | %.1f | %.1f%% | %d | %d | %d |",
                    o.shape().display, o.provider().display, o.width(),
                    o.executeNanos() / 1_000.0, o.commitNanos() / 1_000.0, share,
                    o.executePageReads(), o.commitPageReads(), o.walBytes()));
        }
        md.add("");
        md.add("## WAL operation mix for physical samples");
        md.add("");
        for (Observation o : observations) {
            md.add("### " + o.shape().display + " / " + o.provider().display + " / width " + o.width());
            o.wal().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    md.add("- `" + entry.getKey() + "`: " + entry.getValue().count()
                            + " records, " + entry.getValue().bytes() + " bytes"));
            md.add("");
        }
        Files.write(directory.resolve("f08-insert-commit-decomposition.md"), md,
                StandardCharsets.UTF_8);
    }

    private static void add(
            List<String> csv, Observation observation, String metric, long value, String authority) {
        csv.add(quote(observation.shape().name()) + ',' + quote(observation.provider().name()) + ','
                + observation.width() + ',' + quote(metric) + ',' + value + ',' + quote(authority));
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

    private enum Provider {
        HEAP("Delos Heap", false, false, false),
        GEN2_A1("MVCC Gen2-A1 scalar stamp", false, false, false),
        GEN2_A1_NO_STAMP("MVCC Gen2-A1 no-stamp upper bound", false, false, true),
        GEN2_A1_BATCHED("MVCC Gen2-A1 page-reuse stamp", true, false, false),
        GEN2_A1_WAL_BATCHED("MVCC Gen2-A1 page+WAL batched stamp", true, true, false),
        GEN2_B("MVCC Gen2-B scalar stamp", false, false, false),
        GEN2_B_NO_STAMP("MVCC Gen2-B no-stamp upper bound", false, false, true),
        GEN2_B_BATCHED("MVCC Gen2-B page-reuse stamp", true, false, false),
        GEN2_B_WAL_BATCHED("MVCC Gen2-B page+WAL batched stamp", true, true, false);

        private final String display;
        private final boolean pageReuseCommitStamp;
        private final boolean walBatchedCommitStamp;
        private final boolean elideCommitStamp;

        Provider(
                String display,
                boolean pageReuseCommitStamp,
                boolean walBatchedCommitStamp,
                boolean elideCommitStamp) {
            this.display = display;
            this.pageReuseCommitStamp = pageReuseCommitStamp;
            this.walBatchedCommitStamp = walBatchedCommitStamp;
            this.elideCommitStamp = elideCommitStamp;
        }
    }

    private enum Shape {
        BARE("BARE"),
        PRIMARY_KEY("PRIMARY KEY");

        private final String display;

        Shape(String display) {
            this.display = display;
        }
    }

    private record WalSummary(long count, long bytes) {}

    private record Observation(
            Provider provider,
            Shape shape,
            int width,
            long executeNanos,
            long commitNanos,
            long physicalExecuteNanos,
            long physicalCommitNanos,
            long executePageReads,
            long commitPageReads,
            long executePageWrites,
            long commitPageWrites,
            long walBytes,
            Map<String, WalSummary> wal) {}
}
