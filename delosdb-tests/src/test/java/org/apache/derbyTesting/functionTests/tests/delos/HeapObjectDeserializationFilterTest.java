/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapObjectDeserializationFilterTest

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

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.derby.iapi.services.io.DelosHeapObjectDeserializationFilter;

/** SQL gate for optional Derby-compatible heap JAVA_OBJECT deserialization filtering. */
public final class HeapObjectDeserializationFilterTest extends MvccSqlTestSupport {
    public void testDefaultModePreservesDerbyHeapJavaObjectBehavior() throws Exception {
        String databaseName = databaseName("heap-object-filter-default-db");
        String oldFilter = System.getProperty(DelosHeapObjectDeserializationFilter.FILTER_PROPERTY);
        System.clearProperty(DelosHeapObjectDeserializationFilter.FILTER_PROPERTY);

        try {
            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                createSerializableHeapTable(connection, "heap_object_default_t");
                insertPayload(connection, "heap_object_default_t", 1, new AllowedPayload(10));
                insertPayload(connection, "heap_object_default_t", 2, new BlockedPayload(20));
                connection.commit();

                assertPayload(connection, "heap_object_default_t", 1, AllowedPayload.class, 10);
                assertPayload(connection, "heap_object_default_t", 2, BlockedPayload.class, 20);
                connection.commit();
            }
        } finally {
            restoreFilterProperty(oldFilter);
        }
    }

    public void testStrictModeAllowsConfiguredTypeAndBlocksUnlistedType() throws Exception {
        String databaseName = databaseName("heap-object-filter-strict-db");
        String oldFilter = System.getProperty(DelosHeapObjectDeserializationFilter.FILTER_PROPERTY);
        System.clearProperty(DelosHeapObjectDeserializationFilter.FILTER_PROPERTY);

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            createSerializableHeapTable(connection, "heap_object_strict_t");
            insertPayload(connection, "heap_object_strict_t", 1, new AllowedPayload(100));
            insertPayload(connection, "heap_object_strict_t", 2, new BlockedPayload(200));
            connection.commit();

            System.setProperty(DelosHeapObjectDeserializationFilter.FILTER_PROPERTY,
                    AllowedPayload.class.getName() + ";java.base/*;!*" );

            assertPayload(connection, "heap_object_strict_t", 1, AllowedPayload.class, 100);
            assertBlockedByFilter(connection, "heap_object_strict_t", 2);
            connection.rollback();
        } finally {
            restoreFilterProperty(oldFilter);
        }
    }

    public void testMvccStillRejectsJavaObjectRows() throws Exception {
        String databaseName = databaseName("heap-object-filter-mvcc-boundary-db");
        String oldFilter = System.getProperty(DelosHeapObjectDeserializationFilter.FILTER_PROPERTY);
        System.setProperty(DelosHeapObjectDeserializationFilter.FILTER_PROPERTY,
                AllowedPayload.class.getName() + ";java.base/*;!*" );

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            createSerializableMvccTable(connection, "mvcc_object_filter_boundary_t");
            try {
                insertPayload(connection, "mvcc_object_filter_boundary_t", 1, new AllowedPayload(7));
                connection.commit();
            } catch (SQLException expected) {
                assertTrue("expected clean JAVA_OBJECT/UserType boundary failure, got: " + expected,
                        containsMessage(expected, "JAVA_OBJECT")
                                || containsMessage(expected, "UserType")
                                || containsMessage(expected, "unsupported"));
                rollbackAfterExpectedCommitFailure(connection);
                return;
            }
            fail("Expected delos_mvcc to keep rejecting JAVA_OBJECT durable row values");
        } finally {
            restoreFilterProperty(oldFilter);
        }
    }

    private static void createSerializableHeapTable(Connection connection, String tableName) throws SQLException {
        executeUpdate(connection,
                "create type heap_java_serializable external name 'java.io.Serializable' language java");
        executeUpdate(connection,
                "create table " + tableName + " (id int primary key, payload heap_java_serializable)");
    }

    private static void createSerializableMvccTable(Connection connection, String tableName) throws SQLException {
        executeUpdate(connection,
                "create type mvcc_filter_java_serializable external name 'java.io.Serializable' language java");
        executeUpdate(connection,
                "create table " + tableName + " (id int primary key, payload mvcc_filter_java_serializable) "
                        + "using delos_mvcc");
    }

    private static void rollbackAfterExpectedCommitFailure(Connection connection) {
        try {
            if (!connection.isClosed()) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // Derby may close/invalidate the embedded connection after a failed
            // commit. The expected boundary failure has already been asserted;
            // cleanup must not mask it with "No current connection".
        }
    }

    private static void insertPayload(Connection connection, String tableName, int id, Serializable payload)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into " + tableName + " values (?, ?)")) {
            statement.setInt(1, id);
            statement.setObject(2, payload);
            statement.executeUpdate();
        }
    }

    private static void assertPayload(
            Connection connection,
            String tableName,
            int id,
            Class<?> expectedClass,
            int expectedValue) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select payload from " + tableName + " where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue("expected payload row " + id, rs.next());
                Object payload = rs.getObject(1);
                assertTrue("unexpected payload class: " + payload,
                        expectedClass.isInstance(payload));
                assertEquals("payload value", expectedValue, ((PayloadValue) payload).value());
                assertFalse("expected one payload row " + id, rs.next());
            }
        }
    }

    private static void assertBlockedByFilter(Connection connection, String tableName, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select payload from " + tableName + " where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue("expected blocked payload row " + id, rs.next());
                rs.getObject(1);
                fail("Expected strict heap object deserialization filter to reject blocked payload");
            }
        } catch (SQLException expected) {
            assertTrue("expected object input filter rejection, got: " + expected,
                    containsMessage(expected, "filter")
                            || containsMessage(expected, "REJECTED")
                            || containsMessage(expected, "InvalidClassException"));
        }
    }

    private static void restoreFilterProperty(String oldFilter) {
        if (oldFilter == null) {
            System.clearProperty(DelosHeapObjectDeserializationFilter.FILTER_PROPERTY);
        } else {
            System.setProperty(DelosHeapObjectDeserializationFilter.FILTER_PROPERTY, oldFilter);
        }
    }

    private interface PayloadValue {
        int value();
    }

    public static final class AllowedPayload implements Serializable, PayloadValue {
        private static final long serialVersionUID = 1L;
        private final int value;

        public AllowedPayload(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }
    }

    public static final class BlockedPayload implements Serializable, PayloadValue {
        private static final long serialVersionUID = 1L;
        private final int value;

        public BlockedPayload(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }
    }
}
