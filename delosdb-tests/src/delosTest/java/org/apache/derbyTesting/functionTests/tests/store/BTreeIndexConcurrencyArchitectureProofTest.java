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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

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
 * make DelosDB MVCC; it pins down B-tree conflict, rollback, and concurrent
 * routing behavior while inherited index internals are modernized.</p>
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
        Properties lockTimeouts = new Properties();
        lockTimeouts.setProperty("derby.locks.deadlockTimeout", "1");
        lockTimeouts.setProperty("derby.locks.waitTimeout", "2");
        return new DatabasePropertyTestSetup(test, lockTimeouts, false);
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

    /**
     * A leaf point-read snapshot must not retain a stale RowLocation while a
     * writer repeatedly replaces the row behind one committed unique key. The
     * writer deletes and reinserts the key atomically; every reader result must
     * therefore contain exactly one committed generation with a matching
     * payload. This specifically exercises snapshot invalidation and the
     * post-lock revalidation used by the latch-free exact-key path.
     */
    public void testConcurrentLeafSnapshotTracksRowLocationReplacement()
            throws Exception {
        populateCommittedRows(1, 2000);
        Statement setup = createStatement();

        JDBC.assertFullResultSet(
                setup.executeQuery(
                        "select id, payload from btree_index_concurrency "
                        + "--derby-properties index=btree_index_concurrency_key_uq\n"
                        + "where key_value = 37"),
                new String[][] { { "37", "370" } });

        Connection reader = openDefaultConnection();
        Connection writer = openDefaultConnection();
        reader.setAutoCommit(true);
        writer.setAutoCommit(false);

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread readerThread = new Thread(
                () -> runReplacingKeyReader(reader, start, failure),
                "btree-leaf-snapshot-reader");
        Thread writerThread = new Thread(
                () -> runReplacingKeyWriter(writer, start, failure),
                "btree-leaf-snapshot-writer");

        readerThread.start();
        writerThread.start();
        start.countDown();
        readerThread.join();
        writerThread.join();

        reader.close();
        rollbackAndClose(writer);
        if (failure.get() != null) {
            throw new AssertionError(
                    "Concurrent leaf snapshot failure", failure.get());
        }
    }

    /**
     * A cached root-routing snapshot must never make an index lookup miss a
     * key while concurrent inserts grow and split the same B-tree. The reader
     * repeatedly uses the forced unique secondary index while the writer adds
     * enough keys to mutate its routing structure.
     */
    public void testConcurrentRootRoutingSnapshotTracksIndexGrowth()
            throws Exception {
        populateCommittedRows(1, 2000);

        JDBC.assertSingleValueResultSet(
                createStatement().executeQuery(
                        "select payload from btree_index_concurrency "
                        + "--derby-properties index=btree_index_concurrency_key_uq\n"
                        + "where key_value = 1000"),
                "10000");

        Connection reader = openDefaultConnection();
        Connection writer = openDefaultConnection();
        reader.setAutoCommit(true);
        writer.setAutoCommit(false);

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread readerThread = new Thread(
                () -> runIndexedReader(reader, start, failure),
                "btree-root-routing-reader");
        Thread writerThread = new Thread(
                () -> runIndexGrowthWriter(writer, start, failure),
                "btree-root-routing-writer");

        readerThread.start();
        writerThread.start();
        start.countDown();
        readerThread.join();
        writerThread.join();

        reader.close();
        rollbackAndClose(writer);
        if (failure.get() != null) {
            throw new AssertionError(
                    "Concurrent root-routing snapshot failure", failure.get());
        }

        JDBC.assertSingleValueResultSet(
                createStatement().executeQuery(
                        "select payload from btree_index_concurrency "
                        + "--derby-properties index=btree_index_concurrency_key_uq\n"
                        + "where key_value = 5000"),
                "50000");
    }

    private void populateCommittedRows(int first, int last) throws Exception {
        try (PreparedStatement insert = prepareStatement(
                "insert into btree_index_concurrency values (?, ?, ?)")) {
            for (int key = first; key <= last; key++) {
                insert.setInt(1, key);
                insert.setInt(2, key);
                insert.setInt(3, key * 10);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        commit();
    }

    private void runIndexedReader(
            Connection connection, CountDownLatch start,
            AtomicReference<Throwable> failure) {
        try (PreparedStatement query = connection.prepareStatement(
                "select payload from btree_index_concurrency "
                + "--derby-properties index=btree_index_concurrency_key_uq\n"
                + "where key_value = ?")) {
            start.await();
            for (int iteration = 0; iteration < 6000; iteration++) {
                int key = 1 + (iteration % 2000);
                query.setInt(1, key);
                try (ResultSet rs = query.executeQuery()) {
                    if (!rs.next() || rs.getInt(1) != key * 10 || rs.next()) {
                        throw new AssertionError(
                                "Incorrect indexed lookup for key " + key);
                    }
                }
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private void runReplacingKeyReader(
            Connection connection, CountDownLatch start,
            AtomicReference<Throwable> failure) {
        try (PreparedStatement query = connection.prepareStatement(
                "select id, payload from btree_index_concurrency "
                + "--derby-properties index=btree_index_concurrency_key_uq\n"
                + "where key_value = 37")) {
            start.await();
            for (int iteration = 0; iteration < 2000; iteration++) {
                try (ResultSet rs = query.executeQuery()) {
                    if (!rs.next()) {
                        throw new AssertionError(
                                "Missing committed unique-key generation");
                    }
                    int id = rs.getInt(1);
                    int payload = rs.getInt(2);
                    if (payload != id * 10 || rs.next()) {
                        throw new AssertionError(
                                "Stale or duplicate unique-key generation: id="
                                + id + ", payload=" + payload);
                    }
                }
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private void runReplacingKeyWriter(
            Connection connection, CountDownLatch start,
            AtomicReference<Throwable> failure) {
        try (PreparedStatement delete = connection.prepareStatement(
                     "delete from btree_index_concurrency where key_value = 37");
             PreparedStatement insert = connection.prepareStatement(
                     "insert into btree_index_concurrency values (?, 37, ?)")) {
            start.await();
            for (int iteration = 1; iteration <= 300; iteration++) {
                if (delete.executeUpdate() != 1) {
                    throw new AssertionError(
                            "Expected exactly one unique-key row to replace");
                }
                int id = 100000 + iteration;
                insert.setInt(1, id);
                insert.setInt(2, id * 10);
                insert.executeUpdate();
                connection.commit();
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
        }
    }

    private void runIndexGrowthWriter(
            Connection connection, CountDownLatch start,
            AtomicReference<Throwable> failure) {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into btree_index_concurrency values (?, ?, ?)")) {
            start.await();
            for (int key = 2001; key <= 5000; key++) {
                insert.setInt(1, key);
                insert.setInt(2, key);
                insert.setInt(3, key * 10);
                insert.executeUpdate();
                if ((key % 50) == 0) {
                    connection.commit();
                }
            }
            connection.commit();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
        }
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
