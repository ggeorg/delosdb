/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlTransactionalDdlTest

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;

/** Transactional CREATE/DROP ownership and DDL-plus-MVCC decision proofs. */
public final class MvccSqlTransactionalDdlTest extends MvccSqlTestSupport {
    public void testCreateMvccRollbackRemovesRawStoreConglomerateAcrossReopen() throws Exception {
        String databaseName = databaseName("mvcc-transactional-create-rollback-db");
        long containerId;
        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table create_rollback_t (id int primary key, value int) using delos_mvcc");
            containerId = mvccContainerId(connection, "CREATE_ROLLBACK_T");
            connection.rollback();
            assertTableMissing(connection, "create_rollback_t");
            assertConglomerateMissing(connection, containerId);
        }

        shutdownDatabase(databaseName);
        try (Connection reopened = openDatabase(databaseName, false)) {
            assertTableMissing(reopened, "create_rollback_t");
            assertConglomerateMissing(reopened, containerId);
        }
    }

    public void testDropMvccRollbackPreservesRowsAcrossReopen() throws Exception {
        String databaseName = databaseName("mvcc-transactional-drop-rollback-db");
        long containerId;
        try (Connection connection = openDatabase(databaseName, true)) {
            executeUpdate(connection,
                    "create table drop_rollback_t (id int primary key, value int) using delos_mvcc");
            executeUpdate(connection, "insert into drop_rollback_t values (1, 10), (2, 20)");
            containerId = mvccContainerId(connection, "DROP_ROLLBACK_T");

            connection.setAutoCommit(false);
            executeUpdate(connection, "drop table drop_rollback_t");
            connection.rollback();
            assertRows(connection,
                    "select id, value from drop_rollback_t order by id",
                    "1|10", "2|20");
            assertEquals(containerId, mvccContainerId(connection, "DROP_ROLLBACK_T"));
            assertConglomeratePresent(connection, containerId);
            connection.rollback();
        }

        shutdownDatabase(databaseName);
        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, value from drop_rollback_t order by id",
                    "1|10", "2|20");
            assertEquals(containerId, mvccContainerId(reopened, "DROP_ROLLBACK_T"));
            assertConglomeratePresent(reopened, containerId);
        }
    }

    public void testDropMvccCommitRetiresRawStoreConglomerateAcrossReopen() throws Exception {
        String databaseName = databaseName("mvcc-transactional-drop-commit-db");
        long containerId;
        try (Connection connection = openDatabase(databaseName, true)) {
            executeUpdate(connection,
                    "create table drop_commit_t (id int primary key, value int) using delos_mvcc");
            executeUpdate(connection, "insert into drop_commit_t values (1, 10)");
            containerId = mvccContainerId(connection, "DROP_COMMIT_T");

            connection.setAutoCommit(false);
            executeUpdate(connection, "drop table drop_commit_t");
            connection.commit();
            assertTableMissing(connection, "drop_commit_t");
            assertConglomerateMissing(connection, containerId);
        }

        shutdownDatabase(databaseName);
        try (Connection reopened = openDatabase(databaseName, false)) {
            assertTableMissing(reopened, "drop_commit_t");
            assertConglomerateMissing(reopened, containerId);
        }
    }

    public void testMvccIndexCreateAndDropRollbackAcrossReopen() throws Exception {
        String databaseName = databaseName("mvcc-transactional-index-rollback-db");
        try (Connection connection = openDatabase(databaseName, true)) {
            executeUpdate(connection,
                    "create table index_rollback_t (id int primary key, value int) using delos_mvcc");
            executeUpdate(connection, "insert into index_rollback_t values (1, 10), (2, 20)");

            connection.setAutoCommit(false);
            executeUpdate(connection, "create index index_rollback_value on index_rollback_t(value)");
            connection.rollback();
            assertIndexCount(connection, "INDEX_ROLLBACK_VALUE", 0);

            executeUpdate(connection, "create index index_rollback_value on index_rollback_t(value)");
            connection.commit();
            assertIndexCount(connection, "INDEX_ROLLBACK_VALUE", 1);

            executeUpdate(connection, "drop index index_rollback_value");
            connection.rollback();
            assertIndexCount(connection, "INDEX_ROLLBACK_VALUE", 1);
            assertRows(connection,
                    "select id, value from index_rollback_t where value >= 10 order by value",
                    "1|10", "2|20");
            connection.rollback();
        }

        shutdownDatabase(databaseName);
        try (Connection reopened = openDatabase(databaseName, false)) {
            assertIndexCount(reopened, "INDEX_ROLLBACK_VALUE", 1);
            assertRows(reopened,
                    "select id, value from index_rollback_t where value >= 10 order by value",
                    "1|10", "2|20");
        }
    }

    public void testMvccDmlAndTransactionalDdlShareOneRawDecision() throws Exception {
        String databaseName = databaseName("mvcc-transactional-ddl-mixed-decision-db");
        try (Connection connection = openDatabase(databaseName, true)) {
            executeUpdate(connection,
                    "create table ddl_witness_t (id int primary key, value int) using delos_mvcc");

            connection.setAutoCommit(false);
            executeUpdate(connection, "create table committed_heap_ddl (id int primary key)");
            executeUpdate(connection, "insert into ddl_witness_t values (1, 10)");
            connection.commit();
            assertRows(connection, "select id from committed_heap_ddl", new String[0]);
            assertRows(connection, "select id, value from ddl_witness_t order by id", "1|10");
            connection.rollback();

            executeUpdate(connection, "create table rolled_back_heap_ddl (id int primary key)");
            executeUpdate(connection, "insert into ddl_witness_t values (2, 20)");
            connection.rollback();
            assertTableMissing(connection, "rolled_back_heap_ddl");
            assertRows(connection, "select id, value from ddl_witness_t order by id", "1|10");
            connection.rollback();
        }

        shutdownDatabase(databaseName);
        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened, "select id from committed_heap_ddl", new String[0]);
            assertTableMissing(reopened, "rolled_back_heap_ddl");
            assertRows(reopened, "select id, value from ddl_witness_t order by id", "1|10");
        }
    }

    public void testCreateAndDropOrderWithSameTableDml() throws Exception {
        String databaseName = databaseName("mvcc-transactional-ddl-same-table-dml-db");
        long containerId;
        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table lifecycle_dml_t "
                            + "(id int primary key, value int) using delos_mvcc");
            containerId = mvccContainerId(connection, "LIFECYCLE_DML_T");
            executeUpdate(connection, "insert into lifecycle_dml_t values (1, 10)");
            connection.commit();
            assertRows(connection,
                    "select id, value from lifecycle_dml_t order by id",
                    "1|10");
            connection.rollback();

            executeUpdate(connection, "insert into lifecycle_dml_t values (2, 20)");
            executeUpdate(connection, "drop table lifecycle_dml_t");
            connection.commit();
            assertTableMissing(connection, "lifecycle_dml_t");
            assertConglomerateMissing(connection, containerId);
        }

        shutdownDatabase(databaseName);
        try (Connection reopened = openDatabase(databaseName, false)) {
            assertTableMissing(reopened, "lifecycle_dml_t");
            assertConglomerateMissing(reopened, containerId);
        }
    }

    public void testDropWithPendingDmlDoesNotBlockOtherTableCommit() throws Exception {
        String databaseName = databaseName("mvcc-transactional-ddl-drop-with-live-table-db");
        try (Connection connection = openDatabase(databaseName, true)) {
            executeUpdate(connection,
                    "create table dropped_with_dml_t "
                            + "(id int primary key, value int) using delos_mvcc");
            executeUpdate(connection,
                    "create table retained_with_dml_t "
                            + "(id int primary key, value int) using delos_mvcc");

            connection.setAutoCommit(false);
            executeUpdate(connection, "insert into dropped_with_dml_t values (1, 10)");
            executeUpdate(connection, "insert into retained_with_dml_t values (2, 20)");
            executeUpdate(connection, "drop table dropped_with_dml_t");
            connection.commit();

            assertTableMissing(connection, "dropped_with_dml_t");
            assertRows(connection,
                    "select id, value from retained_with_dml_t order by id",
                    "2|20");
            connection.rollback();
        }

        shutdownDatabase(databaseName);
        try (Connection reopened = openDatabase(databaseName, false)) {
            assertTableMissing(reopened, "dropped_with_dml_t");
            assertRows(reopened,
                    "select id, value from retained_with_dml_t order by id",
                    "2|20");
        }
    }

    public void testCreateAndDropLifecycleRollBackToSavepoint() throws Exception {
        String databaseName = databaseName("mvcc-transactional-ddl-savepoint-db");
        long createdContainerId;
        try (Connection connection = openDatabase(databaseName, true)) {
            executeUpdate(connection,
                    "create table savepoint_existing_t (id int primary key, value int) using delos_mvcc");
            executeUpdate(connection, "insert into savepoint_existing_t values (1, 10)");

            connection.setAutoCommit(false);
            Savepoint createSavepoint = connection.setSavepoint("before_create");
            executeUpdate(connection,
                    "create table savepoint_created_t (id int primary key, value int) using delos_mvcc");
            createdContainerId = mvccContainerId(connection, "SAVEPOINT_CREATED_T");
            connection.rollback(createSavepoint);
            connection.commit();
            assertTableMissing(connection, "savepoint_created_t");
            assertConglomerateMissing(connection, createdContainerId);

            executeUpdate(connection, "insert into savepoint_existing_t values (2, 20)");
            Savepoint dropSavepoint = connection.setSavepoint("before_drop");
            executeUpdate(connection, "drop table savepoint_existing_t");
            connection.rollback(dropSavepoint);
            connection.commit();
            assertRows(connection,
                    "select id, value from savepoint_existing_t order by id",
                    "1|10", "2|20");
            connection.rollback();
        }

        shutdownDatabase(databaseName);
        try (Connection reopened = openDatabase(databaseName, false)) {
            assertTableMissing(reopened, "savepoint_created_t");
            assertRows(reopened,
                    "select id, value from savepoint_existing_t order by id",
                    "1|10", "2|20");
            assertConglomerateMissing(reopened, createdContainerId);
        }
    }

    private static void assertIndexCount(
            Connection connection,
            String indexName,
            int expected) throws SQLException {
        String sql = "select count(*) from sys.sysconglomerates "
                + "where conglomeratename = '" + indexName + "' and isindex = true";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            assertEquals(expected, result.getInt(1));
        }
    }

    private static void assertTableMissing(Connection connection, String tableName)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("select * from " + tableName);
            fail("Expected table to be absent: " + tableName);
        } catch (SQLException expected) {
            assertTrue("expected missing-table SQLState, got " + expected,
                    containsSqlState(expected, "42X05")
                            || containsSqlState(expected, "42Y55"));
        }
    }

    private static boolean containsSqlState(SQLException failure, String sqlState) {
        for (SQLException current = failure; current != null; current = current.getNextException()) {
            if (sqlState.equals(current.getSQLState())) {
                return true;
            }
        }
        return false;
    }

}
