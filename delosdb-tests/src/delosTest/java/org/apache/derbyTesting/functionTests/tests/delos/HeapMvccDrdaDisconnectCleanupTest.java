/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapMvccDrdaDisconnectCleanupTest

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
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import junit.framework.Test;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.BaseTestSuite;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * DRDA connection-abort cleanup proof for matched heap and delos_mvcc
 * transactions. An aborted network connection must roll back uncommitted
 * changes and release all server-side transaction state before another client
 * continues work.
 */
public final class HeapMvccDrdaDisconnectCleanupTest extends BaseJDBCTestCase {
    private static final String HEAP_TABLE = "drda_disconnect_heap";
    private static final String MVCC_TABLE = "drda_disconnect_mvcc";

    public HeapMvccDrdaDisconnectCleanupTest(String name) {
        super(name);
    }

    public static Test suite() {
        BaseTestSuite suite = new BaseTestSuite(
                HeapMvccDrdaDisconnectCleanupTest.class);
        Test test = new CleanDatabaseTestSetup(suite);
        test = HeapMvccDrdaFailureTestSupport.withBoundedCleanupLockWaits(test);
        return TestConfiguration.clientServerDecorator(test);
    }

    public void testAbortRollsBackAndReleasesHeapAndMvccTransactions()
            throws Exception {
        HeapMvccDrdaFailureTestSupport.assertNetworkClient(
                getTestConfiguration());

        try (Connection setup = openDefaultConnection()) {
            setup.setAutoCommit(false);
            HeapMvccDrdaFailureTestSupport.createMarkerTable(
                    setup, HEAP_TABLE, false, 1);
            HeapMvccDrdaFailureTestSupport.createMarkerTable(
                    setup, MVCC_TABLE, true, 1);
            setup.commit();
        }

        assertAbortRollsBackAndReleases(HEAP_TABLE);
        assertAbortRollsBackAndReleases(MVCC_TABLE);

        DelosStorageDiagnostics diagnostics = MvccSqlTestSupport.mvccDiagnostics(
                getTestConfiguration().getDatabasePath(
                        getTestConfiguration().getDefaultDatabaseName()));
        try (Connection connection = openDefaultConnection()) {
            connection.setAutoCommit(false);
            long containerId = MvccSqlTestSupport.mvccContainerId(
                    connection, MVCC_TABLE.toUpperCase(java.util.Locale.ROOT));
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.rollback();
        }
    }

    private void assertAbortRollsBackAndReleases(String table) throws Exception {
        Connection victim = openDefaultConnection();
        victim.setAutoCommit(false);
        try {
            HeapMvccDrdaFailureTestSupport.executeUpdate(victim,
                    "update " + table + " set marker = 99 where id = 1");
            HeapMvccDrdaFailureTestSupport.executeUpdate(victim,
                    "insert into " + table + " values (2, 99)");
            HeapMvccDrdaFailureTestSupport.assertMarkerRow(victim, table, 1, 99);
            HeapMvccDrdaFailureTestSupport.assertMarkerRow(victim, table, 2, 99);

            ExecutorService abortExecutor = Executors.newSingleThreadExecutor();
            try {
                victim.abort(abortExecutor);
            } finally {
                abortExecutor.shutdown();
                assertTrue("connection abort did not complete for " + table,
                        abortExecutor.awaitTermination(10, TimeUnit.SECONDS));
            }
            assertTrue("aborted client connection should be closed for " + table,
                    victim.isClosed());

            HeapMvccDrdaFailureTestSupport.awaitRollbackAndCommit(
                    this::openDefaultConnection, table, 2);

        } finally {
            if (!victim.isClosed()) {
                victim.close();
            }
        }
    }

}
