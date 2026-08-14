/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapPageReadImageArchitectureProofTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements. See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** Correctness proof for the experimental immutable heap-page read image. */
public final class HeapPageReadImageArchitectureProofTest extends MvccSqlTestSupport {
    private static final String DATABASE = "heap-page-read-image-proof";

    public void testCommitRollbackDeleteReinsertAndReopenRemainCurrent()
            throws Exception {
        try (Connection connection = openDatabase(DATABASE, true)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "create table image_t (id int primary key, quantity int not null)");
                statement.executeUpdate("insert into image_t values (1, 10)");
            }
            connection.commit();

            assertQuantity(connection, 10); // latched fallback publishes the first image
            resetDiagnostics();
            assertQuantity(connection, 10); // immutable hit
            assertTrue("expected an immutable heap-page hit", snapshotDiagnostics()[1] > 0L);

            try (PreparedStatement update = connection.prepareStatement(
                    "update image_t set quantity = ? where id = 1")) {
                update.setInt(1, 20);
                assertEquals(1, update.executeUpdate());
            }
            connection.commit();
            assertQuantity(connection, 20);

            try (PreparedStatement update = connection.prepareStatement(
                    "update image_t set quantity = ? where id = 1")) {
                update.setInt(1, 30);
                assertEquals(1, update.executeUpdate());
            }
            connection.rollback();
            assertQuantity(connection, 20);

            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("delete from image_t where id = 1"));
                assertEquals(1, statement.executeUpdate("insert into image_t values (1, 40)"));
            }
            connection.commit();
            assertQuantity(connection, 40);

            long[] diagnostics = snapshotDiagnostics();
            assertTrue("expected page-image invalidation after writes", diagnostics[8] > 0L);
            assertTrue("expected fallback/republication after writes", diagnostics[6] > 0L);
            connection.commit();
        }

        shutdownDatabase(DATABASE);

        try (Connection reopened = openDatabase(DATABASE, false)) {
            reopened.setAutoCommit(false);
            resetDiagnostics();
            assertQuantity(reopened, 40); // no image survives database shutdown
            assertTrue("first reopened read should miss the transient image",
                    snapshotDiagnostics()[2] > 0L);
            assertQuantity(reopened, 40);
            assertTrue("second reopened read should hit a newly published image",
                    snapshotDiagnostics()[1] > 0L);
            reopened.commit();
        } finally {
            shutdownDatabase(DATABASE);
        }
    }

    private static void assertQuantity(Connection connection, int expected) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select quantity from image_t where id = ?")) {
            statement.setInt(1, 1);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getInt(1));
                assertFalse(rs.next());
            }
        }
    }

    private static void resetDiagnostics() throws Exception {
        diagnosticSupport().getMethod("reset").invoke(null);
    }

    private static long[] snapshotDiagnostics() throws Exception {
        Method snapshot = diagnosticSupport().getMethod("snapshot");
        return (long[]) snapshot.invoke(null);
    }

    private static Class<?> diagnosticSupport() throws ClassNotFoundException {
        return Class.forName(
                "org.apache.derby.impl.store.raw.data.HeapPageReadImageDiagnosticTestSupport");
    }
}
