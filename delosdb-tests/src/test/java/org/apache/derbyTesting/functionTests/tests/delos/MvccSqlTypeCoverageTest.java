/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlTypeCoverageTest

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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL integration tests for delos_mvcc typed Derby row values. */
public final class MvccSqlTypeCoverageTest extends MvccSqlTestSupport {
    public void testCommonDerbySqlTypesRoundTripAcrossUpdateRollbackVacuumAndReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-type-coverage-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_type_t ("
                    + "id int primary key, "
                    + "small_val smallint, "
                    + "big_val bigint, "
                    + "char_val char(3), "
                    + "varchar_val varchar(32), "
                    + "bool_val boolean, "
                    + "dec_val decimal(10,2), "
                    + "double_val double, "
                    + "date_val date, "
                    + "time_val time, "
                    + "ts_val timestamp, "
                    + "nullable_varchar varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create index mvcc_type_varchar_idx on mvcc_type_t(varchar_val)");

            insertTypedRow(connection, typedRowOneInitial());
            insertTypedRow(connection, typedRowTwoWithNulls());
            connection.commit();

            containerId = mvccContainerId(connection, "MVCC_TYPE_T");
            assertMvccConsistent(diagnostics, containerId);

            Savepoint savepoint = connection.setSavepoint("TYPE_SP");
            updateTypedRow(connection, typedRowOneRolledBack());
            insertTypedRow(connection, typedRowThreeRolledBack());
            connection.rollback(savepoint);

            assertTypedRow(connection, 1, typedRowOneInitial());
            assertFalse("rolled-back typed insert must not be visible", hasRow(connection, 3));

            updateTypedRow(connection, typedRowOneCommitted());
            connection.commit();

            assertTypedRow(connection, 1, typedRowOneCommitted());
            assertTypedRow(connection, 2, typedRowTwoWithNulls());
            assertRows(connection,
                    "select id, varchar_val from mvcc_type_t --DERBY-PROPERTIES index=mvcc_type_varchar_idx\n "
                            + "where varchar_val = 'updated-varchar'",
                    "1|updated-varchar");
            assertMvccConsistent(diagnostics, containerId);

