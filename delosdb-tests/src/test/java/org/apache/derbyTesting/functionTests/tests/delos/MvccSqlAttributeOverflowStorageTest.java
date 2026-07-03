/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlAttributeOverflowStorageTest

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
import java.sql.PreparedStatement;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL proof for MVCC attribute-level overflow descriptors. */
public final class MvccSqlAttributeOverflowStorageTest extends MvccSqlTestSupport {
    public void testLargeValueUsesAttributeOverflowAndSurvivesReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-attribute-overflow-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        String small = "small-inline-value";
        String large = repeated('x', 24000);
        String replacement = repeated('y', 22000);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_attribute_overflow_t "
                    + "(id int primary key, label varchar(32), payload varchar(32672)) using delos_mvcc");
            insertRow(connection, 1, "small", small);
            insertRow(connection, 2, "large", large);
            connection.commit();

            containerId = mvccContainerId(connection, "MVCC_ATTRIBUTE_OVERFLOW_T");
            assertRows(connection,
                    "select id, label, length(payload) from mvcc_attribute_overflow_t order by id",
                    "1|small|" + small.length(),
                    "2|large|" + large.length());
            assertEquals("small value must round trip", small, payloadFor(connection, 1));
            assertEquals("large value must round trip", large, payloadFor(connection, 2));

            assertEquals("only the oversized row should use the attribute-overflow writer",
                    1L, diagnostics.attributeOverflowWriteCountForTesting(0, containerId));
            assertTrue("attribute overflow should store the value bytes outside the row page",
                    diagnostics.attributeOverflowValueBytesForTesting(0, containerId) >= large.length());
            assertTrue("inline attribute-overflow descriptor should be smaller than the value bytes",
                    diagnostics.attributeOverflowInlineRowBytesForTesting(0, containerId)
                            < diagnostics.attributeOverflowValueBytesForTesting(0, containerId));
            assertTrue("attribute overflow should allocate MVCC overflow pages",
                    diagnostics.overflowPageCountForTesting(0, containerId) > 0L);
            diagnostics.assertConsistentForTesting(0, containerId);
            assertTrue("consistency/read path should resolve attribute overflow descriptors",
                    diagnostics.attributeOverflowReadCountForTesting(0, containerId) > 0L);

            updateRow(connection, 2, "large-updated", replacement);
            connection.commit();
            assertEquals("updated large value must round trip", replacement, payloadFor(connection, 2));
            assertTrue("large update should use another attribute-overflow descriptor",
                    diagnostics.attributeOverflowWriteCountForTesting(0, containerId) >= 2L);
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_ATTRIBUTE_OVERFLOW_T");
            assertRows(reopened,
                    "select id, label, length(payload) from mvcc_attribute_overflow_t order by id",
                    "1|small|" + small.length(),
                    "2|large-updated|" + replacement.length());
            assertEquals("small value must survive reopen", small, payloadFor(reopened, 1));
            assertEquals("large value must survive reopen", replacement, payloadFor(reopened, 2));
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            assertTrue("reopen should hydrate attribute-overflow values from descriptors",
                    diagnostics.attributeOverflowReadCountForTesting(0, reopenedContainerId) > 0L);
        }
    }

    private static void insertRow(Connection connection, int id, String label, String payload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into mvcc_attribute_overflow_t values (?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, label);
            statement.setString(3, payload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void updateRow(Connection connection, int id, String label, String payload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "update mvcc_attribute_overflow_t set label = ?, payload = ? where id = ?")) {
            statement.setString(1, label);
            statement.setString(2, payload);
            statement.setInt(3, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String payloadFor(Connection connection, int id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select payload from mvcc_attribute_overflow_t where id = ?")) {
            statement.setInt(1, id);
            try (java.sql.ResultSet rs = statement.executeQuery()) {
                assertTrue("expected row " + id, rs.next());
                return rs.getString(1);
            }
        }
    }

    private static String repeated(char value, int length) {
        return String.valueOf(value).repeat(length);
    }
}
