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
import org.apache.derby.iapi.store.types.DelosStoragePageCacheDiagnostics;

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
            DelosStoragePageCacheDiagnostics cache = diagnostics.pageCacheDiagnosticsForTesting(0, containerId);
            assertTrue("MVCC page cache should hold at least one durable page", cache.size() > 0L);
            assertTrue("MVCC append path should publish page writes through the cache boundary",
                    cache.writeCount() > 0L);
            assertTrue("MVCC append path should reuse the cached last page instead of rereading every append",
                    cache.hitCount() > 0L);
            assertTrue("MVCC page reads should use scoped page-cache pins", cache.pinCount() > 0L);
            assertEquals("scoped MVCC page-cache pins should be released after reads",
                    cache.pinCount(), cache.unpinCount());
            assertEquals("MVCC page cache should not leave pages pinned after SQL work",
                    0L, cache.pinnedPageCount());
            assertEquals("write-through MVCC page cache should have an empty dirty list after commit",
                    0L, cache.dirtyPageCount());
            assertEquals("write-through MVCC page cache should have an empty flush list after commit",
                    0L, cache.flushListPageCount());
            assertTrue("MVCC page cache should flush dirty writes through the cache boundary",
                    cache.flushCount() > 0L);
            assertTrue("MVCC page cache should advance page generations on cached writes",
                    cache.lastPageGeneration() > 0L);

            long hitsBeforeConsistency = cache.hitCount();
            diagnostics.assertConsistentForTesting(0, containerId);
            assertTrue("consistency check should read durable pages through the cache boundary",
                    diagnostics.pageCacheDiagnosticsForTesting(0, containerId).hitCount() > hitsBeforeConsistency);
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
            DelosStoragePageCacheDiagnostics cache = diagnostics.pageCacheDiagnosticsForTesting(0, reopenedContainerId);
            assertTrue("reopen should hydrate MVCC pages through the cache boundary", cache.missCount() > 0L);
            assertTrue("reopened MVCC table should expose a populated page cache", cache.size() > 0L);
            long hitsBeforeConsistency = cache.hitCount();
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            assertTrue("reopened consistency check should hit the hydrated page cache",
                    diagnostics.pageCacheDiagnosticsForTesting(0, reopenedContainerId).hitCount() > hitsBeforeConsistency);
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

    public void testBoundedPageCacheEvictsAndReloadsPages() throws Exception {
        String databaseName = databaseName("mvcc-sql-page-cache-eviction-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_page_cache_t "
                    + "(id int primary key, payload varchar(7200)) using delos_mvcc");
            for (int index = 1; index <= 150; index++) {
                insertPayload(connection, index, payload((char) ('a' + (index % 26)), 7000));
            }
            connection.commit();

            containerId = mvccContainerId(connection, "MVCC_PAGE_CACHE_T");
            DelosStoragePageCacheDiagnostics cache = diagnostics.pageCacheDiagnosticsForTesting(0, containerId);
            assertTrue("MVCC page cache should expose a bounded capacity", cache.maxPageCount() > 0L);
            assertTrue("MVCC page cache should not grow beyond the bounded capacity",
                    cache.size() <= cache.maxPageCount());
            assertTrue("multi-page MVCC workload should evict old page images", cache.evictionCount() > 0L);
            assertTrue("multi-page MVCC workload should pin pages while reading through the cache",
                    cache.pinCount() > 0L);
            assertEquals("bounded MVCC cache should release read pins after operations",
                    cache.pinCount(), cache.unpinCount());
            assertEquals("bounded MVCC cache should not leave pages dirty after write-through flushes",
                    0L, cache.dirtyPageCount());
            assertTrue("bounded MVCC cache should flush dirty writes before returning from commit",
                    cache.flushCount() > 0L);

            assertRows(connection,
                    "select id, length(payload) from mvcc_page_cache_t where id in (1, 75, 150) order by id",
                    "1|7000",
                    "75|7000",
                    "150|7000");
            long missesBeforeConsistency = diagnostics.pageCacheDiagnosticsForTesting(0, containerId).missCount();
            diagnostics.assertConsistentForTesting(0, containerId);
            assertTrue("durable consistency scan should reload evicted MVCC pages on demand",
                    diagnostics.pageCacheDiagnosticsForTesting(0, containerId).missCount() > missesBeforeConsistency);
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_PAGE_CACHE_T");
            DelosStoragePageCacheDiagnostics cache = diagnostics.pageCacheDiagnosticsForTesting(0, reopenedContainerId);
            assertTrue("reopened MVCC page cache should expose a bounded capacity", cache.maxPageCount() > 0L);
            assertRows(reopened,
                    "select id, length(payload) from mvcc_page_cache_t where id in (1, 75, 150) order by id",
                    "1|7000",
                    "75|7000",
                    "150|7000");
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            cache = diagnostics.pageCacheDiagnosticsForTesting(0, reopenedContainerId);
            assertTrue("reopened MVCC page cache should not grow beyond the bounded capacity",
                    cache.size() <= cache.maxPageCount());
            assertTrue("reopened bounded MVCC cache should evict while scanning durable pages",
                    cache.evictionCount() > 0L);
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
