/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.PrimaryKeyReadPhysicalAccountingTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.derby.shared.common.i18n.MessageService;
import org.apache.derby.shared.common.reference.SQLState;

/** Measurement-only heap/MVCC physical accounting for one indexed primary-key read. */
public final class PrimaryKeyReadPhysicalAccountingTest extends MvccSqlTestSupport {
    private static final String REPORT_DIRECTORY_PROPERTY =
            "delosdb.benchmark.physicalReadAccounting.reportDirectory";
    private static final String ROWS_PROPERTY =
            "delosdb.benchmark.physicalReadAccounting.rows";
    private static final String DATABASE = "primary-key-read-physical-accounting-db";
    private static final String HEAP_TABLE = "PHYS_HEAP";
    private static final String MVCC_TABLE = "PHYS_MVCC";

    public void testPrimaryKeyReadPhysicalAccounting() throws Exception {
        int rows = Integer.getInteger(ROWS_PROPERTY, 10_000);
        assertTrue("physical accounting needs at least 100 rows", rows >= 100);
        int key = rows / 2;
        String database = databaseName(DATABASE);
        String heapStatistics;
        String mvccStatistics;
        MvccRawStoreMetadataInspection.OrderedIndexProbeStats mvccBtree;

        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            createFixture(connection, HEAP_TABLE, "", rows);
            createFixture(connection, MVCC_TABLE, " using delos_mvcc", rows);

            // Prime only the Heap statement. An MVCC warmup would populate the
            // database-scoped current-row anchor/read-image caches and bypass the
            // directory/version hinted-read topology this accounting test exists to
            // measure.
            executePrimaryKeyRead(connection, HEAP_TABLE, key, false);
            connection.commit();

            heapStatistics = measuredPrimaryKeyRead(connection, HEAP_TABLE, key, false);
            assertTrue("heap read must use the primary-key index; statistics=" + heapStatistics,
                    heapStatistics.contains("Index Scan ResultSet"));
            assertTrue("heap read must fetch the base row; statistics=" + heapStatistics,
                    heapStatistics.contains("Index Row to Base Row ResultSet"));
            connection.commit();
        }
        shutdownDatabase(database);

        // A clean restart clears the runtime-only current-row anchor/read-image caches.
        // The first MVCC read after reopen must therefore exercise exactly the physical
        // path under test: ordered-index candidate -> directory hint -> version hint.
        try (Connection connection = openDatabase(database, false)) {
            connection.setAutoCommit(false);
            mvccStatistics = measuredPrimaryKeyRead(connection, MVCC_TABLE, key, true);
            assertTrue("MVCC read must use the RawStore ordered index; statistics=" + mvccStatistics,
                    mvccStatistics.contains("delos_mvcc_rawstore_ordered_index"));
            assertFalse("benchmark primary-key read is intentionally non-covering; statistics="
                            + mvccStatistics,
                    mvccStatistics.contains("delos_mvcc_rawstore_ordered_index_covering"));

            mvccBtree = MvccRawStoreMetadataInspection.orderedIndexProbeStats(
                    connection, MVCC_TABLE, 0, key);
            connection.commit();
        }
        shutdownDatabase(database);

        long heapBtreePages = singleLocalizedMetric(
                heapStatistics, SQLState.STORE_RTS_NUM_PAGES_VISITED);
        long heapBtreeRows = singleLocalizedMetric(
                heapStatistics, SQLState.STORE_RTS_NUM_ROWS_VISITED);
        long heapBtreeQualified = singleLocalizedMetric(
                heapStatistics, SQLState.STORE_RTS_NUM_ROWS_QUALIFIED);
        long heapBtreeHeight = singleLocalizedMetric(
                heapStatistics, SQLState.STORE_RTS_TREE_HEIGHT);

        long candidates = mvccMetric(mvccStatistics, "mvccOrderedCandidates");
        long directoryPages = mvccMetric(mvccStatistics, "mvccDirectoryPageAcquisitions");
        long directoryFallbacks = mvccMetric(mvccStatistics, "mvccDirectoryLogicalFallbacks");
        long versionPages = mvccMetric(mvccStatistics, "mvccVersionPageAcquisitions");
        long versionSlotFetches = mvccMetric(mvccStatistics, "mvccVersionSlotFetches");
        long visibilityChecks = mvccMetric(mvccStatistics, "mvccVisibilityChecks");
        long versionChainSteps = mvccMetric(mvccStatistics, "mvccVersionChainSteps");
        long versionFallbacks = mvccMetric(mvccStatistics, "mvccVersionLogicalFallbacks");

