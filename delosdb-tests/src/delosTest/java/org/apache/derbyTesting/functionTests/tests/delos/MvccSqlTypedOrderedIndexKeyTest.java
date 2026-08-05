/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlTypedOrderedIndexKeyTest

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

/** SQL gate proving ordered MVCC index keys use typed comparison semantics. */
public final class MvccSqlTypedOrderedIndexKeyTest extends MvccSqlTestSupport {
    public void testIntegerRangeUsesNumericOrderedIndexKeySemantics() throws Exception {
        String databaseName = databaseName("mvcc-typed-ordered-index-key-int-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table typed_ordered_key_int_t "
                    + "(id int primary key, n int, payload varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into typed_ordered_key_int_t values (1, 1, 'payload-1')");
            executeUpdate(connection, "insert into typed_ordered_key_int_t values (2, 2, 'payload-2')");
            executeUpdate(connection, "insert into typed_ordered_key_int_t values (3, 10, 'payload-10')");
            executeUpdate(connection, "insert into typed_ordered_key_int_t values (4, -5, 'payload-minus-5')");
            connection.commit();

            containerId = mvccContainerId(connection, "TYPED_ORDERED_KEY_INT_T");
            assertTrue("typed ordered index should have durable entries before numeric range lookup",
                    diagnostics.orderedIndexEntryCountForTesting(0, containerId) > 0L);

            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            long fallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);
            diagnostics.resetCandidateIndexCountersForTesting();
            diagnostics.resetScanCountersForTesting();

            assertRows(connection,
                    "select id, n from typed_ordered_key_int_t where n >= 2 and n <= 10",
                    "2|2", "3|10");

