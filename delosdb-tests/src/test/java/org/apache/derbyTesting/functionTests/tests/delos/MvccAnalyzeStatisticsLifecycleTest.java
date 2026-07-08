/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccAnalyzeStatisticsLifecycleTest

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

import java.sql.Connection;

import org.apache.derby.iapi.store.types.DelosMvccAnalyzeStatisticsLifecycleDiagnostics;
import org.apache.derby.iapi.store.types.DelosMvccOptimizerCostDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/** SQL gate for MVCC storage statistics at Derby's explicit analyze/update-statistics boundary. */
public final class MvccAnalyzeStatisticsLifecycleTest extends MvccSqlTestSupport {
    public void testExplicitUpdateStatisticsCapturesMvccStorageSnapshotWithoutOptimizerAuthority()
            throws Exception {
        String databaseName = databaseName("mvcc-analyze-statistics-lifecycle-db");

        try (SystemPropertyScope ignored = clearSystemProperty(DelosMvccOptimizerCostDiagnostics.PROPERTY_NAME)) {
            DelosMvccAnalyzeStatisticsLifecycleDiagnostics.clearForTesting();
            DelosMvccOptimizerCostDiagnostics.clearForTesting();

            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table mvcc_analyze_lifecycle_t "
                        + "(id int primary key, category varchar(16), payload varchar(128)) using delos_mvcc");
                executeUpdate(connection, "create index mvcc_analyze_lifecycle_category_idx "
                        + "on mvcc_analyze_lifecycle_t(category)");
                executeUpdate(connection, "insert into mvcc_analyze_lifecycle_t values (1, 'alpha', 'one')");
                executeUpdate(connection, "insert into mvcc_analyze_lifecycle_t values (2, 'beta', 'two')");
                executeUpdate(connection, "insert into mvcc_analyze_lifecycle_t values (3, 'beta', 'three')");
                executeUpdate(connection, "insert into mvcc_analyze_lifecycle_t values (4, 'gamma', 'four')");
                executeUpdate(connection, "update mvcc_analyze_lifecycle_t set payload = 'two-updated' where id = 2");
                connection.commit();

                long containerId = mvccContainerId(connection, "MVCC_ANALYZE_LIFECYCLE_T");
                assertEquals("analyze lifecycle diagnostics should start clean",
                        0L,
                        DelosMvccAnalyzeStatisticsLifecycleDiagnostics.explicitUpdateCountForTesting());
                assertEquals("optimizer-cost diagnostics should start clean",
                        0L,
                        DelosMvccOptimizerCostDiagnostics.statisticsEstimateCountForTesting());

                executeStatement(connection,
                        "call SYSCS_UTIL.SYSCS_UPDATE_STATISTICS('APP', 'MVCC_ANALYZE_LIFECYCLE_T', null)");

                assertEquals("one explicit MVCC update-statistics lifecycle event should be recorded",
                        1L,
                        DelosMvccAnalyzeStatisticsLifecycleDiagnostics.explicitUpdateCountForTesting());
                assertEquals("diagnostics snapshot must not fail",
                        0L,
                        DelosMvccAnalyzeStatisticsLifecycleDiagnostics.diagnosticFailureCountForTesting());
                assertEquals("statistics source should be the MVCC provider",
                        DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID,
                        DelosMvccAnalyzeStatisticsLifecycleDiagnostics.lastProviderIdForTesting());
                assertTrue("qualified table name should identify the analyzed MVCC table",
                        DelosMvccAnalyzeStatisticsLifecycleDiagnostics.lastQualifiedTableNameForTesting()
                                .contains("MVCC_ANALYZE_LIFECYCLE_T"));
                assertEquals("analyze lifecycle should report the base MVCC conglomerate",
                        containerId,
                        DelosMvccAnalyzeStatisticsLifecycleDiagnostics.lastContainerIdForTesting());
                assertEquals("visible MVCC logical rows should feed the analyze lifecycle snapshot",
                        4L,
                        DelosMvccAnalyzeStatisticsLifecycleDiagnostics.lastLogicalRowCountForTesting());
                assertTrue("physical MVCC versions should be available at analyze lifecycle time",
                        DelosMvccAnalyzeStatisticsLifecycleDiagnostics.lastPhysicalVersionCountForTesting() >= 4L);
                assertTrue("ordered-index statistics should be present at analyze lifecycle time",
                        DelosMvccAnalyzeStatisticsLifecycleDiagnostics.lastOrderedIndexEntryCountForTesting() > 0L);
                assertTrue("storage-derived full-scan estimate should be available but diagnostic-only",
                        DelosMvccAnalyzeStatisticsLifecycleDiagnostics.lastEstimatedFullScanCostForTesting() > 0L);
                assertTrue("storage-derived index estimate should be available but diagnostic-only",
                        DelosMvccAnalyzeStatisticsLifecycleDiagnostics.lastEstimatedIndexLookupCostForTesting() > 0L);
                assertTrue("summary should prove the inherited Derby statistics refresher seam",
                        DelosMvccAnalyzeStatisticsLifecycleDiagnostics.lastSummaryForTesting()
                                .contains("path=derby-index-statistics-refresher"));
                assertTrue("summary should prove MVCC storage-statistics source",
                        DelosMvccAnalyzeStatisticsLifecycleDiagnostics.lastSummaryForTesting()
                                .contains("source=mvcc-storage-statistics"));
                assertTrue("summary should keep Derby as optimizer/statistics authority",
                        DelosMvccAnalyzeStatisticsLifecycleDiagnostics.lastSummaryForTesting()
                                .contains("optimizerAuthority=derby"));
                assertEquals("explicit update statistics must not enable MVCC optimizer-cost consumption",
                        0L,
                        DelosMvccOptimizerCostDiagnostics.statisticsEstimateCountForTesting());

                assertRows(connection,
                        "select id, payload from mvcc_analyze_lifecycle_t where category = 'beta' order by id",
                        "2|two-updated",
                        "3|three");
                connection.commit();
            }
        } finally {
            DelosMvccAnalyzeStatisticsLifecycleDiagnostics.clearForTesting();
            DelosMvccOptimizerCostDiagnostics.clearForTesting();
        }
    }
}
