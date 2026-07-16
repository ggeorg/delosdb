/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.CrossEngineConsistencyFrameworkTest

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

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;

import org.apache.derby.iapi.store.types.DelosCrossEngineConsistencyReport;
import org.apache.derby.iapi.store.types.DelosStorageConsistencyFinding;
import org.apache.derby.iapi.store.types.DelosStorageConsistencyTarget;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/** SQL gate for the shared heap/MVCC storage consistency framework. */
public final class CrossEngineConsistencyFrameworkTest extends MvccSqlTestSupport {
    public void testMixedHeapAndMvccConsistencyReportIsCleanAndReadOnly() throws Exception {
        String databaseName = databaseName("cross-engine-consistency-db");
        Path databaseDirectory = new File(databaseName).toPath();

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table cross_heap_t "
                    + "(id int primary key, name varchar(32))");
            executeUpdate(connection, "create table cross_mvcc_t "
                    + "(id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into cross_heap_t values (1, 'heap-alpha')");
            executeUpdate(connection, "insert into cross_heap_t values (2, 'heap-beta')");
            executeUpdate(connection, "insert into cross_mvcc_t values (1, 'mvcc-alpha')");
            executeUpdate(connection, "insert into cross_mvcc_t values (2, 'mvcc-beta')");
            connection.commit();

            assertRows(connection,
                    "select id, name from cross_heap_t order by id",
                    "1|heap-alpha",
                    "2|heap-beta");
            assertRows(connection,
                    "select id, name from cross_mvcc_t order by id",
                    "1|mvcc-alpha",
                    "2|mvcc-beta");

            long heapContainerId = baseContainerId(connection, "CROSS_HEAP_T", "heap");
            long mvccContainerId = mvccContainerId(connection, "CROSS_MVCC_T");

            DelosCrossEngineConsistencyReport report = DelosStorageDiagnosticsRegistry.consistencyReport(
                    DelosStorageConsistencyTarget.heap(databaseDirectory, 0, heapContainerId),
                    mvccTarget(databaseName, 0, mvccContainerId));

            assertEquals("expected one heap and one MVCC finding", 2, report.targetCount());
            assertTrue("expected clean mixed consistency report: " + report.summaries(), report.clean());
            assertEquals("expected no cross-engine consistency errors", 0, report.errorCount());
            assertTrue("expected no failed consistency findings", report.failedFindings().isEmpty());

            DelosStorageConsistencyFinding heapFinding = report.finding(
                    DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, 0, heapContainerId);
            DelosStorageConsistencyFinding mvccFinding = report.finding(
                    DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID, 0, mvccContainerId);

            assertTrue("expected clean heap consistency finding", heapFinding.clean());
            assertTrue("expected heap summary to mention heap", heapFinding.summary().contains("heap"));
            assertTrue("expected clean MVCC consistency finding", mvccFinding.clean());
            assertTrue("expected MVCC summary to include durable consistency counts",
                    mvccFinding.summary().contains("physicalVersions")
                            || mvccFinding.summary().contains("durableHeads")
                            || mvccFinding.summary().contains("valid"));

            assertRows(connection,
                    "select id, name from cross_heap_t order by id",
                    "1|heap-alpha",
                    "2|heap-beta");
            assertRows(connection,
                    "select id, name from cross_mvcc_t order by id",
                    "1|mvcc-alpha",
                    "2|mvcc-beta");
            connection.commit();
        }
    }

    public void testCrossEngineConsistencyReportSurfacesHeapMissWithoutTouchingMvcc() throws Exception {
        String databaseName = databaseName("cross-engine-consistency-missing-heap-db");
        Path databaseDirectory = new File(databaseName).toPath();

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table cross_missing_heap_t (id int primary key)");
            executeUpdate(connection, "create table cross_missing_mvcc_t (id int primary key) using delos_mvcc");
            executeUpdate(connection, "insert into cross_missing_heap_t values (1)");
            executeUpdate(connection, "insert into cross_missing_mvcc_t values (1)");
            connection.commit();

            long heapContainerId = baseContainerId(connection, "CROSS_MISSING_HEAP_T", "heap");
            long mvccContainerId = mvccContainerId(connection, "CROSS_MISSING_MVCC_T");
            long missingHeapContainerId = heapContainerId + 10_000_000L;

            DelosCrossEngineConsistencyReport report = DelosStorageDiagnosticsRegistry.consistencyReport(
                    DelosStorageConsistencyTarget.heap(databaseDirectory, 0, missingHeapContainerId),
                    mvccTarget(databaseName, 0, mvccContainerId));

            assertEquals("expected one heap and one MVCC finding", 2, report.targetCount());
            assertFalse("expected mixed report to surface missing heap container", report.clean());
            assertEquals("expected one heap consistency error", 1, report.errorCount());
            assertEquals("expected one failed finding", 1, report.failedFindings().size());

            DelosStorageConsistencyFinding heapFinding = report.finding(
                    DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, 0, missingHeapContainerId);
            DelosStorageConsistencyFinding mvccFinding = report.finding(
                    DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID, 0, mvccContainerId);

            assertFalse("expected missing heap finding to be dirty", heapFinding.clean());
            assertTrue("expected missing heap summary", heapFinding.summary().contains("missing"));
            assertTrue("expected MVCC finding to stay clean", mvccFinding.clean());

            assertRows(connection,
                    "select id from cross_missing_heap_t order by id",
                    "1");
            assertRows(connection,
                    "select id from cross_missing_mvcc_t order by id",
                    "1");
            connection.commit();
        }
    }
}
