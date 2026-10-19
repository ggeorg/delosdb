/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.ImportObjectDeserializationFilterTest

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.derby.shared.common.security.DelosObjectInputFilters;

/**
 * SQL gate for the IMPORT UDT boundary: imported UDT values reject every
 * application class unless the documented allow-list names it.
 */
public final class ImportObjectDeserializationFilterTest extends MvccSqlTestSupport {
    public void testDefaultRejectsImportedUdtAndAllowListAdmitsIt() throws Exception {
        String databaseName = databaseName("import-object-filter-db");
        Path exportFile = databasePath(databaseName).resolveSibling("import-object-filter.dat");

        try (SystemPropertyScope filter = clearSystemProperty(
                     DelosObjectInputFilters.IMPORT_FILTER_PROPERTY);
             SystemPropertyScope fallback = clearSystemProperty(
                     DelosObjectInputFilters.GENERAL_FILTER_PROPERTY);
             SystemPropertyScope compatibility = clearSystemProperty(
                     DelosObjectInputFilters.COMPATIBILITY_MODE_PROPERTY);
             Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create type import_payload external name '"
                    + ImportPayload.class.getName() + "' language java");
            executeUpdate(connection, "create table import_source (id int, payload import_payload)");
            executeUpdate(connection, "create table import_target (id int, payload import_payload)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into import_source values (1, ?)")) {
                insert.setObject(1, new ImportPayload(42));
                insert.executeUpdate();
            }
            connection.commit();
            Files.deleteIfExists(exportFile);
            exportTable(connection, "IMPORT_SOURCE", exportFile);

            try {
                importTable(connection, "IMPORT_TARGET", exportFile);
                fail("Expected IMPORT to reject an application UDT class by default");
            } catch (SQLException expected) {
                assertTrue("expected object input filter rejection, got: " + expected,
                        containsMessage(expected, "filter status: REJECTED"));
                connection.rollback();
            }
            assertRows(connection, "select count(*) from import_target", "0");

            filter.set(ImportPayload.class.getName() + ";java.base/*;!*");
            importTable(connection, "IMPORT_TARGET", exportFile);
            connection.commit();
            assertRows(connection, "select id from import_target", "1");
            assertEquals("imported payload value", 42, ((ImportPayload) singlePayload(connection)).value);
            connection.commit();
        } finally {
            Files.deleteIfExists(exportFile);
        }
    }

    private static void exportTable(Connection connection, String table, Path file) throws SQLException {
        try (CallableStatement call = connection.prepareCall(
                "call SYSCS_UTIL.SYSCS_EXPORT_TABLE(null, ?, ?, null, null, null)")) {
            call.setString(1, table);
            call.setString(2, file.toString());
            call.execute();
        }
    }

    private static void importTable(Connection connection, String table, Path file) throws SQLException {
        try (CallableStatement call = connection.prepareCall(
                "call SYSCS_UTIL.SYSCS_IMPORT_TABLE(null, ?, ?, null, null, null, 0)")) {
            call.setString(1, table);
            call.setString(2, file.toString());
            call.execute();
        }
    }

    private static Object singlePayload(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("select payload from import_target");
             ResultSet rows = select.executeQuery()) {
            assertTrue("expected an imported row", rows.next());
            return rows.getObject(1);
        }
    }

    public static final class ImportPayload implements Serializable {
        private static final long serialVersionUID = 1L;
        private final int value;

        public ImportPayload(int value) {
            this.value = value;
        }
    }
}
