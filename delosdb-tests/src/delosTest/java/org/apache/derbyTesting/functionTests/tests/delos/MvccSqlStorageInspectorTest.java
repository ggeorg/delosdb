/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlStorageInspectorTest

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

import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageInspection;

/** SQL gate for the provider-neutral DelosDB storage-inspection surface. */
public final class MvccSqlStorageInspectorTest extends MvccSqlTestSupport {
    public void testMvccStorageInspectorExposesStableProviderNeutralSnapshot() throws Exception {
        String databaseName = databaseName("mvcc-storage-inspector-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table inspector_t "
                    + "(id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into inspector_t values (1, 'alpha')");
            connection.commit();

            long containerId = mvccContainerId(connection, "INSPECTOR_T");
            DelosStorageInspection inspection = DelosStorageDiagnosticsRegistry.inspectMvcc(databasePath(databaseName), 0, containerId);

            assertEquals(DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID, inspection.providerId());
            assertEquals(0, inspection.segment());
            assertEquals(containerId, inspection.containerId());
            assertTrue("expected at least one MVCC page", inspection.pageDiagnostics().pageCount() > 0L);
            assertEquals("expected one logical row", 1, inspection.pageDiagnostics().logicalRowCount());
            assertEquals("expected clean MVCC consistency snapshot", 0,
                    inspection.consistencyDiagnostics().errorCount());
            assertNotNull("expected page-volume file in storage inspection",
                    inspection.file(DelosStorageInspection.PAGE_VOLUME_FILE));
            assertNotNull("expected checkpoint file in storage inspection",
                    inspection.file(DelosStorageInspection.CHECKPOINT_FILE));
            assertFalse("inspection files should not be empty", inspection.files().isEmpty());
            connection.commit();
        }
    }
}
