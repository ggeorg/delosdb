/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.StorageOptimizerReviewGateTest

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

import org.apache.derby.iapi.store.types.DelosStorageConsistencyTarget;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageOptimizerReviewReport;
import org.apache.derby.iapi.store.types.DelosStoragePredicatePushdown;
import org.apache.derby.iapi.store.types.DelosStoragePredicatePushdownRequest;

/** SQL gate for the pre-optimizer metadata/cost/pushdown review boundary. */
public final class StorageOptimizerReviewGateTest extends MvccSqlTestSupport {
    public void testPreOptimizerReviewAllowsOnlyOptInNextStep() throws Exception {
        String databaseName = databaseName("storage-optimizer-review-db");
        Path databaseDirectory = new File(databaseName).toPath();

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table optimizer_review_heap_t "
                    + "(id int primary key, name varchar(32), payload varchar(128))");
            executeUpdate(connection, "create index optimizer_review_heap_name_idx "
                    + "on optimizer_review_heap_t(name)");
            executeUpdate(connection, "create table optimizer_review_mvcc_t "
                    + "(id int primary key, name varchar(32), payload varchar(128)) using delos_mvcc");
            executeUpdate(connection, "insert into optimizer_review_heap_t values (1, 'heap-alpha', 'one')");
            executeUpdate(connection, "insert into optimizer_review_heap_t values (2, 'heap-beta', 'two')");
            connection.commit();
            executeUpdate(connection, "insert into optimizer_review_mvcc_t values (1, 'mvcc-alpha', 'one')");
            executeUpdate(connection, "insert into optimizer_review_mvcc_t values (2, 'mvcc-beta', 'two')");
            executeUpdate(connection, "insert into optimizer_review_mvcc_t values (3, 'mvcc-gamma', 'three')");
            connection.commit();

            long heapContainerId = baseContainerId(connection, "OPTIMIZER_REVIEW_HEAP_T", "heap");
            long mvccContainerId = mvccContainerId(connection, "OPTIMIZER_REVIEW_MVCC_T");

            DelosStorageConsistencyTarget heapTarget = new DelosStorageConsistencyTarget(
                    " derby_heap ", databaseDirectory, 0, heapContainerId);
            DelosStorageConsistencyTarget mvccTarget = new DelosStorageConsistencyTarget(
                    " delos_mvcc ", databasePath(databaseName), 0, mvccContainerId);

            DelosStoragePredicatePushdownRequest heapRequest = new DelosStoragePredicatePushdownRequest(
                    "DERBY_HEAP",
                    databaseDirectory,
                    0,
                    heapContainerId,
                    "id >= 1 and name like 'heap%'",
                    true,
                    false,
                    false,
                    List.of("id >= 1"),
                    List.of("name like 'heap%'"));
            DelosStoragePredicatePushdownRequest mvccCurrentCommittedRequest = new DelosStoragePredicatePushdownRequest(
                    "DELOS_MVCC",
                    databasePath(databaseName),
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
                    databasePath(databaseName),
                    0,
                    mvccContainerId,
                    "snapshot id >= 1 and name like 'mvcc%'",
                    false,
                    true,
                    false,
                    List.of("id >= 1"),
                    List.of("name like 'mvcc%'"));
            DelosStoragePredicatePushdownRequest mvccWriterBorrowedRequest = new DelosStoragePredicatePushdownRequest(
                    "delos_mvcc",
                    databasePath(databaseName),
                    0,
                    mvccContainerId,
                    "writer borrowed id >= 1 and name like 'mvcc%'",
                    false,
                    false,
                    true,
                    List.of("id >= 1"),
                    List.of("name like 'mvcc%'"));

            DelosStorageOptimizerReviewReport report = DelosStorageDiagnosticsRegistry.optimizerReviewReport(
                    List.of(heapTarget, mvccTarget),
                    List.of(
                            heapRequest,
                            mvccCurrentCommittedRequest,
                            mvccSnapshotRequest,
                            mvccWriterBorrowedRequest));

            assertEquals("review should cover heap and MVCC metadata targets", 2, report.targetCount());
            assertEquals("review should cover heap/current/snapshot/writer-borrowed predicate plans",
                    4,
                    report.predicatePlanCount());
            assertTrue("pre-optimizer review must be read-only", report.readOnly());
            assertTrue("pre-optimizer review must remain optimizer-neutral", report.optimizerNeutral());
            assertTrue("cost estimates must remain proof-only", report.costEstimatesProofOnly());
            assertTrue("storage statistics must be available before optimizer work",
                    report.storageStatisticsAvailable());
            assertTrue("MVCC ordered access authority must be represented before optimizer work",
                    report.hasMvccOrderedAccessAuthority());
            assertTrue("snapshot and writer-borrowed shortcuts must remain disabled",
                    report.snapshotShortcutsStillDisabled());
            assertTrue("predicate pushdown model must be safe for review",
                    report.predicatePushdownSafeForReview());
            assertTrue("review should allow the next opt-in optimizer-integration experiment",
                    report.readyForOptInOptimizerIntegration());
            assertTrue("clean review should have no blockers", report.blockingIssues().isEmpty());
            assertTrue("summary should record readiness",
                    report.summary().contains("readyForOptInOptimizerIntegration=true"));

            DelosStoragePredicatePushdown currentPlan = report.predicatePushdownReport()
                    .plan("delos_mvcc", 0, mvccContainerId);
            assertTrue("current-committed MVCC ordered predicate may be modeled as pushed",
                    currentPlan.pushedToStorage());
            assertFalse("metadata-only plan must not be optimizer-consumed",
                    currentPlan.consumedByDerbyOptimizer());

            DelosStoragePredicatePushdown snapshotPlan = DelosStorageDiagnosticsRegistry.predicatePushdown(
                    mvccSnapshotRequest);
            assertFalse("snapshot request must keep storage candidate as Derby remainder",
                    snapshotPlan.pushedToStorage());
            assertEquals("snapshot request keeps both predicates as Derby remainders",
                    List.of("id >= 1", "name like 'mvcc%'"),
                    snapshotPlan.remainderPredicates());

            DelosStoragePredicatePushdown writerBorrowedPlan = DelosStorageDiagnosticsRegistry.predicatePushdown(
                    mvccWriterBorrowedRequest);
            assertFalse("writer-borrowed request must keep storage candidate as Derby remainder",
                    writerBorrowedPlan.pushedToStorage());
            assertEquals("writer-borrowed request keeps both predicates as Derby remainders",
                    List.of("id >= 1", "name like 'mvcc%'"),
                    writerBorrowedPlan.remainderPredicates());

            assertRows(connection,
                    "select id, name from optimizer_review_heap_t where id >= 1 and name like 'heap%' order by id",
                    "1|heap-alpha",
                    "2|heap-beta");
            assertRows(connection,
                    "select id, name from optimizer_review_mvcc_t where id >= 1 and name like 'mvcc%' order by id",
                    "1|mvcc-alpha",
                    "2|mvcc-beta",
                    "3|mvcc-gamma");
            connection.commit();
        }
    }
}
