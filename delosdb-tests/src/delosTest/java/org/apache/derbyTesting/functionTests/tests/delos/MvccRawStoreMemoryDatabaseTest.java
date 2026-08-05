/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreMemoryDatabaseTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Savepoint;

import org.apache.derby.iapi.store.types.DelosDatabaseMemorySnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageMaintenanceSnapshot;
import org.apache.derby.impl.io.VFMemoryStorageFactory;
import org.apache.derby.io.StorageFile;

/** Complete RawStore MVCC operation and accounting proofs for named memory databases. */
public final class MvccRawStoreMemoryDatabaseTest extends MvccSqlTestSupport {
    private static final String MEMORY_LIMIT_PROPERTY = "delosdb.memory.maxBytes";
    private static final long TEST_MEMORY_LIMIT = 64L * 1024L * 1024L;

    public void testCompleteFeatureSetUsesInheritedMemoryStorage() throws Exception {
        String database = "mvcc-stage6-complete-" + System.nanoTime();
        Path accidentalFilesystemDatabase = accidentalFilesystemPath(database);
        try (SystemPropertyScope ignored = setSystemProperty(
                MEMORY_LIMIT_PROPERTY, Long.toString(TEST_MEMORY_LIMIT));
             Connection connection = DriverManager.getConnection(
                     "jdbc:derby:memory:" + database + ";create=true")) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table memory_heap_t (id int primary key, value int)");
            executeUpdate(connection,
                    "create table memory_account_t ("
                            + "id int primary key, email varchar(64) unique, balance int) "
                            + "using delos_mvcc");
            executeUpdate(connection,
                    "create table memory_audit_t ("
                            + "id int primary key, account_id int, note varchar(64)) "
                            + "using delos_mvcc");
            executeUpdate(connection,
                    "create index memory_balance_idx on memory_account_t(balance)");
            executeUpdate(connection, "insert into memory_heap_t values (1, 10)");
            executeUpdate(connection,
                    "insert into memory_account_t values (1, 'one@example.test', 100)");
            executeUpdate(connection,
                    "insert into memory_audit_t values (1, 1, 'created')");
            connection.commit();

            Savepoint dmlSavepoint = connection.setSavepoint("stage6_dml");
            executeUpdate(connection,
                    "update memory_account_t set balance = 999 where id = 1");
            executeUpdate(connection,
                    "delete from memory_audit_t where id = 1");
            executeUpdate(connection,
                    "update memory_heap_t set value = 99 where id = 1");
            connection.rollback(dmlSavepoint);
            assertRows(connection,
                    "select id, email, balance from memory_account_t",
                    "1|one@example.test|100");
            assertRows(connection,
                    "select id, note from memory_audit_t",
                    "1|created");
            assertRows(connection, "select id, value from memory_heap_t", "1|10");

            Savepoint ddlSavepoint = connection.setSavepoint("stage6_ddl");
            executeUpdate(connection,
                    "create table memory_rolled_back_t (id int primary key) using delos_mvcc");
            executeUpdate(connection,
                    "create index memory_rolled_back_idx on memory_rolled_back_t(id)");
            connection.rollback(ddlSavepoint);
            assertRows(connection,
                    "select count(*) from sys.systables "
                            + "where tablename = 'MEMORY_ROLLED_BACK_T'",
                    "0");

            executeUpdate(connection,
                    "update memory_account_t set balance = 150 where id = 1");
            connection.commit();
            executeUpdate(connection,
                    "update memory_account_t set balance = 175 where id = 1");
            connection.commit();
            inPlaceCompressTable(connection, "MEMORY_ACCOUNT_T");
            connection.commit();

            assertRows(connection,
                    "select id, balance from memory_account_t "
                            + "where balance >= 100 order by balance",
                    "1|175");
            assertRows(connection,
                    "select h.value, a.balance from memory_heap_t h, memory_account_t a "
                            + "where h.id = a.id",
                    "10|175");
            connection.commit();

            DelosStorageMaintenanceSnapshot maintenance =
                    DelosStorageDiagnosticsRegistry
                            .mvccMemoryDatabaseMaintenanceSnapshot(database);
            DelosDatabaseMemorySnapshot memory =
                    DelosStorageDiagnosticsRegistry
                            .mvccMemoryDatabaseMemorySnapshot(database);
            assertTrue(maintenance.runtimeActive());
            assertTrue(memory.runtimeActive());
            assertTrue(memory.memoryDatabase());
            assertEquals(TEST_MEMORY_LIMIT, memory.limitBytes());
            assertTrue(memory.usedBytes() > 0L);
            assertTrue(memory.usedBytes() <= memory.limitBytes());
            assertTrue(memory.peakBytes() >= memory.usedBytes());
            assertEquals(0L, memory.rejectedGrowthCount());
            assertTrue(memory.entryCount() > 0);
            assertEquals(maintenance.databaseIdentity(), memory.databaseIdentity());
            assertTrue(memory.databaseIdentity().startsWith("memory:"));
        } finally {
            if (memoryRuntimeActive(database)) {
                shutdownNamedMemoryDatabase(database);
            }
        }