        assertEquals("one logical heap B-tree row", 1L, heapBtreeRows);
        assertEquals("one qualified heap B-tree row", 1L, heapBtreeQualified);
        assertEquals("one MVCC ordered-index candidate", 1L, candidates);
        assertEquals("one MVCC hidden B-tree candidate", 1, mvccBtree.candidates());
        assertEquals("one MVCC directory page acquisition", 1L, directoryPages);
        assertEquals("MVCC directory hint must avoid logical fallback", 0L, directoryFallbacks);
        assertEquals("one MVCC version page acquisition", 1L, versionPages);
        assertEquals("current version hint performs identity plus projected slot fetches", 2L,
                versionSlotFetches);
        assertEquals("one MVCC visibility check", 1L, visibilityChecks);
        assertEquals("one current-head version-chain step", 1L, versionChainSteps);
        assertEquals("MVCC version hint must avoid logical fallback", 0L, versionFallbacks);

        List<Row> accounting = new ArrayList<>();
        accounting.add(row("B-tree scan opens", 1, 1,
                "source: TableScanResultSet/open scan vs MvccRawStoreOrderedIndex.scanCandidates"));
        accounting.add(row("B-tree pages visited", heapBtreePages, mvccBtree.pagesVisited(),
                "measured ScanInfo; MVCC uses test-only equality probe of the exact hidden B-tree"));
        accounting.add(row("B-tree tree height", heapBtreeHeight, mvccBtree.treeHeight(),
                "measured ScanInfo"));
        accounting.add(row("B-tree leaf rows visited", heapBtreeRows, mvccBtree.rowsVisited(),
                "measured ScanInfo"));
        accounting.add(row("B-tree successful rows qualified", heapBtreeQualified,
                mvccBtree.rowsQualified(), "measured ScanInfo"));
        accounting.add(row("B-tree scan-next/fetch-next calls", heapBtreeRows + 1,
                mvccBtree.nextCalls(),
                "heap and MVCC fetchNext return each candidate plus one terminal false"));
        accounting.add(row("B-tree leaf record fetch operations", 1, 1,
                "source-proven: heap and MVCC both use ScanController.fetchNext(row)"));
        accounting.add(row("heap base-row page acquisitions", 1, 0,
                "source-proven successful IndexRowToBaseRow fetch: one latchPage"));
        accounting.add(row("heap base-row record fetches", 1, 0,
                "source-proven successful GenericConglomerateController.fetch: one fetchFromSlot"));
        accounting.add(row("MVCC directory page acquisitions", 0, directoryPages,
                "measured mvccDirectoryPageAcquisitions"));
        accounting.add(row("MVCC directory record decodes", 0, candidates,
                "source-proven hinted directory lookup; no logical fallback"));
        accounting.add(row("MVCC directory logical fallbacks", 0, directoryFallbacks,
                "measured mvccDirectoryLogicalFallbacks"));
        accounting.add(row("MVCC version page acquisitions", 0, versionPages,
                "measured mvccVersionPageAcquisitions"));
        accounting.add(row("MVCC version slot fetches", 0, versionSlotFetches,
                "measured mvccVersionSlotFetches"));
        accounting.add(row("MVCC visibility checks", 0, visibilityChecks,
                "measured mvccVisibilityChecks"));
        accounting.add(row("MVCC version-chain steps", 0, versionChainSteps,
                "measured mvccVersionChainSteps"));
        accounting.add(row("MVCC version logical fallbacks", 0, versionFallbacks,
                "measured mvccVersionLogicalFallbacks"));
        accounting.add(row("logical candidates/result", 1, candidates,
                "one successful primary-key result"));
        accounting.add(row("unique physical records represented", 2, 3,
                "heap=index+base row; MVCC=index+directory+version"));
        accounting.add(row("physical record fetch/decode operations", 2, 4,
                "heap=index fetchNext+base fetch; MVCC=hidden-index fetchNext+directory decode+2 version slot fetches"));
        accounting.add(row("storage container opens required by read path", 2, 3,
                "source-proven: index+heap vs hidden index+directory+version"));

