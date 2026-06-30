/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPageCacheTest

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
import java.sql.PreparedStatement;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL integration tests for the MVCC page-cache lifecycle boundary. */
public final class MvccSqlPageCacheTest extends MvccSqlTestSupport {
    public void testPageCacheTracksReadsWritesAndRehydratesOnReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-page-cache-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_page_cache_t "
                    + "(id int primary key, payload varchar(2600)) using delos_mvcc");
            for (int index = 1; index <= 6; index++) {
                insertPayload(connection, index, payload((char) ('a' + index), 900));
            }
            connection.commit();

            containerId = mvccContainerId(connection, "MVCC_PAGE_CACHE_T");
            assertTrue("MVCC page cache should hold at least one durable page",
                    diagnostics.pageCacheSizeForTesting(0, containerId) > 0L);
            assertTrue("MVCC append path should publish page writes through the cache boundary",
                    diagnostics.pageCacheWriteCountForTesting(0, containerId) > 0L);
            assertTrue("MVCC append path should reuse the cached last page instead of rereading every append",
                    diagnostics.pageCacheHitCountForTesting(0, containerId) > 0L);

            long hitsBeforeConsistency = diagnostics.pageCacheHitCountForTesting(0, containerId);
            diagnostics.assertConsistentForTesting(0, containerId);
            assertTrue("consistency check should read durable pages through the cache boundary",
                    diagnostics.pageCacheHitCountForTesting(0, containerId) > hitsBeforeConsistency);
            assertRows(connection,
                    "select id, length(payload) from mvcc_page_cache_t order by id",
                    "1|900",
                    "2|900",
                    "3|900",
                    "4|900",
                    "5|900",
                    "6|900");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_PAGE_CACHE_T");
            assertTrue("reopen should hydrate MVCC pages through the cache boundary",
                    diagnostics.pageCacheMissCountForTesting(0, reopenedContainerId) > 0L);
            assertTrue("reopened MVCC table should expose a populated page cache",
                    diagnostics.pageCacheSizeForTesting(0, reopenedContainerId) > 0L);
            long hitsBeforeConsistency = diagnostics.pageCacheHitCountForTesting(0, reopenedContainerId);
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            assertTrue("reopened consistency check should hit the hydrated page cache",
                    diagnostics.pageCacheHitCountForTesting(0, reopenedContainerId) > hitsBeforeConsistency);
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            assertRows(reopened,
                    "select id, length(payload) from mvcc_page_cache_t order by id",
                    "1|900",
                    "2|900",
                    "3|900",
                    "4|900",
                    "5|900",
                    "6|900");
        }
    }

    private static void insertPayload(Connection connection, int id, String payload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into mvcc_page_cache_t values (?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, payload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String payload(char seed, int length) {
        return String.valueOf(seed).repeat(length);
    }
}
