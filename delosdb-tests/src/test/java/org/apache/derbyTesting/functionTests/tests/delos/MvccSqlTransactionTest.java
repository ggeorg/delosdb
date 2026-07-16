/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlTransactionTest

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

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.derbyTesting.junit.J2EEDataSource;
import org.apache.derbyTesting.junit.XATestUtil;

/** SQL integration tests for Phase 8.2 gating and the Phase 8.3 MVCC database decision. */
public final class MvccSqlTransactionTest extends MvccSqlTestSupport {
    private static final String UNSUPPORTED_SQL_STATE = "0A000";

    public void testSingleMvccTableAndHeapOnlyWritesRemainSupported() throws Exception {
        String databaseName = databaseName("mvcc-sql-supported-write-topologies-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_supported_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create table heap_supported_a (id int, name varchar(32))");
            executeUpdate(connection, "create table heap_supported_b (id int, name varchar(32))");
            connection.commit();

            executeUpdate(connection, "insert into mvcc_supported_t values (1, 'one')");
            executeUpdate(connection, "insert into mvcc_supported_t values (2, 'two')");
            connection.commit();

            executeUpdate(connection, "insert into heap_supported_a values (1, 'left')");
            executeUpdate(connection, "insert into heap_supported_b values (1, 'right')");
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_supported_t order by id",
                    "1|one", "2|two");
            assertRows(connection,
                    "select id, name from heap_supported_a order by id",
                    "1|left");
            assertRows(connection,
                    "select id, name from heap_supported_b order by id",
                    "1|right");
            connection.rollback();
        }
    }

    public void testReadOnlyCrossTableAndMixedAccessRemainsSupported() throws Exception {
        String databaseName = databaseName("mvcc-sql-read-only-mixed-topology-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            executeUpdate(connection, "create table mvcc_read_a (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create table mvcc_read_b (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create table heap_read_t (id int, name varchar(32))");
            executeUpdate(connection, "insert into mvcc_read_a values (1, 'a')");
            executeUpdate(connection, "insert into mvcc_read_b values (1, 'b')");
            executeUpdate(connection, "insert into heap_read_t values (1, 'h')");

            connection.setAutoCommit(false);
            assertRows(connection,
                    "select a.name, b.name, h.name "
                            + "from mvcc_read_a a, mvcc_read_b b, heap_read_t h "
                            + "where a.id = b.id and b.id = h.id",
                    "a|b|h");
            connection.rollback();
        }
    }

    public void testTwoMvccTablesCommitThroughOneDatabaseDecision() throws Exception {
        String databaseName = databaseName("mvcc-sql-database-decision-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_commit_a (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create table mvcc_commit_b (id int, name varchar(32)) using delos_mvcc");
            connection.commit();

            executeUpdate(connection, "insert into mvcc_commit_a values (1, 'left')");
            executeUpdate(connection, "insert into mvcc_commit_b values (1, 'right')");
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_commit_a order by id",
                    "1|left");
            assertRows(connection,
                    "select id, name from mvcc_commit_b order by id",
                    "1|right");
            connection.rollback();
        }

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_commit_a order by id",
                    "1|left");
            assertRows(reopened,
                    "select id, name from mvcc_commit_b order by id",
                    "1|right");
        }
    }

    public void testTwoMvccTableRollbackRemainsAtomic() throws Exception {
        String databaseName = databaseName("mvcc-sql-database-rollback-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            executeUpdate(connection, "create table mvcc_rollback_a (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create table mvcc_rollback_b (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_rollback_a values (1, 'before-a')");
            executeUpdate(connection, "insert into mvcc_rollback_b values (1, 'before-b')");

            connection.setAutoCommit(false);
            assertEquals(1, executeUpdate(connection,
                    "update mvcc_rollback_a set name = 'after-a' where id = 1"));
            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_rollback_b where id = 1"));
            connection.rollback();

            assertRows(connection,
                    "select id, name from mvcc_rollback_a order by id",
                    "1|before-a");
            assertRows(connection,
                    "select id, name from mvcc_rollback_b order by id",
                    "1|before-b");
            connection.rollback();
        }
    }

    public void testMixedHeapAndMvccWritesRejectInBothOrdersBeforeSecondMutation() throws Exception {
        String databaseName = databaseName("mvcc-sql-mixed-write-reject-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_reject_t (id int, name varchar(32))");
            executeUpdate(connection, "create table mvcc_reject_t (id int, name varchar(32)) using delos_mvcc");
            connection.commit();

            executeUpdate(connection, "insert into heap_reject_t values (1, 'heap-first')");
            assertUnsupported(() -> executeUpdate(connection,
                    "insert into mvcc_reject_t values (1, 'must-not-appear')"));
            assertRows(connection, "select id, name from mvcc_reject_t order by id");
            connection.rollback();

            executeUpdate(connection, "insert into mvcc_reject_t values (2, 'mvcc-first')");
            assertUnsupported(() -> executeUpdate(connection,
                    "insert into heap_reject_t values (2, 'must-not-appear')"));
            assertRows(connection,
                    "select id, name from heap_reject_t order by id");
            connection.rollback();

            assertRows(connection, "select id, name from heap_reject_t order by id");
            assertRows(connection, "select id, name from mvcc_reject_t order by id");
            connection.rollback();
        }
    }

    public void testMvccXaWriteRejectsBeforeMutationWhileHeapXaRemainsSupported() throws Exception {
        String databaseName = databaseName("mvcc-sql-xa-write-reject-db");

        try (Connection setup = openDatabase(databaseName, true)) {
            executeUpdate(setup, "create table mvcc_xa_reject_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(setup, "create table heap_xa_supported_t (id int, name varchar(32))");
        }

        XADataSource dataSource = J2EEDataSource.getXADataSource();
        J2EEDataSource.setBeanProperty(dataSource, "databaseName", databaseName);

        Xid heapXid = XATestUtil.getXid(0x8201, 1, 1);
        XAConnection heapXaConnection = dataSource.getXAConnection();
        try (Connection connection = heapXaConnection.getConnection()) {
            XAResource resource = heapXaConnection.getXAResource();
            resource.start(heapXid, XAResource.TMNOFLAGS);
            executeUpdate(connection, "insert into heap_xa_supported_t values (1, 'heap-xa')");
            resource.end(heapXid, XAResource.TMSUCCESS);
            resource.commit(heapXid, true);
        } finally {
            heapXaConnection.close();
        }

        Xid mvccXid = XATestUtil.getXid(0x8202, 2, 2);
        XAConnection mvccXaConnection = dataSource.getXAConnection();
        try (Connection connection = mvccXaConnection.getConnection()) {
            XAResource resource = mvccXaConnection.getXAResource();
            resource.start(mvccXid, XAResource.TMNOFLAGS);
            assertUnsupported(() -> executeUpdate(connection,
                    "insert into mvcc_xa_reject_t values (1, 'must-not-appear')"));
            try {
                resource.end(mvccXid, XAResource.TMFAIL);
                fail("Expected XA rollback-only outcome after TMFAIL");
            } catch (XAException expected) {
                assertEquals(XAException.XA_RBROLLBACK, expected.errorCode);
            }
            resource.rollback(mvccXid);
        } finally {
            mvccXaConnection.close();
        }

        try (Connection verification = openDatabase(databaseName, false)) {
            assertRows(verification,
                    "select id, name from heap_xa_supported_t order by id",
                    "1|heap-xa");
            assertRows(verification,
                    "select id, name from mvcc_xa_reject_t order by id");
        }
    }

    private static void assertUnsupported(SqlAction action) throws SQLException {
        try {
            action.run();
            fail("Expected temporary transaction-topology rejection");
        } catch (SQLException expected) {
            assertEquals("unexpected SQLState for transaction-topology rejection",
                    UNSUPPORTED_SQL_STATE,
                    expected.getSQLState());
        }
    }
}
