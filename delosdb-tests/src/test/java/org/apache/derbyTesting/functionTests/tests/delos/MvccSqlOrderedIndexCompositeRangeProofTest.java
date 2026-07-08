/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlOrderedIndexCompositeRangeProofTest

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
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL proof for composite predicates over current single-column ordered MVCC index pages. */
public final class MvccSqlOrderedIndexCompositeRangeProofTest extends MvccSqlTestSupport {
    public void testCompositePredicateUsesSafeSingleColumnNarrowingAndQualifierFiltering() throws Exception {
        String databaseName = databaseName("mvcc-ordered-index-composite-predicate-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            createAndPopulateCompositeTable(connection, "ORDERED_INDEX_COMPOSITE_T");
            connection.commit();

            containerId = mvccContainerId(connection, "ORDERED_INDEX_COMPOSITE_T");
            diagnostics.resetScanCountersForTesting();
            diagnostics.resetStoragePathDiagnosticsForTesting();

            assertRows(connection,
                    "select id, payload from ordered_index_composite_t "
                            + "where category = 'B' and score >= 10 and score <= 20 order by id",
                    "3|b-15");

            List<String> lines = diagnostics.storagePathDiagnosticLinesForTesting();
            assertContainsStoragePath(lines,
                    "storagePath=MVCC_ORDERED_EQUALITY_LOOKUP state=CHOSEN", containerId);
            assertContainsStoragePath(lines,
                    "storagePath=MVCC_ROW_ID_LOOKUP state=CHOSEN", containerId);
            assertFalse("single-column equality narrowing should not be reported as compatibility fallback: "
                            + lines,
                    containsStoragePath(lines, "storagePath=EXPLICIT_COMPATIBILITY_FALLBACK state=FALLBACK"));
            assertTrue("the residual score range must still be enforced by Derby qualifier filtering",
                    diagnostics.qualifierRejectCountForTesting() > 0);
            connection.rollback();
        }

        shutdownDatabase(databaseName);
    }

    public void testMultiColumnRangePredicateRecordsRejectedOrderedRangeAndFallsBack() throws Exception {
        String databaseName = databaseName("mvcc-ordered-index-multicolumn-range-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            createAndPopulateCompositeTable(connection, "ORDERED_INDEX_MULTIRANGE_T");
            connection.commit();

            containerId = mvccContainerId(connection, "ORDERED_INDEX_MULTIRANGE_T");
            diagnostics.resetScanCountersForTesting();
            diagnostics.resetStoragePathDiagnosticsForTesting();

            assertRows(connection,
                    "select id, category, score from ordered_index_multirange_t "
                            + "where score >= 10 and id <= 4 order by id",
                    "2|A|20", "3|B|15", "4|B|25");

            List<String> lines = diagnostics.storagePathDiagnosticLinesForTesting();
            assertContainsStoragePath(lines,
                    "storagePath=MVCC_ORDERED_RANGE_SCAN state=REJECTED", containerId);
            assertContainsStoragePath(lines,
                    "storagePath=EXPLICIT_COMPATIBILITY_FALLBACK state=FALLBACK", containerId);
            assertContainsStoragePath(lines,
                    "storagePath=MVCC_FULL_SCAN state=CHOSEN", containerId);
            assertFalse("multi-column ranges must not claim a single-column ordered range shortcut: "
                            + lines,
                    containsStoragePath(lines, "storagePath=MVCC_ORDERED_RANGE_SCAN state=CHOSEN"));
            connection.rollback();
        }

        shutdownDatabase(databaseName);
    }

    private static void createAndPopulateCompositeTable(Connection connection, String tableName) throws Exception {
        String sqlName = tableName.toLowerCase();
        executeUpdate(connection, "create table " + sqlName
                + " (id int primary key, category varchar(8), score int, payload varchar(32)) using delos_mvcc");
        executeUpdate(connection, "insert into " + sqlName + " values (1, 'A', 5, 'a-5')");
        executeUpdate(connection, "insert into " + sqlName + " values (2, 'A', 20, 'a-20')");
        executeUpdate(connection, "insert into " + sqlName + " values (3, 'B', 15, 'b-15')");
        executeUpdate(connection, "insert into " + sqlName + " values (4, 'B', 25, 'b-25')");
        executeUpdate(connection, "insert into " + sqlName + " values (5, 'C', 30, 'c-30')");
    }

    private static void assertContainsStoragePath(List<String> lines, String marker, long containerId) {
        assertTrue("expected storage path diagnostic marker " + marker + " for container "
                        + containerId + " in " + lines,
                containsStoragePath(lines, marker, "container=" + containerId));
    }

    private static boolean containsStoragePath(List<String> lines, String marker) {
        for (String line : lines) {
            if (line.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsStoragePath(List<String> lines, String marker, String secondMarker) {
        for (String line : lines) {
            if (line.contains(marker) && line.contains(secondMarker)) {
                return true;
            }
        }
        return false;
    }
}
