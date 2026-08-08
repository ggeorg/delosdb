/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlTypedIndexSemanticsTest

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

/** Enduring SQL proof for typed MVCC ordered-index semantics. */
public final class MvccSqlTypedIndexSemanticsTest extends MvccSqlTestSupport {
    public void testTypedRangesNullsAndEnvelopeShapedTextSurviveReopen() throws Exception {
        String databaseName = databaseName("mvcc-typed-index-semantics-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table typed_index_t ("
                    + "id int primary key, n int, b bigint, amount decimal(10,2), marker varchar(32)) "
                    + "using delos_mvcc");
            executeUpdate(connection, "create index typed_index_n_idx on typed_index_t(n)");
            executeUpdate(connection, "create index typed_index_b_idx on typed_index_t(b)");
            executeUpdate(connection, "create index typed_index_amount_idx on typed_index_t(amount)");
            executeUpdate(connection, "create index typed_index_marker_idx on typed_index_t(marker)");
            executeUpdate(connection, "insert into typed_index_t values (1, 1, 1, 1.00, 'I|10')");
            executeUpdate(connection, "insert into typed_index_t values (2, 2, 2, 2.00, 'I|2')");
            executeUpdate(connection, "insert into typed_index_t values (3, 10, 10, 10.00, 'D|3')");
            executeUpdate(connection, "insert into typed_index_t values (4, -5, -5, -5.00, 'minus')");
            executeUpdate(connection, "insert into typed_index_t values (5, null, null, null, 'null-row')");
            connection.commit();

            assertTypedQueries(connection);
        }

        shutdownDatabase(databaseName);
        try (Connection reopened = openDatabase(databaseName, false)) {
            assertTypedQueries(reopened);
        }
    }

    private static void assertTypedQueries(Connection connection) throws Exception {
        assertRows(connection,
                "select id, n from typed_index_t --DERBY-PROPERTIES index=typed_index_n_idx\n"
                        + "where n >= 2 and n <= 10 order by n, id",
                "2|2", "3|10");
        assertRows(connection,
                "select id, b from typed_index_t --DERBY-PROPERTIES index=typed_index_b_idx\n"
                        + "where b >= 2 and b <= 10 order by b, id",
                "2|2", "3|10");
        assertRows(connection,
                "select id, amount from typed_index_t --DERBY-PROPERTIES index=typed_index_amount_idx\n"
                        + "where amount >= 2.00 and amount <= 10.00 order by amount, id",
                "2|2.00", "3|10.00");
        assertRows(connection,
                "select id, marker from typed_index_t --DERBY-PROPERTIES index=typed_index_marker_idx\n"
                        + "where marker >= 'I|10' and marker <= 'I|2' order by marker, id",
                "1|I|10", "2|I|2");
        assertRows(connection,
                "select id, marker from typed_index_t --DERBY-PROPERTIES index=typed_index_n_idx\n"
                        + "where n is null order by id",
                "5|null-row");
    }
}
