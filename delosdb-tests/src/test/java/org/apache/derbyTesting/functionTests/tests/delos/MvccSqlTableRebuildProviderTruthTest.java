/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlTableRebuildProviderTruthTest

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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.derby.shared.common.error.StandardException;

/** SQL proof that inherited table rebuilds cannot silently convert MVCC tables to heap. */
public final class MvccSqlTableRebuildProviderTruthTest extends MvccSqlTestSupport {
    private static final String NOT_IMPLEMENTED_SQLSTATE = "0A000";
    private static final String ROUTINE_EXCEPTION_SQLSTATE = "38000";

    public void testUnsupportedRebuildsRejectBeforeChangingMvccProviderOrData() throws Exception {
        String databaseName = databaseName("mvcc-table-rebuild-provider-truth-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_rebuild_compress ("
                    + "id int primary key, payload varchar(40)) using delos_mvcc");
            executeUpdate(connection, "create table mvcc_rebuild_truncate ("
                    + "id int primary key, payload varchar(40)) using delos_mvcc");
            executeUpdate(connection, "create table mvcc_rebuild_drop ("
                    + "id int primary key, keep_value int, drop_value int) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_rebuild_compress values (1, 'compress-row')");
            executeUpdate(connection, "insert into mvcc_rebuild_truncate values (2, 'truncate-row')");
            executeUpdate(connection, "insert into mvcc_rebuild_drop values (3, 30, 300)");
            connection.commit();

            assertRoutineRejected(connection,
                    "call SYSCS_UTIL.SYSCS_COMPRESS_TABLE('APP', 'MVCC_REBUILD_COMPRESS', 1)");
            assertDirectlyRejected(connection, "truncate table mvcc_rebuild_truncate");
            assertDirectlyRejected(connection, "alter table mvcc_rebuild_drop drop column drop_value");

            assertMvccStorageProvider(connection, "MVCC_REBUILD_COMPRESS");
            assertMvccStorageProvider(connection, "MVCC_REBUILD_TRUNCATE");
            assertMvccStorageProvider(connection, "MVCC_REBUILD_DROP");
            assertRows(connection, "select id, payload from mvcc_rebuild_compress", "1|compress-row");
            assertRows(connection, "select id, payload from mvcc_rebuild_truncate", "2|truncate-row");
            assertRows(connection, "select id, keep_value, drop_value from mvcc_rebuild_drop", "3|30|300");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertMvccStorageProvider(reopened, "MVCC_REBUILD_COMPRESS");
            assertMvccStorageProvider(reopened, "MVCC_REBUILD_TRUNCATE");
            assertMvccStorageProvider(reopened, "MVCC_REBUILD_DROP");
            assertRows(reopened, "select id, payload from mvcc_rebuild_compress", "1|compress-row");
            assertRows(reopened, "select id, payload from mvcc_rebuild_truncate", "2|truncate-row");
            assertRows(reopened, "select id, keep_value, drop_value from mvcc_rebuild_drop", "3|30|300");
        }
    }

    private static void assertDirectlyRejected(Connection connection, String sql) throws SQLException {
        try {
            executeUpdate(connection, sql);
            fail("Expected unsupported MVCC table rebuild: " + sql);
        } catch (SQLException e) {
            assertEquals(NOT_IMPLEMENTED_SQLSTATE, e.getSQLState());
            connection.rollback();
        }
    }

    private static void assertRoutineRejected(Connection connection, String sql) throws SQLException {
        try {
            executeUpdate(connection, sql);
            fail("Expected unsupported MVCC table rebuild routine: " + sql);
        } catch (SQLException e) {
            assertEquals(ROUTINE_EXCEPTION_SQLSTATE, e.getSQLState());
            assertTrue(
                    "SYSCS_COMPRESS_TABLE must retain the underlying 0A000 rebuild rejection",
                    containsSqlState(e, NOT_IMPLEMENTED_SQLSTATE));
            connection.rollback();
        }
    }

    private static boolean containsSqlState(Throwable throwable, String expectedSqlState) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return containsSqlState(throwable, expectedSqlState, visited);
    }

    private static boolean containsSqlState(
            Throwable throwable,
            String expectedSqlState,
            Set<Throwable> visited) {
        if (throwable == null || !visited.add(throwable)) {
            return false;
        }

        if (throwable instanceof SQLException) {
            SQLException sqlException = (SQLException) throwable;
            if (expectedSqlState.equals(sqlException.getSQLState())) {
                return true;
            }
            if (containsSqlState(sqlException.getNextException(), expectedSqlState, visited)) {
                return true;
            }
        } else if (throwable instanceof StandardException
                && expectedSqlState.equals(((StandardException) throwable).getSQLState())) {
            return true;
        }

        return containsSqlState(throwable.getCause(), expectedSqlState, visited);
    }
}
