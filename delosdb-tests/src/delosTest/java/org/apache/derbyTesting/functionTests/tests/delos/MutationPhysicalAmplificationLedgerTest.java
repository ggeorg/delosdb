/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MutationPhysicalAmplificationLedgerTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.store.access.ScanController;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.log.LogFactory;
import org.apache.derby.iapi.store.types.DelosRawStoreIoSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.store.raw.data.RawStoreIoFaultInjectionTestSupport;
import org.apache.derby.impl.store.raw.log.LogCounter;

/**
 * Deterministic physical-work ledger for one steady-state INSERT.
 *
 * <p>This is measurement-only. It deliberately does not add counters to the
 * production mutation hot path. Structural record/index deltas are obtained
 * from test-only storage inspection; RawStore writes are captured by the
 * existing bounded I/O recorder; flushed WAL span is read from RawStore's
 * existing log authority.</p>
 */
public final class MutationPhysicalAmplificationLedgerTest extends MvccSqlTestSupport {
    private static final String REPORT_DIRECTORY_PROPERTY =
            "delosdb.benchmark.mutationPhysicalAccounting.reportDirectory";
    private static final String ROWS_PROPERTY =
            "delosdb.benchmark.mutationPhysicalAccounting.rows";
    private static final int PAYLOAD_WIDTH = 96;

    public void testMutationPhysicalAmplificationLedger() throws Exception {
        int rows = Integer.getInteger(ROWS_PROPERTY, 1_000);
        assertTrue("physical mutation accounting needs at least 100 fixture rows", rows >= 100);

        List<CaseObservation> observations = new ArrayList<>();
        for (Shape shape : Shape.values()) {
            observations.add(measure(Provider.HEAP, shape, rows));
            observations.add(measure(Provider.MVCC, shape, rows));
        }

        assertTopology(observations);
        writeReports(rows, observations);
    }

    private CaseObservation measure(Provider provider, Shape shape, int rows) throws Exception {
        String database = databaseName("mutation-physical-ledger-"
                + provider.name().toLowerCase(Locale.ROOT) + '-'
                + shape.name().toLowerCase(Locale.ROOT));
        String table = "T";
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, createTableSql(provider, shape, table));
            populate(connection, table, rows);
            connection.commit();

            DelosDeleteReinsertPageTopologyTestSupport.Layout layout =
                    DelosDeleteReinsertPageTopologyTestSupport.inspect(
                            connection, table, provider == Provider.MVCC);
            StructureSnapshot beforeStructure = structure(connection, provider, table);
            connection.commit();

            // Establish a clean page/log boundary. This checkpoint is outside
            // the measured operation and therefore cannot be charged to INSERT.
            DelosDeleteReinsertPageTopologyTestSupport.flushPageCache(connection);
            DelosRawStoreIoSnapshot beforeIo = rawStoreSnapshot(provider, database);
            long walBefore = flushedWalInstant(connection);
            String databaseIdentity = beforeIo.databaseIdentity();
            RawStoreIoFaultInjectionTestSupport.installRecording(
                    databaseIdentity,
                    "mutation-physical-ledger-" + provider + '-' + shape);

            DelosRawStoreIoSnapshot afterIo;
            RawStoreIoFaultInjectionTestSupport.Evidence evidence;
            long walAfter;
            try {
                insertOne(connection, table, rows + 1);
                connection.commit();
                // Commit is the durability boundary; capture WAL before the
                // following checkpoint appends its own checkpoint record(s).
                walAfter = flushedWalInstant(connection);
                // Force all pages dirtied by INSERT/commit through the existing
                // RawStore page-write recorder.
                DelosDeleteReinsertPageTopologyTestSupport.flushPageCache(connection);
                afterIo = rawStoreSnapshot(provider, database);
                evidence = RawStoreIoFaultInjectionTestSupport.evidence(databaseIdentity);
            } finally {
                RawStoreIoFaultInjectionTestSupport.clear(databaseIdentity);
            }

            assertEquals("measured INSERT must leave exactly one additional SQL row",
                    rows + 1L, sqlRowCount(connection, table));
            StructureSnapshot afterStructure = structure(connection, provider, table);
            connection.commit();

