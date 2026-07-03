/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.StoragePredicatePushdownModelTest

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
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStoragePredicatePushdown;
import org.apache.derby.iapi.store.types.DelosStoragePredicatePushdownReport;
import org.apache.derby.iapi.store.types.DelosStoragePredicatePushdownRequest;

/** SQL gate for the metadata-only storage predicate pushdown/remainder model. */
public final class StoragePredicatePushdownModelTest extends MvccSqlTestSupport {
    public void testPredicatePushdownModelStaysMetadataOnly() throws Exception {
        String databaseName = databaseName("storage-predicate-pushdown-db");
        Path databaseDirectory = new File(databaseName).toPath();

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table pushdown_heap_t "
                    + "(id int primary key, name varchar(32), payload varchar(128))");
            executeUpdate(connection, "create index pushdown_heap_name_idx on pushdown_heap_t(name)");
            executeUpdate(connection, "create table pushdown_mvcc_t "
                    + "(id int primary key, name varchar(32), payload varchar(128)) using delos_mvcc");
            executeUpdate(connection, "insert into pushdown_heap_t values (1, 'heap-alpha', 'one')");
            executeUpdate(connection, "insert into pushdown_heap_t values (2, 'heap-beta', 'two')");
            executeUpdate(connection, "insert into pushdown_mvcc_t values (1, 'mvcc-alpha', 'one')");
            executeUpdate(connection, "insert into pushdown_mvcc_t values (2, 'mvcc-beta', 'two')");
            executeUpdate(connection, "insert into pushdown_mvcc_t values (3, 'mvcc-gamma', 'three')");
            connection.commit();

            long heapContainerId = baseContainerId(connection, "PUSHDOWN_HEAP_T", "heap");
            long mvccContainerId = mvccContainerId(connection, "PUSHDOWN_MVCC_T");

            DelosStoragePredicatePushdownRequest heapRequest = new DelosStoragePredicatePushdownRequest(
                    " derby_heap ",
                    databaseDirectory,
                    0,
                    heapContainerId,
                    "id >= 1 and name like 'heap%'",
                    true,
                    false,
                    false,
                    List.of("id >= 1"),
                    List.of("name like 'heap%'"));
            DelosStoragePredicatePushdownRequest mvccRequest = new DelosStoragePredicatePushdownRequest(
                    " delos_mvcc ",
                    null,
                    0,
                    mvccContainerId,
                    "id >= 1 and name like 'mvcc%'",
                    true,
                    false,
                    false,
                    List.of("id >= 1"),
                    List.of("name like 'mvcc%'"));
            DelosStoragePredicatePushdownRequest mvccSnapshotRequest = new DelosStoragePredicatePushdownRequest(
                    "delos_mvcc",
                    null,
                    0,
                    mvccContainerId,
                    "snapshot id >= 1 and name like 'mvcc%'",
                    false,
                    true,
                    false,
                    List.of("id >= 1"),
                    List.of("name like 'mvcc%'"));

            DelosStoragePredicatePushdownReport report = DelosStorageDiagnosticsRegistry.predicatePushdownReport(
                    heapRequest,
                    mvccRequest,
                    mvccSnapshotRequest);
            assertEquals("expected heap, MVCC current committed, and MVCC snapshot plans",
                    3,
                    report.targetCount());
            assertTrue("predicate pushdown report must be read-only", report.readOnly());
            assertFalse("predicate pushdown report must not be consumed by Derby optimizer",
                    report.consumedByDerbyOptimizer());
            assertEquals("only the current-committed MVCC request should push metadata",
                    1L,
                    report.pushedTargetCount());
            assertEquals("all requests keep a Derby remainder or unpushed candidate",
                    3L,
                    report.remainderTargetCount());

            DelosStoragePredicatePushdown heapPlan = report.plan("DERBY_HEAP", 0, heapContainerId);
            assertFalse("heap ordered pushdown is not exposed through Delos metadata yet",
                    heapPlan.pushedToStorage());
            assertEquals("heap storage candidate must remain Derby remainder",
                    List.of("id >= 1", "name like 'heap%'"),
                    heapPlan.remainderPredicates());
            assertFalse("heap plan must not be optimizer-consumed", heapPlan.consumedByDerbyOptimizer());

            DelosStoragePredicatePushdown mvccPlan = report.plan(" delos_mvcc ", 0, mvccContainerId);
            assertTrue("current-committed MVCC ordered predicate should be modeled as pushable",
                    mvccPlan.pushedToStorage());
            assertEquals("MVCC storage candidate should be pushed in metadata",
                    List.of("id >= 1"),
                    mvccPlan.pushedPredicates());
            assertEquals("non-storage predicate remains Derby remainder",
                    List.of("name like 'mvcc%'"),
                    mvccPlan.remainderPredicates());
            assertTrue("current-committed shortcut should be marked safe for this metadata plan",
                    mvccPlan.safeForCurrentCommittedShortcut());
            assertFalse("snapshot shortcut remains disabled",
                    mvccPlan.safeForSnapshotShortcut());
            assertFalse("MVCC plan must not be optimizer-consumed",
                    mvccPlan.consumedByDerbyOptimizer());

            DelosStoragePredicatePushdown snapshotPlan = DelosStorageDiagnosticsRegistry.predicatePushdown(
                    mvccSnapshotRequest);
            assertFalse("snapshot request must not use current-committed pushdown metadata",
                    snapshotPlan.pushedToStorage());
            assertEquals("snapshot request keeps storage candidate as Derby remainder",
                    List.of("id >= 1", "name like 'mvcc%'"),
                    snapshotPlan.remainderPredicates());
            assertFalse("snapshot shortcut remains unproven", snapshotPlan.safeForSnapshotShortcut());

            assertRows(connection,
                    "select id, name from pushdown_heap_t where id >= 1 and name like 'heap%' order by id",
                    "1|heap-alpha",
                    "2|heap-beta");
            assertRows(connection,
                    "select id, name from pushdown_mvcc_t where id >= 1 and name like 'mvcc%' order by id",
                    "1|mvcc-alpha",
                    "2|mvcc-beta",
                    "3|mvcc-gamma");
            connection.commit();
        }
    }
}
