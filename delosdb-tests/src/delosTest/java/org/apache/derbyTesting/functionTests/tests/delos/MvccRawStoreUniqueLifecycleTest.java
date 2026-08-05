/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreUniqueLifecycleTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.iapi.types.SQLVarchar;
import org.apache.derby.shared.common.error.StandardException;

/** Transactional native unique-metadata lifecycle proofs for RawStore MVCC. */
public final class MvccRawStoreUniqueLifecycleTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";

    public void testAlterTableAddDropCommitRollbackAndDirectEnforcement() throws Exception {
        String database = databaseName("mvcc-raw-store-unique-lifecycle-alter");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table lifecycle_t (id int, email varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into lifecycle_t values (1, 'one@example')");
            connection.commit();
            assertEquals(0, uniqueMetadata(connection, "LIFECYCLE_T").size());

            executeUpdate(connection,
                    "alter table lifecycle_t add constraint uq_lifecycle_email unique (email)");
            assertUniqueDefinition(connection, "LIFECYCLE_T", 1, true, 1);
            connection.rollback();
            assertEquals(0, uniqueMetadata(connection, "LIFECYCLE_T").size());

            executeUpdate(connection,
                    "alter table lifecycle_t add constraint uq_lifecycle_email unique (email)");
            connection.commit();
            assertUniqueDefinition(connection, "LIFECYCLE_T", 1, true, 1);

            try {
                MvccRawStoreMetadataInspection.insertBaseRowDirect(
                        connection,
                        "LIFECYCLE_T",
                        row(2, "one@example"));
                fail("Expected direct base-conglomerate duplicate rejection");
            } catch (StandardException expected) {
                assertEquals("23505", expected.getSQLState());
            }
            connection.rollback();

            executeUpdate(connection,
                    "alter table lifecycle_t drop constraint uq_lifecycle_email");
            assertEquals(0, uniqueMetadata(connection, "LIFECYCLE_T").size());
            connection.rollback();
            assertUniqueDefinition(connection, "LIFECYCLE_T", 1, true, 1);
            assertDuplicateKey(() -> executeUpdate(
                    connection,
                    "insert into lifecycle_t values (2, 'one@example')"));
            connection.rollback();

            executeUpdate(connection,
                    "alter table lifecycle_t drop constraint uq_lifecycle_email");
            connection.commit();
            assertEquals(0, uniqueMetadata(connection, "LIFECYCLE_T").size());
            executeUpdate(connection, "insert into lifecycle_t values (2, 'one@example')");
            connection.commit();
            assertRows(connection,
                    "select id, email from lifecycle_t order by id",
                    "1|one@example",
                    "2|one@example");
            connection.commit();
        }
        shutdownDatabase(database);
    }

    public void testCreateDropUniqueIndexAndSharedDefinitionReferenceCounts() throws Exception {
        String database = databaseName("mvcc-raw-store-unique-lifecycle-index");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table lifecycle_index_t (id int, email varchar(64)) using delos_mvcc");
            executeUpdate(connection,
                    "create unique index uq_lifecycle_index on lifecycle_index_t(email)");
            connection.commit();
            assertUniqueDefinition(connection, "LIFECYCLE_INDEX_T", 1, false, 1);

            executeUpdate(connection,
                    "alter table lifecycle_index_t add constraint uq_lifecycle_constraint unique (email)");
            connection.commit();
            List<MvccRawStoreMetadataInspection.UniqueConstraintIdentity> shared =
                    uniqueMetadata(connection, "LIFECYCLE_INDEX_T");
            assertEquals(2, shared.size());
            assertFalse(shared.get(0).duplicateNullsAllowed());
            assertTrue(shared.get(1).duplicateNullsAllowed());

            executeUpdate(connection, "insert into lifecycle_index_t values (1, 'shared@example')");
            connection.commit();
            assertDuplicateKey(() -> executeUpdate(
                    connection,
                    "insert into lifecycle_index_t values (2, 'shared@example')"));
            connection.rollback();

            executeUpdate(connection, "drop index uq_lifecycle_index");
            connection.commit();
            assertUniqueDefinition(connection, "LIFECYCLE_INDEX_T", 1, true, 1);
            assertDuplicateKey(() -> executeUpdate(
                    connection,
                    "insert into lifecycle_index_t values (2, 'shared@example')"));
            connection.rollback();
            executeUpdate(connection, "insert into lifecycle_index_t values (3, null)");
            executeUpdate(connection, "insert into lifecycle_index_t values (4, null)");
            connection.commit();

            executeUpdate(connection,
                    "alter table lifecycle_index_t drop constraint uq_lifecycle_constraint");
            connection.commit();
            assertEquals(0, uniqueMetadata(connection, "LIFECYCLE_INDEX_T").size());
            executeUpdate(connection,
                    "insert into lifecycle_index_t values (5, 'shared@example')");
            connection.commit();
        }
        shutdownDatabase(database);
    }

    public void testRejectedDefinitionsAndStatementRollbackLeaveNoNativeMetadata()
            throws Exception {
        String database = databaseName("mvcc-raw-store-unique-lifecycle-reject");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table lifecycle_reject_t (id int, email varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into lifecycle_reject_t values (1, 'duplicate@example')");
            executeUpdate(connection, "insert into lifecycle_reject_t values (2, 'duplicate@example')");
            connection.commit();

            assertDuplicateKey(() -> executeUpdate(
                    connection,
                    "alter table lifecycle_reject_t add constraint uq_reject unique (email)"));
            connection.rollback();
            assertEquals(0, uniqueMetadata(connection, "LIFECYCLE_REJECT_T").size());

            assertDuplicateKey(() -> executeUpdate(
                    connection,
                    "create unique index uq_reject_index on lifecycle_reject_t(email)"));
            connection.rollback();
            assertEquals(0, uniqueMetadata(connection, "LIFECYCLE_REJECT_T").size());

            try {
                executeUpdate(connection,
                        "alter table lifecycle_reject_t add constraint uq_deferred unique (email) "
                                + "deferrable initially deferred");
                fail("Expected deferrable RawStore MVCC unique metadata to fail closed");
            } catch (SQLException expected) {
                assertEquals("0A000", expected.getSQLState());
            }
            connection.rollback();
            assertEquals(0, uniqueMetadata(connection, "LIFECYCLE_REJECT_T").size());
            try {
                executeUpdate(connection,
                        "alter table lifecycle_reject_t drop constraint uq_deferred");
                fail("Rejected deferrable constraint must not leave catalog state");
            } catch (SQLException expected) {
                assertEquals("42X86", expected.getSQLState());
            }
            connection.rollback();
        }
        shutdownDatabase(database);
    }

    public void testLifecycleSurvivesBothCrashBoundariesReopenAndMemory() throws Exception {
        verifyCrashBoundary("after-stamp-before-raw-commit", 91, false);
        verifyCrashBoundary("after-raw-commit-before-publication", 92, true);

        String memoryDatabase = "mvcc_raw_store_unique_lifecycle_memory_"
                + Long.toUnsignedString(System.nanoTime());
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = DriverManager.getConnection(
                     "jdbc:derby:memory:" + memoryDatabase + ";create=true")) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table memory_lifecycle (id int, email varchar(64)) using delos_mvcc");
            executeUpdate(connection,
                    "alter table memory_lifecycle add constraint uq_memory_lifecycle unique (email)");
            connection.commit();
            assertUniqueDefinition(connection, "MEMORY_LIFECYCLE", 1, true, 1);
            executeUpdate(connection, "insert into memory_lifecycle values (1, 'memory@example')");
            connection.commit();
            assertDuplicateKey(() -> executeUpdate(
                    connection,
                    "insert into memory_lifecycle values (2, 'memory@example')"));
            connection.rollback();

            executeUpdate(connection,
                    "alter table memory_lifecycle drop constraint uq_memory_lifecycle");
            connection.rollback();
            assertUniqueDefinition(connection, "MEMORY_LIFECYCLE", 1, true, 1);

            executeUpdate(connection,
                    "alter table memory_lifecycle drop constraint uq_memory_lifecycle");
            connection.commit();
            assertEquals(0, uniqueMetadata(connection, "MEMORY_LIFECYCLE").size());
            executeUpdate(connection, "insert into memory_lifecycle values (2, 'memory@example')");
            connection.commit();
        }
        shutdownMemoryDatabase(memoryDatabase);
    }

    private static void verifyCrashBoundary(
            String failurePoint,
            int expectedStatus,
            boolean expectCommitted) throws Exception {
        String database = Path.of("mvcc-raw-store-unique-lifecycle-crash-"
                + failurePoint + '-'
                + Long.toUnsignedString(System.nanoTime()))
                .toAbsolutePath()
                .normalize()
                .toString();
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table crash_lifecycle (id int, email varchar(64)) using delos_mvcc");
                executeUpdate(setup, "insert into crash_lifecycle values (1, 'old@example')");
                setup.commit();
            }
            shutdownDatabase(database);
        }

        Process process = new ProcessBuilder(
                javaExecutable(),
                "--add-exports",
                "java.base/sun.security.action=ALL-UNNAMED",
                "--add-opens",
                "java.base/java.nio=ALL-UNNAMED",
                "-D" + ENABLED_PROPERTY + "=true",
                "-D" + FAILURE_POINT_PROPERTY + '=' + failurePoint,
                "-cp",
                System.getProperty("java.class.path"),
                CrashWorker.class.getName(),
                database)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("RawStore MVCC unique lifecycle crash worker did not terminate");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("unexpected crash status; output=" + output, expectedStatus, process.exitValue());

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection recovered = openDatabase(database, false)) {
            recovered.setAutoCommit(false);
            List<MvccRawStoreMetadataInspection.UniqueConstraintIdentity> constraints =
                    uniqueMetadata(recovered, "CRASH_LIFECYCLE");
            assertEquals(expectCommitted ? 1 : 0, constraints.size());
            if (expectCommitted) {
                assertRows(recovered,
                        "select id, email from crash_lifecycle order by id",
                        "1|old@example",
                        "2|new@example");
                assertDuplicateKey(() -> executeUpdate(
                        recovered,
                        "insert into crash_lifecycle values (3, 'old@example')"));
                recovered.rollback();
            } else {
                assertRows(recovered,
                        "select id, email from crash_lifecycle order by id",
                        "1|old@example");
                executeUpdate(recovered,
                        "insert into crash_lifecycle values (3, 'old@example')");
                recovered.rollback();
            }
        }
        shutdownDatabase(database);
    }

    private static List<MvccRawStoreMetadataInspection.UniqueConstraintIdentity> uniqueMetadata(
            Connection connection,
            String tableName) throws Exception {
        return MvccRawStoreMetadataInspection.uniqueConstraints(connection, tableName);
    }

    private static void assertUniqueDefinition(
            Connection connection,
            String tableName,
            int expectedColumn,
            boolean duplicateNullsAllowed,
            int expectedCount) throws Exception {
        List<MvccRawStoreMetadataInspection.UniqueConstraintIdentity> constraints =
                uniqueMetadata(connection, tableName);
        assertEquals(expectedCount, constraints.size());
        MvccRawStoreMetadataInspection.UniqueConstraintIdentity constraint =
                constraints.get(expectedCount - 1);
        assertEquals(List.of(expectedColumn),
                java.util.Arrays.stream(constraint.columns()).boxed().toList());
        assertEquals(duplicateNullsAllowed, constraint.duplicateNullsAllowed());
    }

    private static StoreDataValue[] row(int id, String email) {
        return new StoreDataValue[] {
                new SQLInteger(id),
                email == null ? new SQLVarchar() : new SQLVarchar(email)
        };
    }

    private static void shutdownMemoryDatabase(String databaseName) throws Exception {
        try {
            DriverManager.getConnection(
                    "jdbc:derby:memory:" + databaseName + ";shutdown=true");
            fail("Memory database shutdown should throw the normal Derby shutdown exception");
        } catch (SQLException expected) {
            if (!"08006".equals(expected.getSQLState())) {
                throw expected;
            }
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Child JVM stopped on either side of the inherited RawStore commit record. */
    public static final class CrashWorker {
        private CrashWorker() {
        }

        public static void main(String[] arguments) throws Exception {
            if (arguments.length != 1) {
                throw new IllegalArgumentException("Expected database path");
            }
            try (Connection connection = DriverManager.getConnection("jdbc:derby:" + arguments[0])) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "alter table crash_lifecycle add constraint uq_crash_lifecycle unique (email)");
                executeUpdate(connection,
                        "insert into crash_lifecycle values (2, 'new@example')");
                connection.commit();
            }
            throw new AssertionError("Crash failure point did not halt the child JVM");
        }
    }
}