            assertTrue("numeric range should consult ordered index pages",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) > lookupBefore);
            assertEquals("typed numeric range should not fall back to candidate/full scan authority",
                    fallbackBefore, diagnostics.orderedIndexFallbackCountForTesting(0, containerId));
            assertTrue("typed numeric range should feed row-id point reads",
                    diagnostics.rowIdFastPathReadCountForTesting() > 0);
            assertEquals("numeric ordered lookup must not reject 10 as lexically outside 2..10",
                    0, diagnostics.qualifierRejectCountForTesting());
            diagnostics.assertConsistentForTesting(0, containerId);

            lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            fallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);
            diagnostics.resetScanCountersForTesting();
            assertRows(connection,
                    "select id, n from typed_ordered_key_int_t where n > -10 and n < 2",
                    "4|-5", "1|1");
            assertTrue("negative numeric bounds should also use ordered index pages",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) > lookupBefore);
            assertEquals("negative numeric bounds should not fall back",
                    fallbackBefore, diagnostics.orderedIndexFallbackCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.rollback();
        }
    }

    public void testDecimalAndBigintRangesUseTypedOrderedIndexKeySemantics() throws Exception {
        String databaseName = databaseName("mvcc-typed-ordered-index-key-decimal-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table typed_ordered_key_decimal_t "
                    + "(id int primary key, b bigint, amount decimal(10,2), payload varchar(32)) using delos_mvcc");
            executeUpdate(connection,
                    "insert into typed_ordered_key_decimal_t values (1, 1, 1.00, 'payload-1')");
            executeUpdate(connection,
                    "insert into typed_ordered_key_decimal_t values (2, 2, 2.00, 'payload-2')");
            executeUpdate(connection,
                    "insert into typed_ordered_key_decimal_t values (3, 10, 10.00, 'payload-10')");
            executeUpdate(connection,
                    "insert into typed_ordered_key_decimal_t values (4, -5, -5.00, 'payload-minus-5')");
            connection.commit();

            containerId = mvccContainerId(connection, "TYPED_ORDERED_KEY_DECIMAL_T");
            long fallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);
            diagnostics.resetCandidateIndexCountersForTesting();
            diagnostics.resetScanCountersForTesting();

            assertRows(connection,
                    "select id, b from typed_ordered_key_decimal_t where b >= 2 and b <= 10",
                    "2|2", "3|10");
            assertEquals("typed bigint range should not fall back",
                    fallbackBefore, diagnostics.orderedIndexFallbackCountForTesting(0, containerId));
            assertTrue("typed bigint range should feed row-id point reads",
                    diagnostics.rowIdFastPathReadCountForTesting() > 0);

            fallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);
            diagnostics.resetScanCountersForTesting();
            assertRows(connection,
                    "select id, amount from typed_ordered_key_decimal_t "
                            + "where amount >= 2.00 and amount <= 10.00",
                    "2|2.00", "3|10.00");
            assertEquals("typed decimal range should not fall back",
                    fallbackBefore, diagnostics.orderedIndexFallbackCountForTesting(0, containerId));
            assertTrue("typed decimal range should feed row-id point reads",
                    diagnostics.rowIdFastPathReadCountForTesting() > 0);
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.rollback();
        }
    }

    public void testTextValuesThatResembleLegacyTypedEnvelopeUseTextSemantics() throws Exception {
        String databaseName = databaseName("mvcc-typed-ordered-index-key-text-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table typed_ordered_key_text_t "
                    + "(id int primary key, marker varchar(16), payload varchar(32)) using delos_mvcc");
            executeUpdate(connection,
                    "insert into typed_ordered_key_text_t values (1, 'I|10', 'payload-10')");
            executeUpdate(connection,
                    "insert into typed_ordered_key_text_t values (2, 'I|2', 'payload-2')");
            executeUpdate(connection,
                    "insert into typed_ordered_key_text_t values (3, 'D|3', 'payload-3')");
            connection.commit();

            containerId = mvccContainerId(connection, "TYPED_ORDERED_KEY_TEXT_T");
            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            long fallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);
            diagnostics.resetCandidateIndexCountersForTesting();
            diagnostics.resetScanCountersForTesting();

            assertRows(connection,
                    "select id, marker from typed_ordered_key_text_t "
                            + "where marker >= 'I|10' and marker <= 'I|2' order by marker",
                    "1|I|10", "2|I|2");

            assertTrue("prefix-shaped text range should still consult ordered index pages",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) > lookupBefore);
            assertEquals("prefix-shaped text keys should not be mistaken for legacy numeric envelopes",
                    fallbackBefore, diagnostics.orderedIndexFallbackCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.rollback();
        }
    }


    public void testNullValuesKeepTypedOrderedIndexKeySemanticsThroughReopen() throws Exception {
        String databaseName = databaseName("mvcc-typed-ordered-index-key-null-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table typed_ordered_key_null_t "
                    + "(id int primary key, n int, label varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into typed_ordered_key_null_t values (1, null, 'null-a')");
            executeUpdate(connection, "insert into typed_ordered_key_null_t values (2, 2, 'two')");
            executeUpdate(connection, "insert into typed_ordered_key_null_t values (3, null, 'null-b')");
            executeUpdate(connection, "insert into typed_ordered_key_null_t values (4, 10, 'ten')");
            connection.commit();

            containerId = mvccContainerId(connection, "TYPED_ORDERED_KEY_NULL_T");
            assertTrue("typed NULL ordered-index key should be present in durable summaries",
                    containsOrderedIndexSummary(
                            diagnostics.orderedIndexEntrySummariesForTesting(0, containerId),
                            "col:1|key:|"));
            diagnostics.assertConsistentForTesting(0, containerId);

            assertRows(connection,
                    "select id, label from typed_ordered_key_null_t where n is null order by id",
                    "1|null-a", "3|null-b");

            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            long fallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);
            diagnostics.resetCandidateIndexCountersForTesting();
            diagnostics.resetScanCountersForTesting();
            assertRows(connection,
                    "select id, n from typed_ordered_key_null_t where n >= 2 and n <= 10",
                    "2|2", "4|10");
            assertTrue("non-null typed range should still use ordered pages after NULL-key rebuild",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) > lookupBefore);
            assertEquals("NULL-key rebuild must not force ordered-index fallback for supported typed ranges",
                    fallbackBefore, diagnostics.orderedIndexFallbackCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, label from typed_ordered_key_null_t where n is null order by id",
                    "1|null-a", "3|null-b");
            assertTrue("reopened ordered-index summaries should preserve typed NULL key entries",
                    containsOrderedIndexSummary(
                            diagnostics.orderedIndexEntrySummariesForTesting(0, containerId),
                            "col:1|key:|"));
            diagnostics.assertConsistentForTesting(0, containerId);
        }
    }

    private static boolean containsOrderedIndexSummary(List<String> summaries, String token) {
        for (String summary : summaries) {
            if (summary.contains(token)) {
                return true;
            }
        }
        return false;
    }

}
