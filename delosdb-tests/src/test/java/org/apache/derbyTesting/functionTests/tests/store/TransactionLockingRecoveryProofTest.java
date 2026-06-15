/*
 * Derby - Class org.apache.derbyTesting.functionTests.tests.store.TransactionLockingRecoveryProofTest
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.derbyTesting.functionTests.tests.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;

import junit.framework.Test;

import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.DatabasePropertyTestSetup;
import org.apache.derbyTesting.junit.JDBC;
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * Focused transaction, locking, and checkpoint boundary checks.
 *
 * <p>This test deliberately exercises Derby through JDBC and diagnostic SQL.
 * The inherited transaction, lock, log, and recovery code paths are too risky
 * to refactor before the externally visible boundaries are pinned down. These
 * cases are intended as a small proof net before any later cleanup touches the
 * lock manager, transaction undo, or checkpoint/recovery code.</p>
 */
public class TransactionLockingRecoveryProofTest extends BaseJDBCTestCase {

    public TransactionLockingRecoveryProofTest(String name) {
        super(name);
    }

    public static Test suite() {
        Test test = TestConfiguration.embeddedSuite(
                TransactionLockingRecoveryProofTest.class);
        test = new CleanDatabaseTestSetup(test) {
            protected void decorateSQL(Statement s) throws SQLException {
                s.execute("create table tx_proof "
                        + "(id int primary key, value int)");
            }
        };
        return DatabasePropertyTestSetup.setLockTimeouts(test, 1, 2);
    }

    protected void setUp() throws Exception {
        super.setUp();
        Statement s = createStatement();
        s.executeUpdate("delete from tx_proof");
        s.executeUpdate("insert into tx_proof values (1, 10)");
        s.executeUpdate("insert into tx_proof values (2, 20)");
        commit();
    }

    /**
     * A second transaction must time out on a held row X lock, and the same
     * row must become updateable again after the owning transaction rolls back.
     */
    public void testRowLockTimeoutThenReleaseOnRollback() throws Exception {
        Connection owner = getConnection();
        owner.setAutoCommit(false);
        Statement ownerStatement = createStatement();
        ownerStatement.executeUpdate(
                "update tx_proof set value = 11 where id = 1");

        assertOwnerHoldsExclusiveRowLock();

        Connection waiter = openDefaultConnection();
        waiter.setAutoCommit(false);
        Statement waiterStatement = waiter.createStatement();
        try {
            assertStatementError("40XL1", waiterStatement,
                    "update tx_proof set value = 12 where id = 1");
            waiter.rollback();

            owner.rollback();

            assertEquals(1, waiterStatement.executeUpdate(
                    "update tx_proof set value = 12 where id = 1"));
            waiter.commit();
        } finally {
            waiter.rollback();
            waiter.close();
        }

        JDBC.assertSingleValueResultSet(
                createStatement().executeQuery(
                        "select value from tx_proof where id = 1"),
                "12");
    }

    /**
     * Savepoint rollback must undo work after the savepoint while preserving
     * earlier work in the same transaction.
     */
    public void testSavepointRollbackPreservesEarlierTransactionWork()
            throws Exception {
        Connection conn = getConnection();
        conn.setAutoCommit(false);
        Statement s = createStatement();

        s.executeUpdate("update tx_proof set value = 100 where id = 1");
        Savepoint afterFirstUpdate = conn.setSavepoint("after_first_update");
        s.executeUpdate("update tx_proof set value = 200 where id = 2");
        conn.rollback(afterFirstUpdate);
        conn.commit();

        JDBC.assertFullResultSet(
                s.executeQuery("select id, value from tx_proof order by id"),
                new String[][] { { "1", "100" }, { "2", "20" } });
    }

    /**
     * Checkpointing after a rollback must leave only committed work visible.
     * This is not a crash-recovery test; the existing {@link RecoveryTest}
     * still owns dirty-shutdown restart recovery. This case pins down the
     * simpler checkpoint boundary before log/recovery cleanup is attempted.
     */
    public void testCheckpointKeepsCommittedWorkOnly() throws Exception {
        Connection conn = getConnection();
        conn.setAutoCommit(false);
        Statement s = createStatement();

        s.executeUpdate("insert into tx_proof values (3, 30)");
        conn.commit();

        s.executeUpdate("insert into tx_proof values (4, 40)");
        conn.rollback();

        s.executeUpdate("call SYSCS_UTIL.SYSCS_CHECKPOINT_DATABASE()");

        JDBC.assertFullResultSet(
                s.executeQuery("select id, value from tx_proof order by id"),
                new String[][] {
                    { "1", "10" }, { "2", "20" }, { "3", "30" }
                });
    }

    private void assertOwnerHoldsExclusiveRowLock() throws Exception {
        Statement s = createStatement();
        ResultSet rs = s.executeQuery(
                "select mode from syscs_diag.lock_table "
                + "where tablename = 'TX_PROOF' and type = 'ROW' "
                + "and mode = 'X' and state = 'GRANT'");
        assertTrue("Expected owning transaction to hold a granted row X lock",
                rs.next());
        rs.close();
    }
}