            PageWriteSummary writes = pageWrites(
                    layout, provider == Provider.MVCC, beforeIo, afterIo, evidence);
            long walBytes = walBytes(walBefore, walAfter);
            return new CaseObservation(
                    provider,
                    shape,
                    beforeStructure.deltaTo(afterStructure),
                    writes,
                    walBytes,
                    provider == Provider.MVCC ? 1L : 0L,
                    provider == Provider.MVCC && shape == Shape.PRIMARY_KEY ? 1L : 0L);
        } finally {
            shutdownDatabase(database);
        }
    }

    private static String createTableSql(Provider provider, Shape shape, String table) {
        String primaryKey = shape == Shape.PRIMARY_KEY ? " primary key" : "";
        String mvcc = provider == Provider.MVCC ? " using delos_mvcc" : "";
        return "create table " + table
                + " (id int not null" + primaryKey
                + ", payload varchar(128) not null)" + mvcc;
    }

    private static void populate(Connection connection, String table, int rows) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into " + table + " (id, payload) values (?, ?)")) {
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
            }
        }
    }

    private static void insertOne(Connection connection, String table, int id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into " + table + " (id, payload) values (?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, "y".repeat(PAYLOAD_WIDTH));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static long sqlRowCount(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select count(*) from " + table)) {
            assertTrue(result.next());
            long count = result.getLong(1);
            assertFalse(result.next());
            return count;
        }
    }

    private static StructureSnapshot structure(
            Connection connection, Provider provider, String table) throws Exception {
        CatalogLayout catalog = catalogLayout(connection, table);
        long sqlIndexEntries = 0L;
        for (long conglomerate : catalog.indexConglomerates()) {
            sqlIndexEntries += countAccessRows(connection, conglomerate);
        }
        if (provider == Provider.HEAP) {
            return new StructureSnapshot(
                    countAccessRows(connection, catalog.baseConglomerate()),
                    0L,
                    0L,
                    sqlIndexEntries,
                    0L);
        }
        return new StructureSnapshot(
                0L,
                MvccRawStoreMetadataInspection.directories(connection, table).size(),
                MvccRawStoreMetadataInspection.versions(connection, table).size(),
                sqlIndexEntries,
                MvccRawStoreMetadataInspection.orderedIndexEntries(connection, table).size());
    }

    private static CatalogLayout catalogLayout(Connection connection, String table) throws Exception {
        long base = -1L;
        List<Long> indexes = new ArrayList<>();
        String sql = "select c.conglomeratenumber, c.isindex "
                + "from sys.sysconglomerates c, sys.systables t, sys.sysschemas s "
                + "where c.tableid = t.tableid and t.schemaid = s.schemaid "
                + "and s.schemaname = 'APP' and t.tablename = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table.toUpperCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long conglomerate = result.getLong(1);
                    if (result.getBoolean(2)) {
                        indexes.add(conglomerate);
                    } else {
                        base = conglomerate;
                    }
                }
            }
        }
        if (base < 0L) {
            throw new AssertionError("missing base conglomerate for " + table);
        }
        return new CatalogLayout(base, List.copyOf(indexes));
    }

    private static long countAccessRows(Connection connection, long conglomerate) throws Exception {
        TransactionManager manager = transactionManager(connection);
        ScanController scan = manager.openScan(
                conglomerate,
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_READ_UNCOMMITTED,
                null,
                null,
                ScanController.NA,
                null,
                null,
                ScanController.NA);
        try {
            long rows = 0L;
            while (scan.next()) {
                rows++;
            }
            return rows;
        } finally {
            scan.close();
        }
    }

    private static TransactionManager transactionManager(Connection connection) {
        if (!(connection instanceof EmbedConnection embedded)) {
            throw new AssertionError("embedded connection required for physical accounting");
        }
        LanguageConnectionContext lcc = embedded.getLanguageConnection();
        TransactionController controller = lcc.getTransactionExecute();
        if (!(controller instanceof TransactionManager manager)) {
            throw new AssertionError("Derby transaction manager required for physical accounting");
        }
        return manager;
    }

    private static DelosRawStoreIoSnapshot rawStoreSnapshot(
            Provider provider, String database) {
        Path path = databasePath(database);
        return provider == Provider.HEAP
                ? DelosStorageDiagnosticsRegistry.heapDatabaseRawStoreIoSnapshot(path)
                : DelosStorageDiagnosticsRegistry.mvccDatabaseRawStoreIoSnapshot(path);
    }

    private static long flushedWalInstant(Connection connection) throws Exception {
        LogFactory logFactory = transactionManager(connection)
                .getRawStoreXact()
                .getLogFactory();
        return logFactory.getFirstUnflushedInstantAsLong();
    }

    private static long walBytes(long before, long after) {
        long beforeFile = LogCounter.getLogFileNumber(before);
        long afterFile = LogCounter.getLogFileNumber(after);
        if (beforeFile != afterFile) {
            throw new AssertionError(
                    "single INSERT unexpectedly crossed a WAL file boundary: before="
                            + beforeFile + ", after=" + afterFile);
        }
        long bytes = LogCounter.getLogFilePosition(after)
                - LogCounter.getLogFilePosition(before);
        if (bytes < 0L) {
            throw new AssertionError("WAL position regressed across measured INSERT");
        }
        return bytes;
    }

    private static PageWriteSummary pageWrites(
            DelosDeleteReinsertPageTopologyTestSupport.Layout layout,
            boolean mvcc,
            DelosRawStoreIoSnapshot before,
            DelosRawStoreIoSnapshot after,
            RawStoreIoFaultInjectionTestSupport.Evidence evidence) {
        assertTrue("RawStore diagnostics must be active before measurement", before.runtimeActive());
        assertTrue("RawStore diagnostics must be active after measurement", after.runtimeActive());
        assertEquals("RawStore recorder must not overflow", 0L, evidence.discardedHits());

        EnumMap<DelosDeleteReinsertPageTopologyTestSupport.Role, MutableWrites> byRole =
                new EnumMap<>(DelosDeleteReinsertPageTopologyTestSupport.Role.class);
        MutableWrites all = new MutableWrites();
        for (RawStoreIoFaultInjectionTestSupport.HitEvidence hit : evidence.hits()) {
            if (!"AFTER_PAGE_WRITE".equals(hit.point())) {
                continue;
            }
            all.add(hit.length());
            DelosDeleteReinsertPageTopologyTestSupport.Role role =
                    layout.role(hit.containerId(), mvcc);
            byRole.computeIfAbsent(role, ignored -> new MutableWrites()).add(hit.length());
        }

        long counterWrites = after.pageWriteOperations() - before.pageWriteOperations();
        long counterBytes = after.pageWriteBytes() - before.pageWriteBytes();
        assertEquals("page-write recorder/counter operation mismatch", counterWrites, all.writes);
        assertEquals("page-write recorder/counter byte mismatch", counterBytes, all.bytes);

        Map<DelosDeleteReinsertPageTopologyTestSupport.Role, WriteDelta> frozen =
                new LinkedHashMap<>();
        for (var entry : byRole.entrySet()) {
            frozen.put(entry.getKey(), entry.getValue().freeze());
        }
        return new PageWriteSummary(
                all.writes,
                all.bytes,
                Map.copyOf(frozen),
                after.pageReadOperations() - before.pageReadOperations(),
                after.pageReadBytes() - before.pageReadBytes());
    }

    private static void assertTopology(List<CaseObservation> observations) {
        for (CaseObservation observation : observations) {
            StructureDelta delta = observation.structure();
            long expectedSqlIndexes = observation.shape() == Shape.PRIMARY_KEY ? 1L : 0L;
            if (observation.provider() == Provider.HEAP) {
                assertEquals("Heap must add one base record", 1L, delta.heapBaseRows());
                assertEquals("Heap must not have MVCC directory records", 0L, delta.mvccDirectories());
                assertEquals("Heap must not have MVCC version records", 0L, delta.mvccVersions());
                assertEquals("Heap SQL index-entry delta", expectedSqlIndexes, delta.sqlIndexEntries());
                assertEquals("Heap must not have MVCC native index entries", 0L,
                        delta.mvccNativeIndexEntries());
            } else {
                assertEquals("MVCC base is not counted as a Derby heap row", 0L, delta.heapBaseRows());
                assertEquals("MVCC must add one stable directory record", 1L, delta.mvccDirectories());
                assertEquals("MVCC must add one version record", 1L, delta.mvccVersions());
                assertEquals("MVCC SQL index-entry delta", expectedSqlIndexes, delta.sqlIndexEntries());
                assertEquals("MVCC native unique-index entry delta", expectedSqlIndexes,
                        delta.mvccNativeIndexEntries());
            }
        }
    }

    private static void writeReports(int fixtureRows, List<CaseObservation> observations)
            throws Exception {
        Path directory = Path.of(System.getProperty(
                REPORT_DIRECTORY_PROPERTY,
                "build/reports/delosdb/benchmarks/mutation-physical-amplification"));
        Files.createDirectories(directory);

        List<String> csv = new ArrayList<>();
        csv.add("shape,engine,metric,value,authority");
        for (Shape shape : Shape.values()) {
            long derbySqlIndexes = shape == Shape.PRIMARY_KEY ? 1L : 0L;
            add(csv, shape, "APACHE_DERBY_SOURCE", "heapBaseRows", 1L,
                    "source-proven Derby heap topology");
            add(csv, shape, "APACHE_DERBY_SOURCE", "sqlIndexEntries", derbySqlIndexes,
                    "source-proven inherited SQL index topology");
            add(csv, shape, "APACHE_DERBY_SOURCE", "totalUserRecordsAdded",
                    1L + derbySqlIndexes,
                    "base row + SQL index entries; no MVCC structures");
            add(csv, shape, "APACHE_DERBY_SOURCE", "explicitNativeUniqueCandidateScans", 0L,
                    "Derby uniqueness is enforced by the unique SQL B-tree insertion path");
            add(csv, shape, "APACHE_DERBY_SOURCE", "mvccUniqueMetadataRefreshCalls", 0L,
                    "no MVCC metadata path");
        }

        for (CaseObservation observation : observations) {
            StructureDelta delta = observation.structure();
            String engine = observation.provider().name();
            Shape shape = observation.shape();
            add(csv, shape, engine, "heapBaseRows", delta.heapBaseRows(), "measured structural delta");
            add(csv, shape, engine, "mvccDirectoryRecords", delta.mvccDirectories(), "measured structural delta");
            add(csv, shape, engine, "mvccVersionRecords", delta.mvccVersions(), "measured structural delta");
            add(csv, shape, engine, "sqlIndexEntries", delta.sqlIndexEntries(), "measured access-index delta");
            add(csv, shape, engine, "mvccNativeIndexEntries", delta.mvccNativeIndexEntries(), "measured hidden ordered-index delta");
            add(csv, shape, engine, "totalUserRecordsAdded", delta.totalRecords(), "sum of measured row/index deltas");
            add(csv, shape, engine, "mvccUniqueMetadataRefreshCalls",
                    observation.mvccUniqueMetadataRefreshCalls(),
                    "source-proven MvccRawStoreOrderedIndex.assertUnique");
            add(csv, shape, engine, "explicitNativeUniqueCandidateScans",
                    observation.explicitNativeUniqueCandidateScans(),
                    "source-proven candidatesForKey equality scan for one PK constraint");
            add(csv, shape, engine, "rawStorePageWrites", observation.writes().writes(),
                    "measured existing RawStore I/O recorder after forced flush");
            add(csv, shape, engine, "rawStorePageWriteBytes", observation.writes().bytes(),
                    "measured existing RawStore I/O recorder after forced flush");
            add(csv, shape, engine, "rawStorePageReads", observation.writes().reads(),
                    "measured existing database RawStore I/O counters");
            add(csv, shape, engine, "rawStorePageReadBytes", observation.writes().readBytes(),
                    "measured existing database RawStore I/O counters");
            add(csv, shape, engine, "flushedWalSpanBytes", observation.walBytes(),
                    "measured RawStore flushed-log position delta across INSERT+commit");
            for (var role : observation.writes().byRole().entrySet()) {
                add(csv, shape, engine, "pageWrites." + role.getKey().name(),
                        role.getValue().writes(), "measured RawStore page-write role");
                add(csv, shape, engine, "pageWriteBytes." + role.getKey().name(),
                        role.getValue().bytes(), "measured RawStore page-write role");
            }
        }
        Files.write(directory.resolve("mutation-physical-amplification-ledger.csv"), csv,
                StandardCharsets.UTF_8);

        List<String> markdown = new ArrayList<>();
        markdown.add("# DelosDB mutation physical amplification ledger");
        markdown.add("");
        markdown.add("Fixture rows before measured INSERT: " + fixtureRows);
        markdown.add("");
        markdown.add("The Apache Derby column is structural/source authority only. Heap and MVCC are measured in the Delos runtime. RawStore page-write and WAL-span values intentionally exclude fixture creation.");
        markdown.add("");
        markdown.add("| Shape | Engine | User records added | Base | Directory | Version | SQL index | MVCC native index | Unique pre-probes | Metadata refreshes | Page writes | WAL span bytes |");
        markdown.add("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
        for (Shape shape : Shape.values()) {
            long derbyIndexes = shape == Shape.PRIMARY_KEY ? 1L : 0L;
            markdown.add(row(shape, "Apache Derby (source)", 1L + derbyIndexes,
                    1L, 0L, 0L, derbyIndexes, 0L, 0L, 0L, null, null));
            for (CaseObservation observation : observations) {
                if (observation.shape() != shape) {
                    continue;
                }
                StructureDelta delta = observation.structure();
                markdown.add(row(shape, observation.provider().display,
                        delta.totalRecords(), delta.heapBaseRows(), delta.mvccDirectories(),
                        delta.mvccVersions(), delta.sqlIndexEntries(), delta.mvccNativeIndexEntries(),
                        observation.explicitNativeUniqueCandidateScans(),
                        observation.mvccUniqueMetadataRefreshCalls(),
                        observation.writes().writes(), observation.walBytes()));
            }
        }
        Files.write(directory.resolve("mutation-physical-amplification-summary.md"), markdown,
                StandardCharsets.UTF_8);
    }

    private static String row(
            Shape shape,
            String engine,
            long total,
            long base,
            long directory,
            long version,
            long sqlIndex,
            long nativeIndex,
            long preProbes,
            long metadataRefreshes,
            Long pageWrites,
            Long walBytes) {
        return "| " + shape.display + " | " + engine + " | " + total + " | " + base
                + " | " + directory + " | " + version + " | " + sqlIndex + " | "
                + nativeIndex + " | " + preProbes + " | " + metadataRefreshes + " | "
                + (pageWrites == null ? "—" : pageWrites) + " | "
                + (walBytes == null ? "—" : walBytes) + " |";
    }

    private static void add(
            List<String> csv, Shape shape, String engine, String metric, long value, String authority) {
        csv.add(escape(shape.name()) + ',' + escape(engine) + ',' + escape(metric) + ',' + value
                + ',' + escape(authority));
    }

    private static String escape(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private enum Provider {
        HEAP("Delos Heap"),
        MVCC("MVCC Gen1");

        private final String display;

        Provider(String display) {
            this.display = display;
        }
    }

    private enum Shape {
        BARE("BARE"),
        PRIMARY_KEY("PK");

        private final String display;

        Shape(String display) {
            this.display = display;
        }
    }

    private record CatalogLayout(long baseConglomerate, List<Long> indexConglomerates) {
        private CatalogLayout {
            indexConglomerates = List.copyOf(indexConglomerates);
        }
    }

    private record StructureSnapshot(
            long heapBaseRows,
            long mvccDirectories,
            long mvccVersions,
            long sqlIndexEntries,
            long mvccNativeIndexEntries) {
        StructureDelta deltaTo(StructureSnapshot after) {
            return new StructureDelta(
                    after.heapBaseRows - heapBaseRows,
                    after.mvccDirectories - mvccDirectories,
                    after.mvccVersions - mvccVersions,
                    after.sqlIndexEntries - sqlIndexEntries,
                    after.mvccNativeIndexEntries - mvccNativeIndexEntries);
        }
    }

    private record StructureDelta(
            long heapBaseRows,
            long mvccDirectories,
            long mvccVersions,
            long sqlIndexEntries,
            long mvccNativeIndexEntries) {
        long totalRecords() {
            return heapBaseRows + mvccDirectories + mvccVersions
                    + sqlIndexEntries + mvccNativeIndexEntries;
        }
    }

    private record WriteDelta(long writes, long bytes) {
    }

    private record PageWriteSummary(
            long writes,
            long bytes,
            Map<DelosDeleteReinsertPageTopologyTestSupport.Role, WriteDelta> byRole,
            long reads,
            long readBytes) {
        private PageWriteSummary {
            byRole = Map.copyOf(byRole);
        }
    }

    private record CaseObservation(
            Provider provider,
            Shape shape,
            StructureDelta structure,
            PageWriteSummary writes,
            long walBytes,
            long mvccUniqueMetadataRefreshCalls,
            long explicitNativeUniqueCandidateScans) {
    }

    private static final class MutableWrites {
        private long writes;
        private long bytes;

        void add(int length) {
            writes++;
            bytes += length;
        }

        WriteDelta freeze() {
            return new WriteDelta(writes, bytes);
        }
    }
}
