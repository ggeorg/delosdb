/*
 * Derby - Class org.apache.derbyTesting.functionTests.tests.store.BTreeIndexConcurrencyArchitectureProofTest
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
import java.sql.SQLException;
import java.sql.Statement;

import junit.framework.Test;

import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.DatabasePropertyTestSetup;
import org.apache.derbyTesting.junit.JDBC;
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * Focused B-tree index concurrency boundary checks.
 *
 * <p>These cases deliberately stay at the SQL/JDBC boundary. Derby's B-tree
 * implementation combines short-lived page latches with logical transaction
 * locks. Latches are not visible through SQL diagnostics, but the externally
 * observable contract is visible: unique-key conflicts must be serialized, a
 * rollback must release the index-key conflict, and rolled-back index entries
 * must not remain searchable.</p>
 *
 * <p>This is a proof net for the PostgreSQL-class indexing pillar. It does not
 * make DelosDB MVCC and it does not refactor B-tree latch, split, or scan
 * traversal code.</p>
 */
public class BTreeIndexConcurrencyArchitectureProofTest
        extends BaseJDBCTestCase {

    private static final String LOCK_TIMEOUT = "40XL1";
    private static final String DUPLICATE_KEY = "23505";

    public BTreeIndexConcurrencyArchitectureProofTest(String name) {
        super(name);
    }

    public static Test suite() {
        Test test = TestConfiguration.embeddedSuite(
                BTreeIndexConcurrencyArchitectureProofTest.class);
        test = new CleanDatabaseTestSetup(test) {
            protected void decorateSQL(Statement s) throws SQLException {
                s.execute("create table btree_index_concurrency "
                        + "(id int primary key, key_value int not null, "
                        + "payload int)");
                s.execute("create unique index btree_index_concurrency_key_uq "
                        + "on btree_index_concurrency(key_value)");
            }
        };
        return DatabasePropertyTestSetup.setLockTimeouts(test, 1, 2);
    }

    protected void setUp() throws Exception {
        super.setUp();
        Statement s = createStatement();
        s.executeUpdate("delete from btree_index_concurrency");
        commit();
    }

    /**
     * A second transaction must not silently insert a duplicate key while the
     * first transaction owns an uncommitted unique-index key. Once the owner
     * rolls back, the same key must be insertable and searchable through the
     * index. Depending on timing, Derby may expose the conflict as a lock
     * timeout or as duplicate-key detection; both are acceptable conflict
     * outcomes, but silent duplicate insertion is not.
     */
    public void testUncommittedUniqueKeyConflictReleasesAfterRollback()
            throws Exception {
        Connection owner = getConnection();
        owner.setAutoCommit(false);
        Statement ownerStatement = createStatement();
        ownerStatement.executeUpdate(
                "insert into btree_index_concurrency values (1, 7, 70)");

        Connection waiter = openDefaultConnection();
        waiter.setAutoCommit(false);
        Statement waiterStatement = waiter.createStatement();
        boolean ownerRolledBack = false;
        try {
            assertInsertConflicts(waiterStatement,
                    "insert into btree_index_concurrency values (2, 7, 71)");
            waiter.rollback();

            owner.rollback();
            ownerRolledBack = true;

            assertEquals(1, waiterStatement.executeUpdate(
                    "insert into btree_index_concurrency values (2, 7, 71)"));
            waiter.commit();
        } finally {
            if (!ownerRolledBack) {
                owner.rollback();
            }
            rollbackAndClose(waiter);
        }

        JDBC.assertFullResultSet(
                createStatement().executeQuery(
                        "select id, key_value, payload "
                        + "from btree_index_concurrency "
                        + "--derby-properties index=btree_index_concurrency_key_uq\n"
                        + "where key_value = 7"),
                new String[][] { { "2", "7", "71" } });
    }

    /**
     * Once the unique key is committed, Derby must reject a duplicate with the
     * normal duplicate-key SQLState. This pins down the committed-key side of
     * the same index-concurrency boundary.
     */
    public void testCommittedUniqueKeyRejectsDuplicate() throws Exception {
        Statement s = createStatement();
        s.executeUpdate("insert into btree_index_concurrency values (1, 9, 90)");
        commit();

        assertStatementError(DUPLICATE_KEY, s,
                "insert into btree_index_concurrency values (2, 9, 91)");

        JDBC.assertFullResultSet(
                s.executeQuery("select id, key_value, payload "
                        + "from btree_index_concurrency "
                        + "--derby-properties index=btree_index_concurrency_key_uq\n"
                        + "where key_value = 9"),
                new String[][] { { "1", "9", "90" } });
    }

    /**
     * Rolling back an indexed insert must leave no searchable index entry. A
     * later transaction should be able to reuse the same unique key and find
     * exactly the committed row through an index-forced lookup.
     */
    public void testRolledBackIndexInsertLeavesNoSearchableKey()
            throws Exception {
        Connection conn = getConnection();
        conn.setAutoCommit(false);
        Statement s = createStatement();

        s.executeUpdate("insert into btree_index_concurrency values (1, 11, 110)");
        conn.rollback();

        s.executeUpdate("insert into btree_index_concurrency values (2, 11, 111)");
        conn.commit();

        JDBC.assertFullResultSet(
                s.executeQuery("select id, key_value, payload "
                        + "from btree_index_concurrency "
                        + "--derby-properties index=btree_index_concurrency_key_uq\n"
                        + "where key_value = 11"),
                new String[][] { { "2", "11", "111" } });
    }

    private void assertInsertConflicts(Statement statement, String sql)
            throws Exception {
        try {
            statement.executeUpdate(sql);
            fail("Expected duplicate index-key insert to conflict");
        } catch (SQLException e) {
            String state = e.getSQLState();
            assertTrue("Expected lock timeout or duplicate-key conflict, got "
                    + state, LOCK_TIMEOUT.equals(state)
                    || DUPLICATE_KEY.equals(state));
        }
    }

    private void rollbackAndClose(Connection connection) throws SQLException {
        if (connection != null) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }
}
