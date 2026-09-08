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
import org.apache.derby.iapi.store.raw.xact.RawTransaction;
import org.apache.derby.iapi.store.types.DelosRawStoreIoSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.store.raw.data.RawStoreIoFaultInjectionTestSupport;
import org.apache.derby.impl.store.raw.data.RawStoreWalAccountingTestSupport;
import org.apache.derby.impl.store.raw.log.LogCounter;

/**
 * Deterministic physical-work ledgers for fresh INSERT and full-image BARE UPDATE.
 *
 * <p>This is measurement-only. It deliberately does not add counters to the
 * production mutation hot path. Structural record/index deltas are obtained
 * from test-only storage inspection; RawStore writes are captured by the
 * existing bounded I/O recorder; flushed WAL span is read from RawStore's
 * existing log authority.</p>
 *
 * <p>The report contract is fail-closed: the generated ledger must contain the
 * Gen2-A1 BARE, Gen2-B PK, and the complete Gen2-C1 UPDATE matrix before
 * it can be used as architecture evidence.</p>
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
            if (shape == Shape.BARE) {
                observations.add(measure(Provider.MVCC_GEN2_A1, shape, rows));
            } else if (shape == Shape.PRIMARY_KEY) {
                observations.add(measure(Provider.MVCC_GEN2_B, shape, rows));
            }
        }

        writeReports(rows, observations);
        assertTopology(observations);
        assertGen2A1PhysicalGate(observations);
        assertGen2BPhysicalGate(observations);
    }

    /**
     * Full-image BARE UPDATE baseline. FIRST_UPDATE starts with an insert-only
     * fixture; SECOND_UPDATE replaces the same rows in a new transaction.
     * This is physical attribution, not a throughput benchmark or a vacuum test.
     */
    public void testGen2C1UpdatePhysicalAmplificationLedger() throws Exception {
        int rows = Integer.getInteger(ROWS_PROPERTY, 1_000);
        assertTrue("UPDATE physical accounting needs at least 100 fixture rows", rows >= 100);
        List<UpdateObservation> observations = new ArrayList<>();
        writeUpdateReports(observations, false);
        for (UpdateCase updateCase : UpdateCase.values()) {
            for (Provider provider : List.of(
                    Provider.HEAP, Provider.MVCC, Provider.MVCC_GEN2_C1)) {
                measureUpdates(provider, updateCase, rows, false, observations);
            }
        }
        // Heap repeatable-read retains locks and would block this writer. Do
        // not mislabel a different isolation level as an equivalent control.
        for (Provider provider : List.of(Provider.MVCC, Provider.MVCC_GEN2_C1)) {
            measureUpdates(provider, UpdateCase.NARROW_1, rows, true, observations);
        }
        assertEquals("complete UPDATE evidence matrix", 34, observations.size());
        assertTrue("every measured UPDATE must pass its semantic/structural checks",
                observations.stream().allMatch(UpdateObservation::verified));
        writeUpdateReports(observations, true);
    }

    private void measureUpdates(
            Provider provider,
            UpdateCase updateCase,
            int requestedRows,
            boolean heldReader,
            List<UpdateObservation> observations) throws Exception {
        // Wide fixtures are intentionally bounded: this is an attribution test
        // using the existing 256-event recorder, not a buffer-capacity workload.
        int rows = updateCase.wide ? 100 : requestedRows;
        String database = databaseName("update-physical-ledger-"
                + provider.name().toLowerCase(Locale.ROOT) + '-'
                + updateCase.name().toLowerCase(Locale.ROOT)
                + (heldReader ? "-held-reader" : "-no-reader"));
        boolean opened = false;
        try (SystemPropertyScope a1 = clearSystemProperty(
                        "delosdb.experimental.mvccGen2A1.enabled");
             SystemPropertyScope b1 = clearSystemProperty(
                        "delosdb.experimental.mvccGen2B.pk.enabled");
             SystemPropertyScope c1 = setSystemProperty(
                        "delosdb.experimental.mvccGen2C1.history.enabled",
                        Boolean.toString(provider == Provider.MVCC_GEN2_C1));
             SystemPropertyScope maintenance = setSystemProperty(
                        "delosdb.mvcc.rawStoreMaintenance.enabled", "false")) {
            try (Connection writer = openDatabase(database, true)) {
                opened = true;
                writer.setAutoCommit(false);
                try {
                    createUpdateFixture(writer, provider, updateCase, rows);
                    assertEquals("SQL fixture cardinality", rows, sqlRowCount(writer, "T"));
                    if (provider != Provider.HEAP) {
                        assertEquals("persisted UPDATE table format",
                                provider == Provider.MVCC_GEN2_C1 ? 3 : 1,
                                MvccRawStoreMetadataInspection.controlFormatVersion(writer, "T"));
                    }
                    writer.commit();
                    DelosDeleteReinsertPageTopologyTestSupport.Layout layout =
                            DelosDeleteReinsertPageTopologyTestSupport.inspect(
                                    writer, "T", provider != Provider.HEAP);
                    writer.commit();
                    try (Connection reader = heldReader ? openDatabase(database, false) : null;
                         PreparedStatement update = writer.prepareStatement(
                                 "update T set payload = ?"
                                         + (updateCase.fullChange ? ", padding = ?" : "")
                                         + " where id > ?")) {
                        if (reader != null) {
                            reader.setAutoCommit(false);
                            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                        }
                        try {
                            if (reader != null) {
                                assertUpdateContents(reader, updateCase, rows, 0);
                            }
                            for (int phase = 1; phase <= 2; phase++) {
                                measureUpdateWindow(writer, reader, update, database, provider,
                                        updateCase, rows, phase, layout, observations);
                            }
                            if (reader != null) {
                                reader.commit();
                                assertUpdateContents(reader, updateCase, rows, 2);
                                reader.commit();
                            }
                        } finally {
                            if (reader != null) {
                                reader.rollback();
                            }
                        }
                    }
                } finally {
                    writer.rollback();
                }
            }
        } finally {
            if (opened) {
                shutdownDatabase(database);
            }
        }
    }

    private static void createUpdateFixture(
            Connection connection, Provider provider, UpdateCase updateCase, int rows)
            throws Exception {
        executeUpdate(connection, "create table T (id int not null, payload varchar("
                + (updateCase.wide ? "2048" : "128") + ") not null"
                + (updateCase.wide ? ", padding varchar(2048) not null" : "")
                + ')' + (provider == Provider.HEAP ? "" : " using delos_mvcc"));
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into T values (?, ?" + (updateCase.wide ? ", ?" : "") + ')')) {
            for (int id = 1; id <= rows; id++) {
                insert.setInt(1, id);
                insert.setString(2, updatePayload(updateCase, 0));
                if (updateCase.wide) {
                    insert.setString(3, updatePadding(updateCase, 0));
                }
                assertEquals("fixture INSERT count", 1, insert.executeUpdate());
                if (id % 100 == 0) {
                    connection.commit();
                }
            }
        }
        connection.commit();
    }

    private static String updatePayload(UpdateCase updateCase, int phase) {
        return (phase == 0 ? "x" : phase == 1 ? "y" : "w").repeat(updateCase.width);
    }

    private static String updatePadding(UpdateCase updateCase, int phase) {
        return (updateCase.fullChange && phase > 0
                ? phase == 1 ? "q" : "r" : "z").repeat(updateCase.width);
    }

    private static void assertUpdateContents(
            Connection connection, UpdateCase updateCase, int rows, int phase) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "select id, payload" + (updateCase.wide ? ", padding" : "")
                             + " from T order by id")) {
            for (int id = 1; id <= rows; id++) {
                assertTrue("missing SQL row " + id, result.next());
                assertEquals("stable SQL row identity", id, result.getInt(1));
                int expectedPhase = id > rows - updateCase.updatedRows ? phase : 0;
                assertEquals("payload for row " + id,
                        updatePayload(updateCase, expectedPhase), result.getString(2));
                if (updateCase.wide) {
                    assertEquals("padding for row " + id,
                            updatePadding(updateCase, expectedPhase), result.getString(3));
                }
            }
            assertFalse("UPDATE must not add SQL rows", result.next());
        }
    }

    private static void measureUpdateWindow(
            Connection writer,
            Connection reader,
            PreparedStatement update,
            String database,
            Provider provider,
            UpdateCase updateCase,
            int rows,
            int phase,
            DelosDeleteReinsertPageTopologyTestSupport.Layout layout,
            List<UpdateObservation> observations) throws Exception {
        StructureSnapshot beforeStructure = structure(writer, provider, "T");
        List<MvccRawStoreMetadataInspection.RowLocationIdentity> beforeLocations =
                provider == Provider.MVCC_GEN2_C1
                        ? MvccRawStoreMetadataInspection.baseScanRowLocations(writer, "T")
                        : List.of();
        writer.commit();
        update.setString(1, updatePayload(updateCase, phase));
        int parameter = 2;
        if (updateCase.fullChange) {
            update.setString(parameter++, updatePadding(updateCase, phase));
        }
        update.setInt(parameter, rows - updateCase.updatedRows);

        // SQL compilation, fixtures, structure scans, and reader setup precede
        // the clean boundary. Reader verification happens after recording ends.
        DelosDeleteReinsertPageTopologyTestSupport.flushPageCache(writer);
        DelosRawStoreIoSnapshot beforeIo = rawStoreSnapshot(provider, database);
        UpdateWork beforeWork = updateWork(provider, database);
        long walBefore = flushedWalInstant(writer);
        String identity = beforeIo.databaseIdentity();
        RawStoreIoFaultInjectionTestSupport.installRecording(identity,
                "update-ledger-" + provider + '-' + updateCase + '-' + phase);
        int affected;
        long updateNanos;
        long commitNanos;
        long walAfter;
        DelosRawStoreIoSnapshot afterIo;
        UpdateWork afterWork;
        RawStoreIoFaultInjectionTestSupport.Evidence evidence;
        try {
            long start = System.nanoTime();
            affected = update.executeUpdate();
            long updated = System.nanoTime();
            writer.commit();
            long committed = System.nanoTime();
            updateNanos = updated - start;
            commitNanos = committed - updated;
            walAfter = flushedWalInstant(writer);
            DelosDeleteReinsertPageTopologyTestSupport.flushPageCache(writer);
            afterIo = rawStoreSnapshot(provider, database);
            afterWork = updateWork(provider, database);
            evidence = RawStoreIoFaultInjectionTestSupport.evidence(identity);
        } finally {
            RawStoreIoFaultInjectionTestSupport.clear(identity);
        }
        PageWriteSummary writes = pageWrites(
                layout, provider != Provider.HEAP, beforeIo, afterIo, evidence);
        StructureSnapshot afterStructure = structure(writer, provider, "T");
        UpdateObservation observation = new UpdateObservation(
                provider, updateCase, reader != null, phase, rows, affected,
                beforeStructure, afterStructure, writes, walBytes(walBefore, walAfter),
                updateNanos, commitNanos, afterWork.deltaFrom(beforeWork), List.of(), false);
        int index = observations.size();
        observations.add(observation);
        // Retain measured costs before semantic/topology assertions. An error
        // leaves an explicitly incomplete report, never a successful old one.
        writeUpdateReports(observations, false);

        // Read only after the measured I/O window has closed. This scans the
        // existing flushed WAL; it never changes the logging/flush protocol.
        observation = observation.withWalOperations(walOperations(
                writer, layout, provider, walBefore, walAfter));
        observations.set(index, observation);
        writeUpdateReports(observations, false);
        assertEquals("WAL operation bytes must account for the complete measured span",
                observation.walBytes(), observation.accountedWalBytes());
        assertUpdateTopology(observation);
        assertTrue("measured UPDATE must write RawStore pages", writes.writes() > 0L);
        assertTrue("measured UPDATE must append durable WAL", observation.walBytes() > 0L);
        assertUpdateContents(writer, updateCase, rows, phase);
        if (provider == Provider.MVCC_GEN2_C1) {
            assertEquals("C1 fixture must expose every current-row locator", rows, beforeLocations.size());
            assertTrue("C1 current rows require physical locator hints",
                    beforeLocations.stream().allMatch(
                            MvccRawStoreMetadataInspection.RowLocationIdentity::hasLocator));
            assertEquals("C1 UPDATE must retain current-row locations",
                    beforeLocations,
                    MvccRawStoreMetadataInspection.baseScanRowLocations(writer, "T"));
        }
        writer.commit();
        if (reader != null) {
            assertUpdateContents(reader, updateCase, rows, 0);
        }
        observations.set(index, observation.withVerified());
        writeUpdateReports(observations, false);
    }

    private static List<WalOperation> walOperations(
            Connection connection,
            DelosDeleteReinsertPageTopologyTestSupport.Layout layout,
            Provider provider,
            long before,
            long after) throws Exception {
        if (!(transactionManager(connection).getRawStoreXact() instanceof RawTransaction raw)) {
            throw new AssertionError("RawStore transaction required for WAL attribution");
        }
        Map<WalOperationKey, long[]> totals = new LinkedHashMap<>();
        for (var record : RawStoreWalAccountingTestSupport.read(raw.getLogFactory(), before, after)) {
            String role = record.containerId() < 0L ? "NON_CONTAINER"
                    : record.segmentId() == 0L
                            ? layout.role(record.containerId(), provider != Provider.HEAP).name()
                            : "OTHER_SEGMENT_" + record.segmentId();
            WalOperationKey key = new WalOperationKey(record.operation(), role);
            long[] values = totals.computeIfAbsent(key, ignored -> new long[3]);
            values[0]++;
            values[1] += record.recordBytes();
            values[2] += record.optionalBytes();
        }
        List<WalOperation> result = new ArrayList<>();
        for (var entry : totals.entrySet()) {
            long[] values = entry.getValue();
            result.add(new WalOperation(entry.getKey(), values[0], values[1], values[2]));
        }
        return List.copyOf(result);
    }

    private static UpdateWork updateWork(Provider provider, String database) {
        if (provider == Provider.HEAP) {
            // These are MVCC diagnostic counters, not fabricated Heap zeros.
            return UpdateWork.UNAVAILABLE;
        }
        var diagnostics = mvccDiagnostics(database);
        return new UpdateWork(
                diagnostics.updateCountForTesting(),
                diagnostics.scanOpenCountForTesting(),
                diagnostics.candidateIndexLookupCountForTesting(),
                diagnostics.candidateIndexRowIdCountForTesting(),
                diagnostics.rowIdFastPathReadCountForTesting());
    }

    private static void assertUpdateTopology(UpdateObservation observation) {
        assertEquals("one mutation per selected SQL row",
                observation.updateCase().updatedRows, observation.affectedRows());
        StructureSnapshot before = observation.before();
        StructureSnapshot after = observation.after();
        StructureDelta delta = before.deltaTo(after);
        long rows = observation.fixtureRows();
        long changed = observation.updateCase().updatedRows;
        long earlierUpdates = (observation.phase() - 1L) * changed;
        assertEquals("BARE table must have no SQL index before UPDATE", 0L, before.sqlIndexEntries());
        assertEquals("BARE table must have no SQL index after UPDATE", 0L, after.sqlIndexEntries());
        assertEquals("BARE table must have no native index before UPDATE", 0L,
                before.mvccNativeIndexEntries());
        assertEquals("BARE table must have no native index after UPDATE", 0L,
                after.mvccNativeIndexEntries());
        assertEquals("UPDATE must not add Heap/current/directory rows", 0L,
                delta.heapBaseRows() + delta.mvccDirectories());
        if (observation.provider() == Provider.HEAP) {
            assertEquals("Heap fixture rows before UPDATE", rows, before.heapBaseRows());
            assertEquals("Heap fixture rows after UPDATE", rows, after.heapBaseRows());
            assertEquals("Heap has no MVCC directories", 0L, after.mvccDirectories());
            assertEquals("Heap has no MVCC versions", 0L, after.mvccVersions());
            assertEquals("Heap UPDATE must not add logical records", 0L, delta.totalRecords());
        } else {
            assertEquals("MVCC does not add a second Heap representation", 0L, after.heapBaseRows());
            assertEquals("one directory/current row per SQL row before UPDATE", rows,
                    before.mvccDirectories());
            assertEquals("one directory/current row per SQL row after UPDATE", rows,
                    after.mvccDirectories());
            long initialVersions = observation.provider() == Provider.MVCC ? rows : 0L;
            assertEquals("version/history population before measured UPDATE",
                    initialVersions + earlierUpdates, before.mvccVersions());
            assertEquals("one retained predecessor per updated row",
                    initialVersions + earlierUpdates + changed, after.mvccVersions());
            assertEquals("only one added physical row per updated SQL row", changed,
                    delta.totalRecords());
        }
    }

    private static void writeUpdateReports(
            List<UpdateObservation> observations, boolean complete) throws Exception {
        Path directory = Path.of(System.getProperty(REPORT_DIRECTORY_PROPERTY,
                "build/reports/delosdb/benchmarks/mutation-physical-amplification"));
        Files.createDirectories(directory);
        List<String> csv = new ArrayList<>();
        csv.add("scenario,reader,phase,engine,metric,value,authority");
        List<String> markdown = new ArrayList<>();
        markdown.add("# Gen2-C1 BARE UPDATE physical ledger");
        markdown.add("");
        markdown.add("Evidence status: " + (complete ? "COMPLETE" : "INCOMPLETE")
                + "; observations: " + observations.size() + "/34.");
        markdown.add("Physical cost decision: PENDING_REVIEW. A completed ledger is not a throughput verdict.");
        markdown.add("");
        markdown.add("Each database starts with committed fresh INSERTs and then two separately committed UPDATEs. FIRST_UPDATE includes first-history allocation for C1; SECOND_UPDATE extends the same chains. This is not a steady-state or reclamation benchmark.");
        markdown.add("Narrow rows have a 96-character payload. Wide rows have two 1024-character fields: SPARSE changes only payload; FULL changes both. Wide fixtures contain 100 rows to bound recording. No history compression is enabled by this test.");
        markdown.add("Held readers are MVCC-only repeatable-read snapshots established before either update. Heap is not given a held reader because its repeatable-read locks would block the writer. Background MVCC maintenance is explicitly disabled for attribution in every case.");
        markdown.add("WAL spans cover UPDATE plus commit, before forced data-page flushing. Page I/O covers UPDATE, commit, and forced flushing, but excludes fixtures, structure inspection, and verification reads. Compilation is outside the measurement window. Per-operation timings are single-sample diagnostics only; there is no throughput, dispersion, or latency acceptance claim.");
        markdown.add("Gen1 version counts include its current VERSION images plus older images. C1 version-container counts contain historical predecessors only. DIRECTORY is the legacy inspection/role name for C1 CURRENT records.");
        markdown.add("Software counters come from existing MVCC diagnostics; unavailable Heap counters are omitted. Pending-list inspections and payload-clone counts are not instrumented by this overlay.");
        markdown.add("WAL attribution decodes the existing flushed log after measurement. Record bytes include framing and reconcile exactly to the measured span, including checksum/transaction records. Optional bytes are a subset containing operation data; UPDATE optional data combines before and after images, not separately measured redo/undo byte counts. No payload values are exported.");
        List<String> walCsv = new ArrayList<>();
        walCsv.add("scenario,reader,phase,engine,operation,physicalRole,recordCount,recordBytes,optionalBytes,headerAndFramingBytes");
        markdown.add("");
        markdown.add("| Case | Reader | Phase | Engine | Fixture | Updated | Current/directory before→after | Version/history before→after | Added records | Reads | Writes | Write bytes | WAL bytes | Verified |");
        markdown.add("|---|---|---|---|---:|---:|---|---|---:|---:|---:|---:|---:|---|");
        for (UpdateObservation o : observations) {
            StructureDelta delta = o.before().deltaTo(o.after());
            addUpdate(csv, o, "fixtureRows", o.fixtureRows(), "test fixture");
            addUpdate(csv, o, "updatedRows", o.affectedRows(), "JDBC UPDATE count");
            addUpdate(csv, o, "payloadWidth", o.updateCase().width, "characters per payload field");
            addUpdate(csv, o, "changedPayloadFields", o.updateCase().fullChange ? 2L : 1L,
                    "SQL SET clause; unchanged id is excluded");
            addUpdate(csv, o, "before.heapBaseRows", o.before().heapBaseRows(), "RawStore/access inspection");
            addUpdate(csv, o, "after.heapBaseRows", o.after().heapBaseRows(), "RawStore/access inspection");
            addUpdate(csv, o, "before.mvccCurrentOrDirectoryRecords", o.before().mvccDirectories(),
                    "RawStore current/directory inspection");
            addUpdate(csv, o, "after.mvccCurrentOrDirectoryRecords", o.after().mvccDirectories(),
                    "RawStore current/directory inspection");
            addUpdate(csv, o, "before.mvccVersionRecords", o.before().mvccVersions(),
                    "RawStore version-container inspection");
            addUpdate(csv, o, "after.mvccVersionRecords", o.after().mvccVersions(),
                    "RawStore version-container inspection");
            addUpdate(csv, o, "mvccVersionRecordsAdded", delta.mvccVersions(), "measured structural delta");
            addUpdate(csv, o, "after.sqlIndexEntries", o.after().sqlIndexEntries(), "access-index inspection");
            addUpdate(csv, o, "after.mvccNativeIndexEntries", o.after().mvccNativeIndexEntries(),
                    "native ordered-index inspection");
            addUpdate(csv, o, "totalUserRecordsAdded", delta.totalRecords(), "measured structural delta");
            addUpdate(csv, o, "rawStorePageReads", o.writes().reads(), "database RawStore I/O counters");
            addUpdate(csv, o, "rawStorePageReadBytes", o.writes().readBytes(), "database RawStore I/O counters");
            addUpdate(csv, o, "rawStorePageWrites", o.writes().writes(), "bounded recorder matched to counters");
            addUpdate(csv, o, "rawStorePageWriteBytes", o.writes().bytes(), "bounded recorder matched to counters");
            addUpdate(csv, o, "flushedWalSpanBytes", o.walBytes(), "RawStore flushed-log position delta");
            addUpdate(csv, o, "wal.accountedBytes", o.accountedWalBytes(), "decoded WAL records including framing/checksums");
            addUpdate(csv, o, "wal.reconciled", o.walReconciled() ? 1L : 0L, "exact decoded-byte/span equality");
            for (WalOperation operation : o.walOperations()) {
                walCsv.add(escape(o.updateCase().name()) + ',' + escape(o.readerName())
                        + ',' + escape(o.phaseName()) + ',' + escape(o.provider().name())
                        + ',' + escape(operation.key().operation()) + ',' + escape(operation.key().role())
                        + ',' + operation.count() + ',' + operation.bytes()
                        + ',' + operation.optionalBytes() + ',' + (operation.bytes() - operation.optionalBytes()));
            }
            addUpdate(csv, o, "diagnostic.updateElapsedNanos", o.updateNanos(), "single timed executeUpdate; not throughput evidence");
            addUpdate(csv, o, "diagnostic.commitElapsedNanos", o.commitNanos(), "single timed commit; not throughput evidence");
            addUpdate(csv, o, "semanticAndTopologyVerified", o.verified() ? 1L : 0L, "post-measurement assertions");
            if (o.work().updates() >= 0L) {
                addUpdate(csv, o, "mvcc.updateCalls", o.work().updates(), "existing MVCC diagnostic delta");
                addUpdate(csv, o, "mvcc.scanOpens", o.work().scanOpens(), "existing MVCC diagnostic delta");
                addUpdate(csv, o, "mvcc.candidateLookups", o.work().candidateLookups(), "existing MVCC diagnostic delta");
                addUpdate(csv, o, "mvcc.candidateRowIds", o.work().candidateRowIds(), "existing MVCC diagnostic delta");
                addUpdate(csv, o, "mvcc.rowIdFastPathReads", o.work().rowIdFastPathReads(), "existing MVCC diagnostic delta");
            }
            for (var role : o.writes().byRole().entrySet()) {
                addUpdate(csv, o, "pageWrites." + role.getKey().name(), role.getValue().writes(),
                        "RawStore page-write role; C1 CURRENT uses MVCC_METADATA_DIRECTORY");
                addUpdate(csv, o, "pageWriteBytes." + role.getKey().name(), role.getValue().bytes(),
                        "RawStore page-write role");
            }
            markdown.add("| " + o.updateCase() + " | " + o.readerName() + " | " + o.phaseName()
                    + " | " + o.provider().display + " | " + o.fixtureRows() + " | " + o.affectedRows()
                    + " | " + (o.before().heapBaseRows() + o.before().mvccDirectories())
                    + "→" + (o.after().heapBaseRows() + o.after().mvccDirectories())
                    + " | " + o.before().mvccVersions() + "→" + o.after().mvccVersions()
                    + " | " + delta.totalRecords() + " | " + o.writes().reads()
                    + " | " + o.writes().writes() + " | " + o.writes().bytes()
                    + " | " + o.walBytes() + " | " + o.verified() + " |");
        }
        markdown.add("");
        markdown.add("## WAL bytes by operation and physical role");
        markdown.add("");
        markdown.add("The companion gen2-c1-update-wal-operations.csv has all observations. The entries below show C1 only; optional bytes are included in record bytes, not additive.");
        markdown.add("");
        markdown.add("| Case | Reader | Phase | Operation | Role | Records | Record bytes | Optional bytes | Header/framing bytes |");
        markdown.add("|---|---|---|---|---|---:|---:|---:|---:|");
        for (UpdateObservation o : observations) {
            if (o.provider() != Provider.MVCC_GEN2_C1) {
                continue;
            }
            for (WalOperation operation : o.walOperations()) {
                markdown.add("| " + o.updateCase() + " | " + o.readerName() + " | " + o.phaseName()
                        + " | " + operation.key().operation() + " | " + operation.key().role()
                        + " | " + operation.count() + " | " + operation.bytes()
                        + " | " + operation.optionalBytes()
                        + " | " + (operation.bytes() - operation.optionalBytes()) + " |");
            }
        }
        Files.write(directory.resolve("gen2-c1-update-wal-operations.csv"), walCsv, StandardCharsets.UTF_8);
        Files.write(directory.resolve("gen2-c1-update-physical-ledger.csv"), csv, StandardCharsets.UTF_8);
        Files.write(directory.resolve("gen2-c1-update-physical-summary.md"), markdown, StandardCharsets.UTF_8);
        // Write the completion marker last, after all evidence files exist.
        Files.write(directory.resolve("gen2-c1-update-physical-status.properties"), List.of(
                "schemaVersion=2",
                "walAccounting=" + (complete && observations.size() == 34
                        && observations.stream().allMatch(UpdateObservation::walReconciled)
                                ? "COMPLETE" : "INCOMPLETE"),
                "status=" + (complete ? "COMPLETE" : "INCOMPLETE"),
                "expectedObservations=34",
                "observations=" + observations.size(),
                "verifiedObservations=" + observations.stream().filter(UpdateObservation::verified).count(),
                "physicalCostDecision=PENDING_REVIEW"), StandardCharsets.UTF_8);
    }

    private static void addUpdate(
            List<String> csv, UpdateObservation observation, String metric, long value, String authority) {
        csv.add(escape(observation.updateCase().name()) + ',' + escape(observation.readerName())
                + ',' + escape(observation.phaseName()) + ',' + escape(observation.provider().name())
                + ',' + escape(metric) + ',' + value + ',' + escape(authority));
    }

    private CaseObservation measure(Provider provider, Shape shape, int rows) throws Exception {
        String database = databaseName("mutation-physical-ledger-"
                + provider.name().toLowerCase(Locale.ROOT) + '-'
                + shape.name().toLowerCase(Locale.ROOT));
        String table = "T";
        String previousGen2A1 = System.getProperty("delosdb.experimental.mvccGen2A1.enabled");
        String previousGen2B = System.getProperty("delosdb.experimental.mvccGen2B.pk.enabled");
        String previousGen2C1 = System.getProperty("delosdb.experimental.mvccGen2C1.history.enabled");
        System.clearProperty("delosdb.experimental.mvccGen2C1.history.enabled");
        if (provider == Provider.MVCC_GEN2_A1) {
            System.setProperty("delosdb.experimental.mvccGen2A1.enabled", "true");
            System.clearProperty("delosdb.experimental.mvccGen2B.pk.enabled");
        } else if (provider == Provider.MVCC_GEN2_B) {
            System.clearProperty("delosdb.experimental.mvccGen2A1.enabled");
            System.setProperty("delosdb.experimental.mvccGen2B.pk.enabled", "true");
        } else {
            System.clearProperty("delosdb.experimental.mvccGen2A1.enabled");
            System.clearProperty("delosdb.experimental.mvccGen2B.pk.enabled");
        }
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, createTableSql(provider, shape, table));
            if (provider == Provider.MVCC_GEN2_A1 || provider == Provider.MVCC_GEN2_B) {
                assertEquals(
                        "Gen2 current-row table must persist control format marker 2 at CREATE time",
                        2,
                        MvccRawStoreMetadataInspection.controlFormatVersion(connection, table));
            }
            populate(connection, table, rows);
            connection.commit();

            DelosDeleteReinsertPageTopologyTestSupport.Layout layout =
                    DelosDeleteReinsertPageTopologyTestSupport.inspect(
                            connection, table, provider != Provider.HEAP);
            StructureSnapshot beforeStructure = structure(connection, provider, table);
            if (provider == Provider.MVCC_GEN2_A1 || provider == Provider.MVCC_GEN2_B) {
                assertEquals(
                        "Gen2 current-row table format marker must remain 2 before measured INSERT",
                        2,
                        MvccRawStoreMetadataInspection.controlFormatVersion(connection, table));
            }
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
                    layout, provider != Provider.HEAP, beforeIo, afterIo, evidence);
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
            restoreProperty("delosdb.experimental.mvccGen2A1.enabled", previousGen2A1);
            restoreProperty("delosdb.experimental.mvccGen2B.pk.enabled", previousGen2B);
            restoreProperty("delosdb.experimental.mvccGen2C1.history.enabled", previousGen2C1);
            shutdownDatabase(database);
        }
    }

    private static void restoreProperty(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

    private static String createTableSql(Provider provider, Shape shape, String table) {
        String primaryKey = shape == Shape.PRIMARY_KEY ? " primary key" : "";
        String mvcc = provider == Provider.HEAP ? "" : " using delos_mvcc";
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
        if (!(transactionManager(connection).getRawStoreXact() instanceof RawTransaction raw)) {
            throw new AssertionError("RawStore transaction required for WAL accounting");
        }
        LogFactory logFactory = raw.getLogFactory();
        return logFactory.getFirstUnflushedInstantAsLong();
    }

    private static long walBytes(long before, long after) {
        long beforeFile = LogCounter.getLogFileNumber(before);
        long afterFile = LogCounter.getLogFileNumber(after);
        if (beforeFile != afterFile) {
            throw new AssertionError(
                    "measured mutation unexpectedly crossed a WAL file boundary: before="
                            + beforeFile + ", after=" + afterFile);
        }
        long bytes = LogCounter.getLogFilePosition(after)
                - LogCounter.getLogFilePosition(before);
        if (bytes < 0L) {
            throw new AssertionError("WAL position regressed across measured mutation");
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
            } else if (observation.provider() == Provider.MVCC) {
                assertEquals("MVCC base is not counted as a Derby heap row", 0L, delta.heapBaseRows());
                assertEquals("MVCC Gen1 must add one stable directory record", 1L, delta.mvccDirectories());
                assertEquals("MVCC Gen1 must add one version record", 1L, delta.mvccVersions());
                assertEquals("MVCC Gen1 SQL index-entry delta", expectedSqlIndexes, delta.sqlIndexEntries());
                assertEquals("MVCC Gen1 native unique-index entry delta", expectedSqlIndexes,
                        delta.mvccNativeIndexEntries());
            } else if (observation.provider() == Provider.MVCC_GEN2_A1) {
                assertEquals("Gen2-A1 is measured only on the bare shape", Shape.BARE, observation.shape());
                assertEquals("Gen2-A1 base is not a Derby heap row", 0L, delta.heapBaseRows());
                assertEquals("Gen2-A1 must add one authoritative current-row record",
                        1L, delta.mvccDirectories());
                assertEquals("Gen2-A1 first INSERT must add no history/version record",
                        0L, delta.mvccVersions());
                assertEquals("Gen2-A1 bare table has no SQL index entry", 0L, delta.sqlIndexEntries());
                assertEquals("Gen2-A1 bare table has no native index entry",
                        0L, delta.mvccNativeIndexEntries());
            } else {
                assertEquals("Gen2-B is measured only on the PK shape",
                        Shape.PRIMARY_KEY, observation.shape());
                assertEquals("Gen2-B base is not a Derby heap row", 0L, delta.heapBaseRows());
                assertEquals("Gen2-B must add one authoritative current-row record",
                        1L, delta.mvccDirectories());
                assertEquals("Gen2-B fresh INSERT must add no history/version record",
                        0L, delta.mvccVersions());
                assertEquals("Gen2-B must add exactly one SQL PK index entry",
                        1L, delta.sqlIndexEntries());
                assertEquals("Gen2-B must not recreate the Gen1 native candidate index",
                        0L, delta.mvccNativeIndexEntries());
            }
        }
    }

    private static void assertGen2A1PhysicalGate(List<CaseObservation> observations) {
        CaseObservation heap = observations.stream()
                .filter(o -> o.provider() == Provider.HEAP && o.shape() == Shape.BARE)
                .findFirst().orElseThrow();
        CaseObservation gen2 = observations.stream()
                .filter(o -> o.provider() == Provider.MVCC_GEN2_A1 && o.shape() == Shape.BARE)
                .findFirst().orElseThrow();
        assertTrue("Gen2-A1 steady-state BARE INSERT must dirty at most two RawStore pages; got "
                        + gen2.writes().writes(),
                gen2.writes().writes() <= 2L);
        assertTrue("Gen2-A1 BARE WAL must stay within 2x Heap before adding PK/history: heap="
                        + heap.walBytes() + ", gen2=" + gen2.walBytes(),
                gen2.walBytes() <= Math.multiplyExact(heap.walBytes(), 2L));
    }

    private static void assertGen2BPhysicalGate(List<CaseObservation> observations) {
        CaseObservation heap = observations.stream()
                .filter(o -> o.provider() == Provider.HEAP && o.shape() == Shape.PRIMARY_KEY)
                .findFirst().orElseThrow();
        CaseObservation gen2 = observations.stream()
                .filter(o -> o.provider() == Provider.MVCC_GEN2_B
                        && o.shape() == Shape.PRIMARY_KEY)
                .findFirst().orElseThrow();
        assertEquals("Gen2-B PK must add the same two authoritative records as Heap",
                heap.structure().totalRecords(), gen2.structure().totalRecords());
        assertEquals("Gen2-B PK must not run the Gen1 native uniqueness candidate scan",
                0L, gen2.explicitNativeUniqueCandidateScans());
        assertEquals("Gen2-B PK must not refresh Gen1 native uniqueness metadata",
                0L, gen2.mvccUniqueMetadataRefreshCalls());
        assertTrue("Gen2-B steady-state PK INSERT must dirty at most three RawStore pages; got "
                        + gen2.writes().writes(),
                gen2.writes().writes() <= 3L);
        assertTrue("Gen2-B PK WAL must stay within 2x Heap: heap="
                        + heap.walBytes() + ", gen2=" + gen2.walBytes(),
                gen2.walBytes() <= Math.multiplyExact(heap.walBytes(), 2L));
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
        MVCC("MVCC Gen1"),
        MVCC_GEN2_A1("MVCC Gen2-A1"),
        MVCC_GEN2_B("MVCC Gen2-B"),
        MVCC_GEN2_C1("MVCC Gen2-C1");

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

    private enum UpdateCase {
        NARROW_1(1, 96, false, false),
        NARROW_10(10, 96, false, false),
        NARROW_100(100, 96, false, false),
        WIDE_SPARSE_1(1, 1024, true, false),
        WIDE_FULL_1(1, 1024, true, true);

        private final int updatedRows;
        private final int width;
        private final boolean wide;
        private final boolean fullChange;

        UpdateCase(int updatedRows, int width, boolean wide, boolean fullChange) {
            this.updatedRows = updatedRows;
            this.width = width;
            this.wide = wide;
            this.fullChange = fullChange;
        }
    }

    private record UpdateWork(
            long updates,
            long scanOpens,
            long candidateLookups,
            long candidateRowIds,
            long rowIdFastPathReads) {
        private static final UpdateWork UNAVAILABLE = new UpdateWork(-1L, -1L, -1L, -1L, -1L);

        UpdateWork deltaFrom(UpdateWork before) {
            return updates < 0L ? UNAVAILABLE : new UpdateWork(
                    updates - before.updates, scanOpens - before.scanOpens,
                    candidateLookups - before.candidateLookups,
                    candidateRowIds - before.candidateRowIds,
                    rowIdFastPathReads - before.rowIdFastPathReads);
        }
    }

    private record UpdateObservation(
            Provider provider,
            UpdateCase updateCase,
            boolean heldReader,
            int phase,
            int fixtureRows,
            int affectedRows,
            StructureSnapshot before,
            StructureSnapshot after,
            PageWriteSummary writes,
            long walBytes,
            long updateNanos,
            long commitNanos,
            UpdateWork work,
            List<WalOperation> walOperations,
            boolean verified) {
        UpdateObservation {
            walOperations = List.copyOf(walOperations);
        }

        long accountedWalBytes() {
            return walOperations.stream().mapToLong(WalOperation::bytes).sum();
        }

        boolean walReconciled() {
            return !walOperations.isEmpty() && accountedWalBytes() == walBytes;
        }

        UpdateObservation withWalOperations(List<WalOperation> operations) {
            return new UpdateObservation(provider, updateCase, heldReader, phase, fixtureRows,
                    affectedRows, before, after, writes, walBytes, updateNanos, commitNanos,
                    work, operations, verified);
        }

        String readerName() {
            return heldReader ? "HELD_READER" : "NO_READER";
        }

        String phaseName() {
            return phase == 1 ? "FIRST_UPDATE" : "SECOND_UPDATE";
        }

        UpdateObservation withVerified() {
            return new UpdateObservation(provider, updateCase, heldReader, phase, fixtureRows,
                    affectedRows, before, after, writes, walBytes, updateNanos, commitNanos,
                    work, walOperations, true);
        }
    }

    private record WalOperationKey(String operation, String role) {
    }

    private record WalOperation(WalOperationKey key, long count, long bytes, long optionalBytes) {
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
