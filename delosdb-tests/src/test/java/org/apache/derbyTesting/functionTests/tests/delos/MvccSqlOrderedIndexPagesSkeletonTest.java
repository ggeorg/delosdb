/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlOrderedIndexPagesSkeletonTest

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

import java.nio.file.Files;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL gate for the first shadow ordered MVCC index-page skeleton. */
public final class MvccSqlOrderedIndexPagesSkeletonTest extends MvccSqlTestSupport {
    public void testOrderedIndexPagesMirrorCommittedCandidateKeysThroughReopen() throws Exception {
        String databaseName = databaseName("mvcc-ordered-index-pages-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table ordered_index_t "
                    + "(id int primary key, name varchar(32), note varchar(32)) using delos_mvcc");
            connection.commit();
            containerId = mvccContainerId(connection, "ORDERED_INDEX_T");
            connection.rollback();

            executeUpdate(connection, "insert into ordered_index_t values (3, 'c', 'n3')");
            executeUpdate(connection, "insert into ordered_index_t values (1, 'a', 'n1')");
            executeUpdate(connection, "insert into ordered_index_t values (2, 'b', 'n2')");
            connection.commit();

            assertTrue("ordered index page sidecar path should be exposed diagnostically",
                    diagnostics.orderedIndexPagesFileForTesting(0, containerId) != null);
            assertTrue("ordered index page sidecar should exist after committed inserts",
                    Files.exists(diagnostics.orderedIndexPagesFileForTesting(0, containerId)));
            assertTrue("ordered index page skeleton should use durable pages",
                    diagnostics.orderedIndexPageCountForTesting(0, containerId) > 0L);
            assertEquals("three visible rows with three indexed values should create nine shadow entries",
                    9L, diagnostics.orderedIndexEntryCountForTesting(0, containerId));
            assertEquals("shadow ordered index keys should mirror candidate-index key count",
                    diagnostics.candidateIndexKeyCountForTesting(0, containerId),
                    diagnostics.orderedIndexDistinctKeyCountForTesting(0, containerId));
            assertTrue("ordered index rebuilds should track committed candidate-index rebuilds",
                    diagnostics.orderedIndexRebuildCountForTesting(0, containerId) > 0L);
            assertOrdered("ordered index summaries should be sorted by column/key/row",
                    diagnostics.orderedIndexEntrySummariesForTesting(0, containerId));

            executeUpdate(connection, "update ordered_index_t set name = 'z' where id = 2");
            executeUpdate(connection, "delete from ordered_index_t where id = 3");
            connection.commit();
            inPlaceCompressTable(connection, "ORDERED_INDEX_T");
            connection.commit();

            List<String> afterChange = diagnostics.orderedIndexEntrySummariesForTesting(0, containerId);
            assertEquals("two visible rows with three indexed values should leave six shadow entries",
                    6L, diagnostics.orderedIndexEntryCountForTesting(0, containerId));
            assertEquals("ordered index distinct keys should stay aligned with candidate index after update/delete/vacuum",
                    diagnostics.candidateIndexKeyCountForTesting(0, containerId),
                    diagnostics.orderedIndexDistinctKeyCountForTesting(0, containerId));
            assertOrdered("ordered index summaries should stay sorted after rebuild", afterChange);
            assertTrue("updated visible key should be present in shadow ordered pages",
                    containsSummary(afterChange, "col:1|key:z|"));
            assertFalse("obsolete updated key should be absent from shadow ordered pages",
                    containsSummary(afterChange, "col:1|key:b|"));
            assertFalse("deleted primary-key value should be absent from shadow ordered pages",
                    containsSummary(afterChange, "col:0|key:3|"));
            assertEquals("legacy snapshot read fallback must remain zero after ordered index rebuild",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot scan fallback must remain zero after ordered index rebuild",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            assertRows(connection, "select id, name, note from ordered_index_t order by id", "1|a|n1", "2|z|n2");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened, "select id, name, note from ordered_index_t order by id", "1|a|n1", "2|z|n2");
            assertTrue("reopened ordered index sidecar should remain durable",
                    Files.exists(diagnostics.orderedIndexPagesFileForTesting(0, containerId)));
            assertEquals("reopened ordered index entry count should match visible committed rows",
                    6L, diagnostics.orderedIndexEntryCountForTesting(0, containerId));
            assertEquals("reopened ordered index distinct keys should match candidate index",
                    diagnostics.candidateIndexKeyCountForTesting(0, containerId),
                    diagnostics.orderedIndexDistinctKeyCountForTesting(0, containerId));
            assertOrdered("reopened ordered index summaries should remain sorted",
                    diagnostics.orderedIndexEntrySummariesForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
        }
    }

    private static boolean containsSummary(List<String> summaries, String token) {
        for (String summary : summaries) {
            if (summary.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static void assertOrdered(String message, List<String> summaries) {
        List<String> sorted = new ArrayList<>(summaries);
        sorted.sort(String::compareTo);
        assertEquals(message, sorted, summaries);
    }
}