            inPlaceCompressTable(connection, "MVCC_TYPE_T");
            connection.commit();
            assertFalse("type coverage vacuum should not be skipped",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertMvccConsistent(diagnostics, containerId);
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_TYPE_T");
            assertMvccConsistent(diagnostics, reopenedContainerId);
            assertTypedRow(reopened, 1, typedRowOneCommitted());
            assertTypedRow(reopened, 2, typedRowTwoWithNulls());
            assertRows(reopened,
                    "select id, varchar_val from mvcc_type_t --DERBY-PROPERTIES index=mvcc_type_varchar_idx\n "
                            + "where varchar_val = 'updated-varchar'",
                    "1|updated-varchar");
        }
    }

    private static TypedRow typedRowOneInitial() {
        return new TypedRow(1, (short) 12, 1234567890123L, "A1B", "initial-varchar", true,
                new BigDecimal("12345.67"), 12.5d,
                Date.valueOf("2026-06-29"), Time.valueOf("12:34:56"),
                Timestamp.valueOf("2026-06-29 12:34:56"), "not-null");
    }

    private static TypedRow typedRowOneRolledBack() {
        return new TypedRow(1, (short) 13, 2222222222222L, "RBK", "rolled-back", false,
                new BigDecimal("22222.22"), 22.25d,
                Date.valueOf("2026-06-30"), Time.valueOf("13:35:57"),
                Timestamp.valueOf("2026-06-30 13:35:57"), "rollback-value");
    }

    private static TypedRow typedRowOneCommitted() {
        return new TypedRow(1, (short) 14, 3333333333333L, "C2D", "updated-varchar", false,
                new BigDecimal("33333.33"), 33.75d,
                Date.valueOf("2026-07-01"), Time.valueOf("14:36:58"),
                Timestamp.valueOf("2026-07-01 14:36:58"), "updated-nullable");
    }

    private static TypedRow typedRowTwoWithNulls() {
        return new TypedRow(2, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static TypedRow typedRowThreeRolledBack() {
        return new TypedRow(3, (short) 3, 3L, "T3R", "insert-rollback", true,
                new BigDecimal("3.33"), 3.0d,
                Date.valueOf("2026-07-03"), Time.valueOf("03:03:03"),
                Timestamp.valueOf("2026-07-03 03:03:03"), "rollback-insert");
    }

    private static void insertTypedRow(Connection connection, TypedRow row) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into mvcc_type_t values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            bindTypedRow(statement, row);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void updateTypedRow(Connection connection, TypedRow row) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update mvcc_type_t set small_val = ?, big_val = ?, char_val = ?, varchar_val = ?, "
                        + "bool_val = ?, dec_val = ?, double_val = ?, date_val = ?, time_val = ?, "
                        + "ts_val = ?, nullable_varchar = ? where id = ?")) {
            bindNullableShort(statement, 1, row.smallValue);
            bindNullableLong(statement, 2, row.bigValue);
            bindNullableChar(statement, 3, row.charValue);
            bindNullableString(statement, 4, row.varcharValue);
            bindNullableBoolean(statement, 5, row.booleanValue);
            bindNullableDecimal(statement, 6, row.decimalValue);
            bindNullableDouble(statement, 7, row.doubleValue);
            bindNullableDate(statement, 8, row.dateValue);
            bindNullableTime(statement, 9, row.timeValue);
            bindNullableTimestamp(statement, 10, row.timestampValue);
            bindNullableString(statement, 11, row.nullableVarchar);
            statement.setInt(12, row.id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void bindTypedRow(PreparedStatement statement, TypedRow row) throws SQLException {
        statement.setInt(1, row.id);
        bindNullableShort(statement, 2, row.smallValue);
        bindNullableLong(statement, 3, row.bigValue);
        bindNullableChar(statement, 4, row.charValue);
        bindNullableString(statement, 5, row.varcharValue);
        bindNullableBoolean(statement, 6, row.booleanValue);
        bindNullableDecimal(statement, 7, row.decimalValue);
        bindNullableDouble(statement, 8, row.doubleValue);
        bindNullableDate(statement, 9, row.dateValue);
        bindNullableTime(statement, 10, row.timeValue);
        bindNullableTimestamp(statement, 11, row.timestampValue);
        bindNullableString(statement, 12, row.nullableVarchar);
    }

    private static void bindNullableShort(PreparedStatement statement, int parameter, Short value) throws SQLException {
        if (value == null) {
            statement.setNull(parameter, Types.SMALLINT);
        } else {
            statement.setShort(parameter, value);
        }
    }

    private static void bindNullableLong(PreparedStatement statement, int parameter, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(parameter, Types.BIGINT);
        } else {
            statement.setLong(parameter, value);
        }
    }


    private static void bindNullableChar(PreparedStatement statement, int parameter, String value) throws SQLException {
        if (value == null) {
            statement.setNull(parameter, Types.CHAR);
        } else {
            statement.setString(parameter, value);
        }
    }

    private static void bindNullableString(PreparedStatement statement, int parameter, String value) throws SQLException {
        if (value == null) {
            statement.setNull(parameter, Types.VARCHAR);
        } else {
            statement.setString(parameter, value);
        }
    }

    private static void bindNullableBoolean(PreparedStatement statement, int parameter, Boolean value) throws SQLException {
        if (value == null) {
            statement.setNull(parameter, Types.BOOLEAN);
        } else {
            statement.setBoolean(parameter, value);
        }
    }

    private static void bindNullableDecimal(PreparedStatement statement, int parameter, BigDecimal value) throws SQLException {
        if (value == null) {
            statement.setNull(parameter, Types.DECIMAL);
        } else {
            statement.setBigDecimal(parameter, value);
        }
    }

    private static void bindNullableDouble(PreparedStatement statement, int parameter, Double value) throws SQLException {
        if (value == null) {
            statement.setNull(parameter, Types.DOUBLE);
        } else {
            statement.setDouble(parameter, value);
        }
    }

    private static void bindNullableDate(PreparedStatement statement, int parameter, Date value) throws SQLException {
        if (value == null) {
            statement.setNull(parameter, Types.DATE);
        } else {
            statement.setDate(parameter, value);
        }
    }

    private static void bindNullableTime(PreparedStatement statement, int parameter, Time value) throws SQLException {
        if (value == null) {
            statement.setNull(parameter, Types.TIME);
        } else {
            statement.setTime(parameter, value);
        }
    }

    private static void bindNullableTimestamp(PreparedStatement statement, int parameter, Timestamp value) throws SQLException {
        if (value == null) {
            statement.setNull(parameter, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(parameter, value);
        }
    }

    private static void assertTypedRow(Connection connection, int id, TypedRow expected) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id, small_val, big_val, char_val, varchar_val, bool_val, dec_val, double_val, "
                        + "date_val, time_val, ts_val, nullable_varchar from mvcc_type_t where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue("expected typed row " + id, rs.next());
                assertEquals(expected.id, rs.getInt(1));
                assertNullableShort(expected.smallValue, rs, 2);
                assertNullableLong(expected.bigValue, rs, 3);
                assertNullableString(expected.charValue, rs, 4);
                assertNullableString(expected.varcharValue, rs, 5);
                assertNullableBoolean(expected.booleanValue, rs, 6);
                assertNullableDecimal(expected.decimalValue, rs, 7);
                assertNullableDouble(expected.doubleValue, rs, 8);
                assertNullableDate(expected.dateValue, rs, 9);
                assertNullableTime(expected.timeValue, rs, 10);
                assertNullableTimestamp(expected.timestampValue, rs, 11);
                assertNullableString(expected.nullableVarchar, rs, 12);
                assertFalse("expected exactly one typed row for id " + id, rs.next());
            }
        }
    }

    private static boolean hasRow(Connection connection, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select id from mvcc_type_t where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void assertNullableShort(Short expected, ResultSet rs, int column) throws SQLException {
        short actual = rs.getShort(column);
        if (expected == null) {
            assertTrue("expected SQL NULL at column " + column, rs.wasNull());
        } else {
            assertEquals(expected.shortValue(), actual);
            assertFalse("unexpected SQL NULL at column " + column, rs.wasNull());
        }
    }

    private static void assertNullableLong(Long expected, ResultSet rs, int column) throws SQLException {
        long actual = rs.getLong(column);
        if (expected == null) {
            assertTrue("expected SQL NULL at column " + column, rs.wasNull());
        } else {
            assertEquals(expected.longValue(), actual);
            assertFalse("unexpected SQL NULL at column " + column, rs.wasNull());
        }
    }

    private static void assertNullableString(String expected, ResultSet rs, int column) throws SQLException {
        String actual = rs.getString(column);
        if (expected == null) {
            assertNull("expected SQL NULL at column " + column, actual);
        } else {
            assertEquals(expected, actual);
        }
    }

    private static void assertNullableBoolean(Boolean expected, ResultSet rs, int column) throws SQLException {
        boolean actual = rs.getBoolean(column);
        if (expected == null) {
            assertTrue("expected SQL NULL at column " + column, rs.wasNull());
        } else {
            assertEquals(expected.booleanValue(), actual);
            assertFalse("unexpected SQL NULL at column " + column, rs.wasNull());
        }
    }

    private static void assertNullableDecimal(BigDecimal expected, ResultSet rs, int column) throws SQLException {
        BigDecimal actual = rs.getBigDecimal(column);
        if (expected == null) {
            assertNull("expected SQL NULL at column " + column, actual);
        } else {
            assertEquals(expected, actual);
        }
    }

    private static void assertNullableDouble(Double expected, ResultSet rs, int column) throws SQLException {
        double actual = rs.getDouble(column);
        if (expected == null) {
            assertTrue("expected SQL NULL at column " + column, rs.wasNull());
        } else {
            assertEquals(expected.doubleValue(), actual, 0.0d);
            assertFalse("unexpected SQL NULL at column " + column, rs.wasNull());
        }
    }

    private static void assertNullableDate(Date expected, ResultSet rs, int column) throws SQLException {
        Date actual = rs.getDate(column);
        if (expected == null) {
            assertNull("expected SQL NULL at column " + column, actual);
        } else {
            assertEquals(expected, actual);
        }
    }

    private static void assertNullableTime(Time expected, ResultSet rs, int column) throws SQLException {
        Time actual = rs.getTime(column);
        if (expected == null) {
            assertNull("expected SQL NULL at column " + column, actual);
        } else {
            assertEquals(expected, actual);
        }
    }

    private static void assertNullableTimestamp(Timestamp expected, ResultSet rs, int column) throws SQLException {
        Timestamp actual = rs.getTimestamp(column);
        if (expected == null) {
            assertNull("expected SQL NULL at column " + column, actual);
        } else {
            assertEquals(expected, actual);
        }
    }

    private static void assertMvccConsistent(DelosStorageDiagnostics diagnostics, long containerId) {
        String summary = diagnostics.consistencySummaryForTesting(0, containerId);
        assertEquals("expected valid durable MVCC state, got " + summary,
                0, diagnostics.consistencyErrorCountForTesting(0, containerId));
        diagnostics.assertConsistentForTesting(0, containerId);
    }

    private static final class TypedRow {
        private final int id;
        private final Short smallValue;
        private final Long bigValue;
        private final String charValue;
        private final String varcharValue;
        private final Boolean booleanValue;
        private final BigDecimal decimalValue;
        private final Double doubleValue;
        private final Date dateValue;
        private final Time timeValue;
        private final Timestamp timestampValue;
        private final String nullableVarchar;

        private TypedRow(int id, Short smallValue, Long bigValue, String charValue, String varcharValue,
                Boolean booleanValue, BigDecimal decimalValue, Double doubleValue, Date dateValue, Time timeValue,
                Timestamp timestampValue, String nullableVarchar) {
            this.id = id;
            this.smallValue = smallValue;
            this.bigValue = bigValue;
            this.charValue = charValue;
            this.varcharValue = varcharValue;
            this.booleanValue = booleanValue;
            this.decimalValue = decimalValue;
            this.doubleValue = doubleValue;
            this.dateValue = dateValue;
            this.timeValue = timeValue;
            this.timestampValue = timestampValue;
            this.nullableVarchar = nullableVarchar;
        }
    }
}