        assertFalse(Files.exists(accidentalFilesystemDatabase));
        assertMemoryRuntimeAbsent(database);
    }

    public void testTwoNamedMemoryDatabasesHaveScopedDiagnosticsAndShutdownIsolation()
            throws Exception {
        String firstDatabase = "mvcc-stage6-memory-a-" + System.nanoTime();
        String secondDatabase = "mvcc-stage6-memory-b-" + System.nanoTime();
        Path firstFilesystemPath = accidentalFilesystemPath(firstDatabase);
        Path secondFilesystemPath = accidentalFilesystemPath(secondDatabase);
        Connection first = null;
        Connection second = null;
        try (SystemPropertyScope ignored = setSystemProperty(
                MEMORY_LIMIT_PROPERTY, Long.toString(TEST_MEMORY_LIMIT))) {
            first = DriverManager.getConnection(
                    "jdbc:derby:memory:" + firstDatabase + ";create=true");
            second = DriverManager.getConnection(
                    "jdbc:derby:memory:" + secondDatabase + ";create=true");
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            executeUpdate(first,
                    "create table memory_a_t (id int primary key, value int) using delos_mvcc");
            executeUpdate(second,
                    "create table memory_b_t (id int primary key, value int) using delos_mvcc");
            executeUpdate(first, "insert into memory_a_t values (1, 10)");
            executeUpdate(second, "insert into memory_b_t values (1, 20)");
            first.commit();
            second.commit();

            DelosDatabaseMemorySnapshot firstSnapshot =
                    DelosStorageDiagnosticsRegistry
                            .mvccMemoryDatabaseMemorySnapshot(firstDatabase);
            DelosDatabaseMemorySnapshot secondSnapshot =
                    DelosStorageDiagnosticsRegistry
                            .mvccMemoryDatabaseMemorySnapshot(secondDatabase);
            assertFalse(firstSnapshot.databaseIdentity().equals(
                    secondSnapshot.databaseIdentity()));
            assertTrue(firstSnapshot.usedBytes() > 0L);
            assertTrue(secondSnapshot.usedBytes() > 0L);
            assertRows(first, "select id, value from memory_a_t", "1|10");
            assertRows(second, "select id, value from memory_b_t", "1|20");
            first.commit();
            second.commit();

            first.close();
            first = null;
            shutdownNamedMemoryDatabase(firstDatabase);
            assertMemoryRuntimeAbsent(firstDatabase);
            assertTrue(DelosStorageDiagnosticsRegistry
                    .mvccMemoryDatabaseMemorySnapshot(secondDatabase)
                    .runtimeActive());
            assertRows(second, "select id, value from memory_b_t", "1|20");
            second.commit();
        } finally {
            if (first != null) {
                first.close();
            }
            if (second != null) {
                second.close();
            }
            if (memoryRuntimeActive(firstDatabase)) {
                shutdownNamedMemoryDatabase(firstDatabase);
            }
            if (memoryRuntimeActive(secondDatabase)) {
                shutdownNamedMemoryDatabase(secondDatabase);
            }
        }

        assertMemoryRuntimeAbsent(secondDatabase);
        assertFalse(Files.exists(firstFilesystemPath));
        assertFalse(Files.exists(secondFilesystemPath));
    }

    public void testInheritedMemoryStoreRejectsGrowthBeforeItsDatabaseBudget()
            throws Exception {
        VFMemoryStorageFactory factory = new VFMemoryStorageFactory();
        String database = "mvcc-stage6-budget-" + System.nanoTime();
        factory.init(null, database, null, "stage6-budget");
        StorageFile root = factory.newStorageFile((String) null);
        assertTrue(root.mkdirs() || root.exists());
        factory.configureMemoryLimit(8L * 1024L);

        StorageFile file = factory.newStorageFile("bounded.dat");
        byte[] block = new byte[4 * 1024];
        try (OutputStream output = file.getOutputStream()) {
            output.write(block);
            assertEquals(8L * 1024L, factory.memoryUsedBytes());
            try {
                output.write(block);
                fail("memory storage must reject growth above the database budget");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(),
                        expected.getMessage().contains("Memory database limit exceeded"));
            }
        }

        assertEquals(8L * 1024L, factory.memoryLimitBytes());
        assertEquals(8L * 1024L, factory.memoryUsedBytes());
        assertEquals(8L * 1024L, factory.memoryPeakBytes());
        assertEquals(1L, factory.memoryRejectedGrowthCount());
        assertTrue(factory.memoryEntryCount() >= 2);
        assertTrue(file.delete());
        assertEquals(0L, factory.memoryUsedBytes());
        assertTrue(root.deleteAll());
        factory.shutdown();
    }

    private static Path accidentalFilesystemPath(String database) {
        String home = System.getProperty("derby.system.home");
        return (home == null || home.isBlank()
                ? Path.of(database)
                : Path.of(home, database))
                .toAbsolutePath()
                .normalize();
    }

    private static boolean memoryRuntimeActive(String database) {
        try {
            return DelosStorageDiagnosticsRegistry
                    .mvccMemory(database)
                    .runtimeActiveForTesting();
        } catch (IllegalStateException absent) {
            return false;
        }
    }

    private static void assertMemoryRuntimeAbsent(String database) {
        try {
            DelosStorageDiagnosticsRegistry.mvccMemoryDatabaseMemorySnapshot(database);
            fail("shutdown memory database must not retain its MVCC runtime");
        } catch (IllegalStateException expected) {
            // Weak non-owning diagnostics registration is removed at shutdown.
        }
    }
}
