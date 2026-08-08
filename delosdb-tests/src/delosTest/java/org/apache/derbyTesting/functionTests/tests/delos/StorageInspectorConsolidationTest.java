/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.StorageInspectorConsolidationTest

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import org.apache.derby.iapi.store.types.DelosStorageConsistencyTarget;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageInspection;
import org.apache.derby.iapi.store.types.DelosStorageProviderIds;
import org.apache.derby.iapi.store.types.DelosStorageInspectionReport;

/** SQL gate for the consolidated heap/MVCC storage-inspector surface. */
public final class StorageInspectorConsolidationTest extends MvccSqlTestSupport {
    public void testMixedHeapAndMvccInspectionReportIsStableAndReadOnly() throws Exception {
        String databaseName = databaseName("storage-inspector-consolidation-db");
        Path databaseDirectory = new File(databaseName).toPath();

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table inspector_heap_t "
                    + "(id int primary key, name varchar(32))");
            executeUpdate(connection, "create table inspector_mvcc_t "
                    + "(id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into inspector_heap_t values (1, 'heap-alpha')");
            executeUpdate(connection, "insert into inspector_heap_t values (2, 'heap-beta')");
            connection.commit();
            executeUpdate(connection, "insert into inspector_mvcc_t values (1, 'mvcc-alpha')");
            executeUpdate(connection, "insert into inspector_mvcc_t values (2, 'mvcc-beta')");
            connection.commit();

            long heapContainerId = baseContainerId(connection, "INSPECTOR_HEAP_T", "heap");
            long mvccContainerId = mvccContainerId(connection, "INSPECTOR_MVCC_T");

            DelosStorageInspectionReport report = DelosStorageDiagnosticsRegistry.inspectionReport(
                    DelosStorageConsistencyTarget.heap(databaseDirectory, 0, heapContainerId),
                    mvccTarget(databaseName, 0, mvccContainerId));

            assertEquals("expected heap and MVCC inspection targets", 2, report.targetCount());
            assertTrue("expected clean mixed storage-inspection report: " + report.summaries(),
                    report.clean());
            assertEquals("expected no mixed storage-inspection errors", 0, report.errorCount());
            assertTrue("expected no failed inspections", report.failedInspections().isEmpty());
            assertTrue("expected heap provider in inspection report",
                    report.providerIds().contains(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID));
            assertTrue("expected MVCC provider in inspection report",
                    report.providerIds().contains(DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID));
            assertTrue("provider-id helper should match heap ids with whitespace/case differences",
                    DelosStorageProviderIds.matches("  DERBY_HEAP  ",
                            DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID));
            assertEquals("provider target should canonicalize MVCC id",
                    DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID,
                    new DelosStorageConsistencyTarget("  DELOS_MVCC  ", databasePath(databaseName), 0, mvccContainerId).providerId());

            DelosStorageInspection heapInspection = report.inspection(
                    "  DERBY_HEAP  ", 0, heapContainerId);
            DelosStorageInspection mvccInspection = report.inspection(
                    DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID, 0, mvccContainerId);

            assertEquals(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, heapInspection.providerId());
            assertEquals(0, heapInspection.segment());
            assertEquals(heapContainerId, heapInspection.containerId());
            assertEquals("HEAP_FILE_OBSERVED", heapInspection.checkpointStatus());
            assertTrue("expected heap page observation", heapInspection.pageDiagnostics().pageCount() > 0L);
            assertEquals("expected clean heap inspection", 0,
                    heapInspection.consistencyDiagnostics().errorCount());
            Path heapContainerFile = heapInspection.file(DelosStorageInspection.PAGE_VOLUME_FILE);
            assertNotNull("expected heap container file in consolidated inspection", heapContainerFile);
            assertTrue("expected heap container file to exist: " + heapContainerFile,
                    Files.isRegularFile(heapContainerFile));
            assertTrue("expected heap container file to be non-empty: " + heapContainerFile,
                    Files.size(heapContainerFile) > 0L);
            assertFalse("heap inspection files should not be empty", heapInspection.files().isEmpty());

            assertEquals(DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID, mvccInspection.providerId());
            assertEquals(0, mvccInspection.segment());
            assertEquals(mvccContainerId, mvccInspection.containerId());
            assertEquals("RAWSTORE_OWNED", mvccInspection.checkpointStatus());
            assertTrue("expected MVCC page observation", mvccInspection.pageDiagnostics().pageCount() > 0L);
            assertEquals("RawStore-backed inspection must not expose retired logical-row counters", 0,
                    mvccInspection.pageDiagnostics().logicalRowCount());
            assertEquals("expected clean MVCC inspection", 0,
                    mvccInspection.consistencyDiagnostics().errorCount());
            assertNull("RawStore-backed MVCC must not expose retired page-volume state files",
                    mvccInspection.file(DelosStorageInspection.PAGE_VOLUME_FILE));
            assertNull("RawStore-backed MVCC must not expose retired checkpoint state files",
                    mvccInspection.file(DelosStorageInspection.CHECKPOINT_FILE));
            assertTrue("RawStore-backed MVCC inspection must not expose retired external-state files",
                    mvccInspection.files().isEmpty());

            assertRows(connection,
                    "select id, name from inspector_heap_t order by id",
                    "1|heap-alpha",
                    "2|heap-beta");
            assertRows(connection,
                    "select id, name from inspector_mvcc_t order by id",
                    "1|mvcc-alpha",
                    "2|mvcc-beta");
            connection.commit();
        }
    }
}
