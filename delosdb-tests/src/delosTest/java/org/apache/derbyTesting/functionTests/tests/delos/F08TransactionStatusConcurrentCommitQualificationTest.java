/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.impl.store.access.mvcc.MvccTransactionStatusCommitDiagnosticTestSupport;

/**
 * Cheap integration qualification for concurrent transaction-status commits.
 *
 * <p>The diagnostic interval starts only after each transaction has staged its
 * transaction-status row. Overlap therefore proves that status staging no
 * longer serializes all writers before RawStore commit.</p>
 */
public final class F08TransactionStatusConcurrentCommitQualificationTest
        extends MvccSqlTestSupport {
    private static final String B_PROPERTY =
            "delosdb.experimental.mvccGen2B.pk.enabled";
    private static final String STATUS_PROPERTY =
            "delosdb.experimental.mvccGen2TransactionStatusVisibility.enabled";
    private static final int WORKERS = 8;
    private static final int ROWS_PER_TRANSACTION = 100;

    public void testStatusBackedCommitsOverlapAfterStatusStaging() throws Exception {
        assertTrue("transaction-status commit diagnostics must be enabled by the task",
                MvccTransactionStatusCommitDiagnosticTestSupport.enabled());

        String database = databaseName("f08-tx-status-concurrent-commit");
        try (SystemPropertyScope b = setSystemProperty(B_PROPERTY, "true");
             SystemPropertyScope status = setSystemProperty(STATUS_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table T (id int not null primary key, "
                                + "payload varchar(128) not null) using delos_mvcc");
                setup.commit();
            }

            CountDownLatch ready = new CountDownLatch(WORKERS);
            CountDownLatch startCommit = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(WORKERS);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> threads = new ArrayList<>();

            for (int worker = 0; worker < WORKERS; worker++) {
                final int workerId = worker;
                Thread thread = new Thread(() -> runWriter(
                        database, workerId, ready, startCommit, done, failure),
                        "f08-tx-status-commit-" + workerId);
                thread.start();
                threads.add(thread);
            }

            assertTrue("writers did not prepare their transactions in time",
                    ready.await(30, TimeUnit.SECONDS));
            if (failure.get() != null) {
                throw new AssertionError("writer failed before commit qualification", failure.get());
            }

            MvccTransactionStatusCommitDiagnosticTestSupport.reset();
            startCommit.countDown();
            assertTrue("concurrent commits did not complete in time",
                    done.await(60, TimeUnit.SECONDS));
            for (Thread thread : threads) {
                thread.join(TimeUnit.SECONDS.toMillis(1));
            }
            if (failure.get() != null) {
                throw new AssertionError("concurrent transaction-status commit failed", failure.get());
            }

            long[] diagnostics = MvccTransactionStatusCommitDiagnosticTestSupport.snapshot();
            assertEquals("every writer must enter the status-backed commit pipeline",
                    WORKERS, diagnostics[0]);
            assertEquals("every entered status-backed commit must complete",
                    WORKERS, diagnostics[1]);
            assertTrue("at least two commits must overlap after status staging; max="
                            + diagnostics[2],
                    diagnostics[2] >= 2L);
            assertEquals("no status-backed commit may remain active after the wave",
                    0L, diagnostics[3]);

            try (Connection verify = openDatabase(database, false)) {
                assertRows(verify, "select count(*) from T",
                        Integer.toString(WORKERS * ROWS_PER_TRANSACTION));
            }
        } finally {
            shutdownIfBooted(database);
        }
    }

    private static void runWriter(
            String database,
            int workerId,
            CountDownLatch ready,
            CountDownLatch startCommit,
            CountDownLatch done,
            AtomicReference<Throwable> failure) {
        try (Connection connection = openDatabase(database, false)) {
            connection.setAutoCommit(false);
            insertBatch(connection, workerId * ROWS_PER_TRANSACTION + 1,
                    ROWS_PER_TRANSACTION);
            ready.countDown();
            if (!startCommit.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("commit start barrier timed out");
            }
            connection.commit();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
            ready.countDown();
        } finally {
            done.countDown();
        }
    }

    private static void shutdownIfBooted(String database) throws SQLException {
        try {
            shutdownDatabase(database);
        } catch (SQLException e) {
            if (!"XJ004".equals(e.getSQLState()) && !"08006".equals(e.getSQLState())) {
                throw e;
            }
        }
    }

    private static void insertBatch(Connection connection, int firstId, int count)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into T (id, payload) values (?, ?)")) {
            for (int offset = 0; offset < count; offset++) {
                int id = firstId + offset;
                statement.setInt(1, id);
                statement.setString(2, "v" + id);
                statement.addBatch();
            }
            int[] results = statement.executeBatch();
            assertEquals(count, results.length);
        }
    }
}
