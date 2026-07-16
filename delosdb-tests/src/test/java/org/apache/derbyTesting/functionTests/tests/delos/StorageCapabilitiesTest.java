/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.StorageCapabilitiesTest

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

import org.apache.derby.iapi.store.types.DelosStorageCapabilities;
import org.apache.derby.iapi.store.types.DelosStorageCapabilitiesReport;
import org.apache.derby.iapi.store.types.DelosStorageConsistencyTarget;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageMetadataSnapshot;

/** SQL gate for DelosDB storage access capability metadata. */
public final class StorageCapabilitiesTest extends MvccSqlTestSupport {
    public void testStorageCapabilitiesRemainReadOnlyAndOptimizerNeutral() throws Exception {
        String databaseName = databaseName("storage-capabilities-db");
        Path databaseDirectory = new File(databaseName).toPath();

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table capability_heap_t "
                    + "(id int primary key, name varchar(32), payload varchar(128))");
            executeUpdate(connection, "create index capability_heap_name_idx on capability_heap_t(name)");
            executeUpdate(connection, "create table capability_mvcc_t "
                    + "(id int primary key, name varchar(32), payload varchar(128)) using delos_mvcc");
            executeUpdate(connection, "insert into capability_heap_t values (1, 'heap-alpha', 'one')");
            executeUpdate(connection, "insert into capability_heap_t values (2, 'heap-beta', 'two')");
            executeUpdate(connection, "insert into capability_mvcc_t values (1, 'mvcc-alpha', 'one')");
            executeUpdate(connection, "insert into capability_mvcc_t values (2, 'mvcc-beta', 'two')");
            executeUpdate(connection, "insert into capability_mvcc_t values (3, 'mvcc-gamma', 'three')");
            connection.commit();

            long heapContainerId = baseContainerId(connection, "CAPABILITY_HEAP_T", "heap");
            long mvccContainerId = mvccContainerId(connection, "CAPABILITY_MVCC_T");
            DelosStorageConsistencyTarget heapTarget = new DelosStorageConsistencyTarget(
                    " DERBY_HEAP ", databaseDirectory, 0, heapContainerId);
            DelosStorageConsistencyTarget mvccTarget = new DelosStorageConsistencyTarget(
                    " DELOS_MVCC ", databasePath(databaseName), 0, mvccContainerId);

            List<DelosStorageMetadataSnapshot> snapshots = DelosStorageDiagnosticsRegistry.metadataSnapshots(
                    List.of(heapTarget, mvccTarget));
            assertEquals("expected heap and MVCC metadata snapshots", 2, snapshots.size());

            DelosStorageCapabilities heapCapabilities = snapshots.get(0).capabilities();
            assertEquals("expected normalized heap provider id",
                    DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID,
                    heapCapabilities.providerId());
            assertTrue("heap capabilities must be read-only", heapCapabilities.readOnly());
            assertTrue("heap storage statistics capability should be exposed",
                    heapCapabilities.supportsStorageStatistics());
            assertTrue("heap cost estimate capability should be exposed",
                    heapCapabilities.supportsCostEstimate());
            assertFalse("heap ordered equality is not exposed through Delos metadata yet",
                    heapCapabilities.supportsOrderedEqualityLookup());
            assertFalse("heap ordered range is not exposed through Delos metadata yet",
                    heapCapabilities.supportsOrderedRangeScan());
            assertFalse("heap current-committed shortcut is not exposed through Delos metadata yet",
                    heapCapabilities.supportsCurrentCommittedShortcut());
            assertFalse("heap capability metadata must not be optimizer-consumed",
                    heapCapabilities.consumedByDerbyOptimizer());

            DelosStorageCapabilities mvccCapabilities = snapshots.get(1).capabilities();
            assertEquals("expected normalized MVCC provider id",
                    DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID,
                    mvccCapabilities.providerId());
            assertTrue("MVCC capabilities must be read-only", mvccCapabilities.readOnly());
            assertTrue("MVCC row-id lookup should be exposed", mvccCapabilities.supportsRowIdLookup());
            assertTrue("MVCC ordered equality should be exposed",
                    mvccCapabilities.supportsOrderedEqualityLookup());
            assertTrue("MVCC ordered range should be exposed",
                    mvccCapabilities.supportsOrderedRangeScan());
            assertTrue("MVCC stable key order should be exposed",
                    mvccCapabilities.supportsStableKeyOrder());
            assertTrue("MVCC current-committed shortcut should be exposed",
                    mvccCapabilities.supportsCurrentCommittedShortcut());
            assertFalse("MVCC snapshot shortcut remains disabled until separately proven",
                    mvccCapabilities.supportsSnapshotShortcut());
            assertTrue("MVCC attribute overflow should be exposed",
                    mvccCapabilities.supportsAttributeOverflow());
            assertTrue("MVCC candidate-index authority removal should be exposed",
                    mvccCapabilities.candidateIndexAuthorityRemoved());
            assertTrue("MVCC storage statistics capability should be exposed",
                    mvccCapabilities.supportsStorageStatistics());
            assertTrue("MVCC cost estimate capability should be exposed",
                    mvccCapabilities.supportsCostEstimate());
            assertFalse("MVCC capability metadata must not be optimizer-consumed",
                    mvccCapabilities.consumedByDerbyOptimizer());

            DelosStorageCapabilitiesReport report = DelosStorageDiagnosticsRegistry.capabilitiesReport(
                    heapTarget,
                    mvccTarget);
            assertEquals("capabilities report should contain heap and MVCC", 2, report.targetCount());
            assertTrue("capabilities report must be read-only", report.readOnly());
            assertFalse("capabilities report must not be optimizer-consumed",
                    report.consumedByDerbyOptimizer());
            assertTrue("report lookup should normalize provider ids",
                    report.capability(" delos_mvcc ", 0, mvccContainerId).supportsOrderedRangeScan());
            assertTrue("summary should include ordered range capability",
                    report.summaries().toString().contains("orderedRange=true"));

            assertRows(connection,
                    "select id, name from capability_heap_t order by id",
                    "1|heap-alpha",
                    "2|heap-beta");
            assertRows(connection,
                    "select id, name from capability_mvcc_t order by id",
                    "1|mvcc-alpha",
                    "2|mvcc-beta",
                    "3|mvcc-gamma");
            connection.commit();
        }
    }
}
