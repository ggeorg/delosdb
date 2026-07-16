/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.StorageLifecycleConsistencyReportTest

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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosMvccAnalyzeStatisticsLifecycleDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageConsistencyTarget;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageLifecycleConsistencyReport;
import org.apache.derby.iapi.store.types.DelosStorageLifecycleConsistencySnapshot;

/** SQL proof for the shared heap/MVCC storage lifecycle consistency report. */
public final class StorageLifecycleConsistencyReportTest extends MvccSqlTestSupport {
    public void testSharedLifecycleReportSummarizesHeapAndMvccLifecycleState() throws Exception {
        String databaseName = databaseName("storage-lifecycle-consistency-report-db");
        DelosMvccAnalyzeStatisticsLifecycleDiagnostics.clearForTesting();

        long heapContainerId;
        long mvccContainerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_lifecycle_report_t "
                    + "(id int primary key, payload varchar(64))");
            executeUpdate(connection, "insert into heap_lifecycle_report_t values (1, 'heap-one')");
            executeUpdate(connection, "insert into heap_lifecycle_report_t values (2, 'heap-two')");
            connection.commit();

            executeUpdate(connection, "create table mvcc_lifecycle_report_t "
                    + "(id int primary key, category varchar(16), payload varchar(32672)) using delos_mvcc");
            executeUpdate(connection, "create index mvcc_lifecycle_report_category_idx "
                    + "on mvcc_lifecycle_report_t(category)");
            insertMvccLifecycleRow(connection, 1, "alpha", "one");
            insertMvccLifecycleRow(connection, 2, "beta", repeated('r', 24000));
            connection.commit();

            executeUpdate(connection, "update mvcc_lifecycle_report_t set payload = 'one-updated' where id = 1");
            connection.commit();
            executeUpdate(connection, "update mvcc_lifecycle_report_t set payload = 'one-updated-again' where id = 1");
            connection.commit();

            heapContainerId = baseContainerId(connection, "HEAP_LIFECYCLE_REPORT_T", "heap");
            mvccContainerId = mvccContainerId(connection, "MVCC_LIFECYCLE_REPORT_T");

            inPlaceCompressTable(connection, "MVCC_LIFECYCLE_REPORT_T");
            connection.commit();
            executeStatement(connection,
                    "call SYSCS_UTIL.SYSCS_UPDATE_STATISTICS('APP', 'MVCC_LIFECYCLE_REPORT_T', null)");
            connection.commit();

            DelosStorageLifecycleConsistencyReport report = DelosStorageDiagnosticsRegistry
                    .lifecycleConsistencyReport(List.of(
                            DelosStorageConsistencyTarget.heap(Path.of(databaseName), 0, heapContainerId),
                            mvccTarget(databaseName, 0, mvccContainerId)));

            assertEquals("report should cover heap and MVCC targets", 2, report.targetCount());
            assertTrue("report should include the inherited heap provider: " + report.providerIds(),
                    report.providerIds().contains(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID));
            assertTrue("report should include the MVCC provider: " + report.providerIds(),
                    report.providerIds().contains(DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID));
            assertTrue("shared lifecycle report should be clean: " + report.summaries(), report.clean());
            assertEquals("one target should have Derby-triggered analyze/update-statistics state",
                    1L, report.analyzedTargetCount());
            assertTrue("at least one target should expose purge/vacuum lifecycle state",
                    report.purgeObservedTargetCount() >= 1L);

            DelosStorageLifecycleConsistencySnapshot heap = report.snapshot(
                    DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID,
                    0,
                    heapContainerId);
            assertTrue("heap lifecycle should observe the inherited heap file", heap.checkpointObserved());
            assertTrue("heap lifecycle should be consistent", heap.consistent());
            assertFalse("heap lifecycle report must not invent MVCC analyze state", heap.analyzeObserved());
            assertTrue("heap summary should name the heap provider: " + heap.summary(),
                    heap.summary().contains("provider=" + DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID));

            DelosStorageLifecycleConsistencySnapshot mvcc = report.snapshot(
                    DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID,
                    0,
                    mvccContainerId);
            assertTrue("MVCC lifecycle should observe a checkpoint", mvcc.checkpointObserved());
            assertTrue("MVCC lifecycle should expose recovery metadata records",
                    mvcc.recoveryRecordCount() > 0L);
            assertTrue("MVCC lifecycle should expose a complete recovery boundary",
                    mvcc.recoveryComplete());
            assertTrue("MVCC lifecycle should expose purge/vacuum state", mvcc.purgeObserved());
            assertTrue("MVCC lifecycle should expose analyze/update-statistics state", mvcc.analyzeObserved());
            assertTrue("MVCC analyze summary should preserve Derby optimizer authority: " + mvcc.analyzeSummary(),
                    mvcc.analyzeSummary().contains("optimizerAuthority=derby"));
            assertTrue("MVCC summary should be compact and provider-neutral: " + mvcc.summary(),
                    mvcc.summary().contains("provider=" + DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID)
                            && mvcc.summary().contains("checkpoint=")
                            && mvcc.summary().contains("recoveryRecords="));

            assertRows(connection,
                    "select id, category from mvcc_lifecycle_report_t order by id",
                    "1|alpha",
                    "2|beta");
        } finally {
            DelosMvccAnalyzeStatisticsLifecycleDiagnostics.clearForTesting();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, payload from heap_lifecycle_report_t order by id",
                    "1|heap-one",
                    "2|heap-two");
            assertRows(reopened,
                    "select id, category from mvcc_lifecycle_report_t order by id",
                    "1|alpha",
                    "2|beta");
        }
    }

    private static void insertMvccLifecycleRow(
            Connection connection,
            int id,
            String category,
            String payload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into mvcc_lifecycle_report_t values (?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, category);
            statement.setString(3, payload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String repeated(char value, int length) {
        char[] chars = new char[length];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
