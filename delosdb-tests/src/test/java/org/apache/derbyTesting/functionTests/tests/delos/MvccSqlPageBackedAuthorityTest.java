/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPageBackedAuthorityTest

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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL gate proving page-backed committed MVCC state matches committed SQL state. */
public final class MvccSqlPageBackedAuthorityTest extends MvccSqlTestSupport {
    public void testPageBackedCommittedImageMatchesSqlCommittedState() throws Exception {
        String databaseName = databaseName("mvcc-page-backed-authority-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table authority_t "
                    + "(id int primary key, code varchar(32), payload varchar(128)) using delos_mvcc");
            executeUpdate(connection, "create index authority_code_idx on authority_t(code)");
            executeUpdate(connection, "insert into authority_t values (1, 'alpha', 'payload-1')");
            executeUpdate(connection, "insert into authority_t values (2, 'beta', 'payload-2')");
            executeUpdate(connection, "insert into authority_t values (3, 'gamma', 'payload-3')");
            connection.commit();

            containerId = mvccContainerId(connection, "AUTHORITY_T");
            assertPageBackedImageMatchesSql(connection, diagnostics, containerId);

            executeUpdate(connection, "update authority_t set code = 'alpha-2', payload = 'payload-1b' where id = 1");
            executeUpdate(connection, "delete from authority_t where id = 2");
            executeUpdate(connection, "insert into authority_t values (4, 'delta', 'payload-4')");
            connection.commit();

            assertPageBackedImageMatchesSql(connection, diagnostics, containerId);
            diagnostics.assertConsistentForTesting(0, containerId);

            inPlaceCompressTable(connection, "AUTHORITY_T");
            connection.commit();
            assertPageBackedImageMatchesSql(connection, diagnostics, containerId);
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            long reopenedContainerId = mvccContainerId(reopened, "AUTHORITY_T");
            assertPageBackedImageMatchesSql(reopened, diagnostics, reopenedContainerId);
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            reopened.commit();
        }
    }

    private static void assertPageBackedImageMatchesSql(
            Connection connection,
            DelosStorageDiagnostics diagnostics,
            long containerId) throws SQLException {
        List<String> sqlRows = sqlCommittedRows(connection);
        List<String> pageBackedRows = diagnostics.pageBackedVisibleRowSummariesForTesting(0, containerId);
        assertEquals("page-backed committed image must match SQL committed state", sqlRows, pageBackedRows);
        assertTrue("page-backed logical row count may retain deleted row identities, "
                        + "but must cover the committed visible SQL rows",
                diagnostics.logicalRowCountForTesting(0, containerId) >= sqlRows.size());
    }

    private static List<String> sqlCommittedRows(Connection connection) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select id, code, payload from authority_t order by id")) {
            while (rs.next()) {
                String id = value(rs.getString(1));
                rows.add(id + "|" + id + "|" + value(rs.getString(2)) + "|" + value(rs.getString(3)));
            }
        }
        return List.copyOf(rows);
    }

    private static String value(String value) {
        return value == null ? "<null>" : value;
    }
}
