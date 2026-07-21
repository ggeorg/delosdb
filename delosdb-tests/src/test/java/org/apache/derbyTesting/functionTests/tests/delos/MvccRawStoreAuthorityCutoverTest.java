/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreAuthorityCutoverTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.apache.derby.iapi.store.types.DelosStorageMaintenanceSnapshot;

/** Stage 5 proof that RawStore authority no longer boots the retained persistence runtime. */
public final class MvccRawStoreAuthorityCutoverTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";

    public void testCleanRawStoreDatabaseOwnsNoRetainedPersistenceRuntime() throws Exception {
        String database = databaseName("mvcc-raw-store-authority-clean");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table authority_t (id int, name varchar(64)) using delos_mvcc");
                executeUpdate(connection, "insert into authority_t values (1, 'raw-store')");
                connection.commit();
                assertRows(connection,
                        "select id, name from authority_t order by id",
                        "1|raw-store");
                connection.commit();

                assertEquals(
                        "RawStore authority must not create retained persistence files",
                        0L,
                        inheritedMvccStateFileCount(database));

                DelosStorageMaintenanceSnapshot maintenance =
                        mvccDiagnostics(database).databaseMaintenanceSnapshot();
                assertTrue(maintenance.runtimeActive());
                assertFalse(maintenance.maintenanceEnabled());

                try {
                    mvccDiagnostics(database).databaseStorageSnapshot();
                    fail("RawStore authority must not boot MvccDatabaseRuntime");
                } catch (IllegalStateException expected) {
                    assertTrue(expected.toString(),
                            containsMessage(expected, "No active delos_mvcc runtime"));
                }
            }
            shutdownDatabase(database);
        }
    }

    public void testRetainedFormatFailsClosedBeforeRawStoreRuntimeStarts() throws Exception {
        String database = databaseName("mvcc-raw-store-authority-retained");
        try (SystemPropertyScope ignored = clearSystemProperty(ENABLED_PROPERTY)) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table retained_t (id int, name varchar(64)) using delos_mvcc");
                executeUpdate(connection, "insert into retained_t values (7, 'retained')");
                connection.commit();
            }
            shutdownDatabase(database);
        }

        long retainedFileCount = inheritedMvccStateFileCount(database);
        assertTrue(retainedFileCount > 0L);

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try {
                try (Connection connection = DriverManager.getConnection("jdbc:derby:" + database)) {
                    assertRows(connection,
                            "select id, name from retained_t order by id",
                            "7|retained");
                }
                fail("RawStore authority must reject retained external state");
            } catch (SQLException expected) {
                assertTrue(expected.toString(),
                        containsMessage(expected, "retained external delos_mvcc state")
                                || containsMessage(expected, "retained external-format table"));
            }

            try {
                mvccDiagnostics(database).databaseMaintenanceSnapshot();
                fail("Rejected authority cutover must not register a RawStore runtime");
            } catch (IllegalStateException expected) {
                assertTrue(expected.toString(),
                        containsMessage(expected, "No active RawStore-backed delos_mvcc runtime"));
            }
            shutdownDatabase(database);
        }

        assertEquals(
                "fail-closed cutover must not rewrite retained state",
                retainedFileCount,
                inheritedMvccStateFileCount(database));

        try (SystemPropertyScope ignored = clearSystemProperty(ENABLED_PROPERTY)) {
            try (Connection connection = openDatabase(database, false)) {
                assertRows(connection,
                        "select id, name from retained_t order by id",
                        "7|retained");
            }
            shutdownDatabase(database);
        }
    }
}
