/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlTestSupport

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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

import junit.framework.TestCase;

/** Shared JDBC helpers for delos_mvcc SQL integration suites. */
abstract class MvccSqlTestSupport extends TestCase {
    protected static Connection openDatabase(String databaseName, boolean create) throws SQLException {
        return DriverManager.getConnection("jdbc:derby:" + databaseName + (create ? ";create=true" : ""));
    }

    protected static void shutdownDatabase(String databaseName) throws SQLException {
        try {
            DriverManager.getConnection("jdbc:derby:" + databaseName + ";shutdown=true");
            fail("Database shutdown should throw the normal Derby shutdown exception");
        } catch (SQLException e) {
            if (!"08006".equals(e.getSQLState())) {
                throw e;
            }
        }
    }

    protected static int executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    protected static void assertRows(Connection connection, String sql, String... expectedRows) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        row.append('|');
                    }
                    row.append(rs.getString(i));
                }
                rows.add(row.toString());
            }
        }
        assertEquals(List.of(expectedRows), rows);
    }


    protected interface SqlAction {
        void run() throws SQLException;
    }

    protected static void assertWriteConflict(SqlAction action) throws SQLException {
        try {
            action.run();
            fail("Expected a deterministic MVCC write conflict");
        } catch (SQLException expected) {
            assertTrue("expected a Derby-wrapped MVCC write conflict, got: " + expected,
                    containsMessage(expected, "conflict")
                            || containsMessage(expected, "already deleted")
                            || containsMessage(expected, "not visible"));
        }
    }


    protected static void assertDuplicateKey(SqlAction action) throws SQLException {
        try {
            action.run();
            fail("Expected duplicate-key violation");
        } catch (SQLException expected) {
            assertTrue("expected duplicate-key violation, got: " + expected,
                    "23505".equals(expected.getSQLState())
                            || containsMessage(expected, "duplicate")
                            || containsMessage(expected, "constraint")
                            || containsMessage(expected, "primary key"));
        }
    }


    protected static void assertDuplicateKeyOrWriteConflict(SqlAction action) throws SQLException {
        try {
            action.run();
            fail("Expected duplicate-key violation or deterministic MVCC write conflict");
        } catch (SQLException expected) {
            assertTrue("expected duplicate-key violation or deterministic MVCC write conflict, got: " + expected,
                    "23505".equals(expected.getSQLState())
                            || containsMessage(expected, "duplicate")
                            || containsMessage(expected, "constraint")
                            || containsMessage(expected, "primary key")
                            || containsMessage(expected, "conflict")
                            || containsMessage(expected, "not visible")
                            || containsMessage(expected, "lock"));
        }
    }


    protected static void rollbackAfterExpectedConflict(Connection connection) throws SQLException {
        try {
            connection.rollback();
        } catch (SQLException e) {
            if (!"08003".equals(e.getSQLState()) && !"X0Y67".equals(e.getSQLState())) {
                throw e;
            }
        }
    }


    protected static DelosStorageDiagnostics mvccDiagnostics() {
        return DelosStorageDiagnosticsRegistry.mvcc();
    }

    protected static long mvccContainerId(Connection connection, String tableName) throws SQLException {
        String sql = "select c.conglomeratenumber "
                + "from sys.sysconglomerates c, sys.systables t, sys.sysschemas s "
                + "where c.tableid = t.tableid "
                + "and t.schemaid = s.schemaid "
                + "and s.schemaname = 'APP' "
                + "and t.tablename = ? "
                + "and c.isindex = false";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue("expected MVCC base conglomerate for table " + tableName, rs.next());
                long containerId = rs.getLong(1);
                assertFalse("expected one MVCC base conglomerate for table " + tableName, rs.next());
                return containerId;
            }
        }
    }

    protected static void inPlaceCompressTable(Connection connection, String tableName) throws SQLException {
        executeStatement(connection, "call syscs_util.syscs_inplace_compress_table('APP', '"
                + tableName
                + "', 1, 0, 0)");
    }

    protected static void executeStatement(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }




    protected static long inheritedMvccStateFileCount(String databaseName) throws IOException {
        Path inheritedStore = new File(databaseName).toPath()
                .resolve("delos_mvcc")
                .resolve("inherited-store");
        if (!Files.exists(inheritedStore)) {
            return 0L;
        }
        try (var paths = Files.walk(inheritedStore)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    protected static boolean containsMessage(Throwable throwable, String needle) {
        String lowerNeedle = needle.toLowerCase();
        for (Throwable current = throwable; current != null; current = nextThrowable(current)) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains(lowerNeedle)) {
                return true;
            }
        }
        return false;
    }

    protected static Throwable nextThrowable(Throwable throwable) {
        if (throwable instanceof SQLException sqlException && sqlException.getNextException() != null) {
            return sqlException.getNextException();
        }
        return throwable.getCause();
    }

    protected static String databaseName(String name) {
        return new File(name).getPath();
    }

}
