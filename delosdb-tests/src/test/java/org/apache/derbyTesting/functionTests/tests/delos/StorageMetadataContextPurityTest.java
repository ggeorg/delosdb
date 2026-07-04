/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.StorageMetadataContextPurityTest

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

import org.apache.derby.iapi.store.types.DelosStorageConsistencyTarget;
import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsContext;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageInspection;
import org.apache.derby.iapi.store.types.DelosStorageMetadataSnapshot;

/** SQL gate for explicit storage metadata diagnostics context. */
public final class StorageMetadataContextPurityTest extends MvccSqlTestSupport {
    public void testHeapMetadataUsesExplicitContextWithoutMutatingDiagnosticsProvider() throws Exception {
        String databaseName = databaseName("storage-metadata-context-purity-db");
        Path databaseDirectory = new File(databaseName).toPath();

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table metadata_context_heap_t "
                    + "(id int primary key, name varchar(32))");
            executeUpdate(connection, "insert into metadata_context_heap_t values (1, 'heap-alpha')");
            connection.commit();

            long heapContainerId = baseContainerId(connection, "METADATA_CONTEXT_HEAP_T", "heap");
            DelosStorageDiagnostics heapDiagnostics = DelosStorageDiagnosticsRegistry.heap();
            heapDiagnostics.clearRuntimeStateForTesting();
            assertEquals("fresh heap diagnostics provider should have no mutable test context", 0,
                    heapDiagnostics.runtimeStateCountForTesting());

            DelosStorageDiagnostics contextualDiagnostics = heapDiagnostics.withContext(
                    DelosStorageDiagnosticsContext.databaseDirectory(databaseDirectory));
            assertEquals("creating an explicit diagnostics context must not mutate the base provider", 0,
                    heapDiagnostics.runtimeStateCountForTesting());

            DelosStorageInspection inspection = DelosStorageInspection.fromDiagnostics(
                    contextualDiagnostics, 0, heapContainerId);
            assertEquals("contextual heap inspection should preserve provider id",
                    DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, inspection.providerId());
            assertTrue("contextual heap inspection should observe the heap container",
                    inspection.file(DelosStorageInspection.PAGE_VOLUME_FILE).toString().contains("seg0"));
            assertEquals("using contextual diagnostics must not leave hidden mutable state", 0,
                    heapDiagnostics.runtimeStateCountForTesting());

            DelosStorageMetadataSnapshot snapshot = DelosStorageDiagnosticsRegistry.metadataSnapshot(
                    DelosStorageConsistencyTarget.heap(databaseDirectory, 0, heapContainerId));
            assertEquals("metadata snapshot should still use the heap provider",
                    DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, snapshot.providerId());
            assertTrue("metadata snapshot should remain read-only", snapshot.readOnly());
            assertTrue("metadata snapshot should observe heap storage bytes",
                    snapshot.statistics().observedStorageBytes() > 0L);
            assertEquals("registry metadata snapshot must not mutate the sampled provider", 0,
                    heapDiagnostics.runtimeStateCountForTesting());

            // The diagnostics assertions above are read-only, but Derby still considers the
            // connection to have an active transaction after catalog/container inspection.
            // End it explicitly so the test closes the embedded connection cleanly.
            connection.rollback();
        }
    }
}