        writeReports(rows, key, accounting, heapStatistics, mvccStatistics, mvccBtree);
    }

    private static void createFixture(
            Connection connection, String table, String suffix, int rows) throws Exception {
        executeUpdate(connection, "create table " + table
                + " (id int not null primary key, quantity int not null, payload varchar(256) not null)"
                + suffix);
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + table + " values (?, ?, ?)")) {
            for (int id = 1; id <= rows; id++) {
                insert.setInt(1, id);
                insert.setInt(2, id * 3);
                insert.setString(3, "payload-" + id + '-' + "x".repeat(96));
                insert.addBatch();
                if (id % 100 == 0) {
                    insert.executeBatch();
                    connection.commit();
                }
            }
            if (rows % 100 != 0) {
                insert.executeBatch();
                connection.commit();
            }
        }
    }

    private static String measuredPrimaryKeyRead(
            Connection connection, String table, int key, boolean forceMvccBaseScan)
            throws Exception {
        executeUpdate(connection, "call syscs_util.syscs_set_runtimestatistics(1)");
        executePrimaryKeyRead(connection, table, key, forceMvccBaseScan);
        return runtimeStatistics(connection);
    }

    private static void executePrimaryKeyRead(
            Connection connection, String table, int key, boolean forceMvccBaseScan)
            throws Exception {
        String sql = "select id, quantity from " + table
                + (forceMvccBaseScan ? " --DERBY-PROPERTIES index=null\n" : " ")
                + "where id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue("primary-key row must exist", resultSet.next());
                assertEquals(key, resultSet.getInt(1));
                assertEquals(key * 3, resultSet.getInt(2));
                assertFalse("primary key must return exactly one row", resultSet.next());
            }
        }
    }

    private static String runtimeStatistics(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "values syscs_util.syscs_get_runtimestatistics()")) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static long singleLocalizedMetric(String statistics, String sqlState) {
        String name = MessageService.getTextMessage(sqlState);
        Pattern pattern = Pattern.compile(
                "(?m)^\\s*" + Pattern.quote(name) + "\\s*=\\s*(\\d+)\\s*$");
        Matcher matcher = pattern.matcher(statistics);
        assertTrue("missing runtime statistic " + name + "; statistics=" + statistics,
                matcher.find());
        long value = Long.parseLong(matcher.group(1));
        assertFalse("expected exactly one runtime statistic " + name + "; statistics="
                + statistics, matcher.find());
        return value;
    }

    private static long mvccMetric(String statistics, String name) {
        Pattern pattern = Pattern.compile(
                "(?m)^\\s*" + Pattern.quote(name) + "\\s*=\\s*(\\d+)\\s*$");
        Matcher matcher = pattern.matcher(statistics);
        assertTrue("missing MVCC scan metric " + name + "; statistics=" + statistics,
                matcher.find());
        return Long.parseLong(matcher.group(1));
    }

    private static Row row(String operation, long heap, long mvcc, String authority) {
        return new Row(operation, heap, mvcc, mvcc - heap, authority);
    }

    private static void writeReports(
            int rows,
            int key,
            List<Row> accounting,
            String heapStatistics,
            String mvccStatistics,
            MvccRawStoreMetadataInspection.OrderedIndexProbeStats mvccBtree) throws Exception {
        Path directory = Path.of(System.getProperty(
                REPORT_DIRECTORY_PROPERTY,
                "build/reports/delosdb/benchmarks/physical-read-accounting"));
        Files.createDirectories(directory);

        List<String> csv = new ArrayList<>();
        csv.add("operation,heap,mvcc,mvccExcess,authority");
        for (Row row : accounting) {
            csv.add(csv(row.operation()) + ',' + row.heap() + ',' + row.mvcc() + ','
                    + row.excess() + ',' + csv(row.authority()));
        }
        Files.write(directory.resolve("primary-key-read-physical-accounting.csv"), csv,
                StandardCharsets.UTF_8);

        StringBuilder text = new StringBuilder();
        text.append("DelosDB PRIMARY_KEY_READ physical accounting\n")
                .append("rows=").append(rows).append(" key=").append(key).append('\n')
                .append("query=select id, quantity from <table> where id = ?\n\n")
                .append(String.format(Locale.ROOT, "%-44s %8s %8s %8s%n",
                        "operation", "heap", "mvcc", "excess"));
        for (Row row : accounting) {
            text.append(String.format(Locale.ROOT, "%-44s %8d %8d %+8d%n",
                    row.operation(), row.heap(), row.mvcc(), row.excess()));
            text.append("    authority: ").append(row.authority()).append('\n');
        }
        text.append("\nMVCC hidden B-tree test-only probe: ").append(mvccBtree).append('\n')
                .append("\nImportant: the hidden-B-tree probe uses the exact published hidden B-tree and "
                        + "the same equality bounds as the production candidate path. It is test-only and "
                        + "does not change production instrumentation.\n")
                .append("\nHeap runtime statistics\n-----------------------\n")
                .append(heapStatistics).append('\n')
                .append("\nMVCC runtime statistics\n-----------------------\n")
                .append(mvccStatistics).append('\n');
        Files.writeString(directory.resolve("primary-key-read-physical-accounting.txt"),
                text.toString(), StandardCharsets.UTF_8);
        System.out.println(text);
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private record Row(String operation, long heap, long mvcc, long excess, String authority) {
    }
}
