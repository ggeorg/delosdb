/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapStorageInspectorTest

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

import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageInspection;

/** SQL gate for read-only Derby-compatible heap storage inspection. */
public final class HeapStorageInspectorTest extends MvccSqlTestSupport {
    public void testHeapStorageInspectorExposesReadOnlyContainerSnapshot() throws Exception {
        String databaseName = databaseName("heap-storage-inspector-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_inspector_t "
                    + "(id int primary key, name varchar(32))");
            executeUpdate(connection, "insert into heap_inspector_t values (1, 'alpha')");
            connection.commit();

            long containerId = baseContainerId(connection, "HEAP_INSPECTOR_T", "heap");
            Path databaseDirectory = new File(databaseName).toPath();
            DelosStorageInspection inspection = DelosStorageDiagnosticsRegistry.inspectHeap(
                    databaseDirectory, 0, containerId);

            assertEquals(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, inspection.providerId());
            assertEquals(0, inspection.segment());
            assertEquals(containerId, inspection.containerId());
            assertEquals("HEAP_FILE_OBSERVED", inspection.checkpointStatus());
            assertTrue("expected at least one heap container page",
                    inspection.pageDiagnostics().pageCount() > 0L);
            assertEquals("expected clean heap file observation", 0,
                    inspection.consistencyDiagnostics().errorCount());

            Path containerFile = inspection.file(DelosStorageInspection.PAGE_VOLUME_FILE);
            assertNotNull("expected heap container file in storage inspection", containerFile);
            assertTrue("expected heap container file to exist: " + containerFile,
                    Files.isRegularFile(containerFile));
            assertTrue("expected heap container file to be non-empty: " + containerFile,
                    Files.size(containerFile) > 0L);
            assertFalse("inspection files should not be empty", inspection.files().isEmpty());
            connection.commit();
        }
    }
}
