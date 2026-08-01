/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreAuthorityCutoverTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.apache.derby.iapi.store.types.DelosStorageMaintenanceSnapshot;

/** Stage 5 proof that RawStore is the only production MVCC persistence authority. */
public final class MvccRawStoreAuthorityCutoverTest extends MvccSqlTestSupport {
    private static final String RETIRED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";

    public void testRawStoreIsTheOnlyBootedMvccRuntime() throws Exception {
        String database = databaseName("mvcc-raw-store-authority-only");
        try (SystemPropertyScope ignored = setSystemProperty(RETIRED_PROPERTY, "false");
             Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table authority_t (id int, name varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into authority_t values (1, 'raw-store')");
            connection.commit();
            assertRows(connection,
                    "select id, name from authority_t order by id",
                    "1|raw-store");
            connection.commit();

            assertEquals(0L, inheritedMvccStateFileCount(database));
            DelosStorageMaintenanceSnapshot maintenance =
                    mvccDiagnostics(database).databaseMaintenanceSnapshot();
            assertTrue(maintenance.runtimeActive());

            try {
                mvccDiagnostics(database).databaseStorageSnapshot();
                fail("Retained database-storage diagnostics must be retired");
            } catch (IllegalStateException expected) {
                assertTrue(expected.toString(),
                        containsMessage(expected, "external persistence runtime"));
            }
        }
        shutdownDatabase(database);
    }

    public void testRetainedStateFailsClosedWithoutLegacyFallback() throws Exception {
        String database = databaseName("mvcc-raw-store-authority-retired-state");
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table authority_guard_t (id int) using delos_mvcc");
            executeUpdate(connection, "insert into authority_guard_t values 7");
            connection.commit();
        }
        shutdownDatabase(database);

        Path providerDirectory = databasePath(database).resolve("delos_mvcc");
        Files.createDirectories(providerDirectory);
        Path retainedMarker = providerDirectory.resolve("retained.marker");
        Files.writeString(retainedMarker, "retired");

        try (SystemPropertyScope ignored = setSystemProperty(RETIRED_PROPERTY, "false")) {
            try (Connection connection = DriverManager.getConnection("jdbc:derby:" + database)) {
                assertRows(connection, "select id from authority_guard_t", "7");
                fail("Retired external state must reject database boot before runtime registration");
            } catch (SQLException expected) {
                assertTrue(expected.toString(),
                        containsMessage(expected, "retired external delos_mvcc format"));
            }

            try {
                mvccDiagnostics(database).databaseMaintenanceSnapshot();
                fail("Rejected boot must not register a RawStore runtime");
            } catch (IllegalStateException expected) {
                assertTrue(expected.toString(),
                        containsMessage(expected, "No active RawStore-backed delos_mvcc runtime"));
            }
        }

        Files.delete(retainedMarker);
        Files.delete(providerDirectory);
        try (Connection reopened = openDatabase(database, false)) {
            assertRows(reopened, "select id from authority_guard_t", "7");
        }
        shutdownDatabase(database);
    }
}
