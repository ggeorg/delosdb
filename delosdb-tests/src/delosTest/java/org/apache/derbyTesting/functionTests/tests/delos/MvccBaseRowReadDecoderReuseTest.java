/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccBaseRowReadDecoderReuseTest

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
import java.sql.ResultSet;

/** Correctness proof for read-only MVCC base-row version-decoder reuse. */
public final class MvccBaseRowReadDecoderReuseTest extends MvccSqlTestSupport {
    private static final String DATABASE = "mvcc-base-row-read-decoder-reuse-db";
    private static final String INDEXED_SQL =
            "select id, payload from reuse_t --DERBY-PROPERTIES index=reuse_group_idx\n"
                    + "where group_id = ? order by id";

    public void testRepeatedNonCoveringIndexFetchesRemainStable() throws Exception {
        String database = databaseName(DATABASE + "-repeated");
        try (Connection connection = openDatabase(database, true)) {
            createFixture(connection);
            try (PreparedStatement statement = connection.prepareStatement(INDEXED_SQL)) {
                for (int round = 0; round < 20; round++) {
                    int group = round % 5;
                    statement.setInt(1, group);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        int rows = 0;
                        while (resultSet.next()) {
                            int id = resultSet.getInt(1);
                            assertEquals(group, id % 5);
                            assertEquals("payload-" + id, resultSet.getString(2));
                            rows++;
                        }
                        assertEquals(20, rows);
                    }
                }
            }
            connection.commit();
        }
        shutdownDatabase(database);
    }

    public void testHistoricalVisibilityStillWorksThroughNonCoveringIndexFetch() throws Exception {
        String database = databaseName(DATABASE + "-history");
        try (Connection setup = openDatabase(database, true)) {
            createFixture(setup);
        }

        try (Connection reader = openDatabase(database, false);
             Connection writer = openDatabase(database, false)) {
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            assertPayload(reader, 2, 7, "payload-7");

            try (PreparedStatement update = writer.prepareStatement(
                    "update reuse_t set payload = ? where id = ?")) {
                update.setString(1, "payload-new-7");
                update.setInt(2, 7);
                assertEquals(1, update.executeUpdate());
            }
            writer.commit();

            assertPayload(reader, 2, 7, "payload-7");
            reader.rollback();
        }

        try (Connection current = openDatabase(database, false)) {
            assertPayload(current, 2, 7, "payload-new-7");
        }
        shutdownDatabase(database);
    }

    private static void createFixture(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        executeUpdate(connection,
                "create table reuse_t ("
                        + "id int not null primary key, "
                        + "group_id int not null, "
                        + "payload varchar(64) not null) using delos_mvcc");
        executeUpdate(connection, "create index reuse_group_idx on reuse_t(group_id)");
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into reuse_t values (?, ?, ?)")) {
            for (int id = 0; id < 100; id++) {
                insert.setInt(1, id);
                insert.setInt(2, id % 5);
                insert.setString(3, "payload-" + id);
                insert.addBatch();
            }
            int[] counts = insert.executeBatch();
            assertEquals(100, counts.length);
        }
        connection.commit();
    }

    private static void assertPayload(
            Connection connection,
            int group,
            int id,
            String expectedPayload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(INDEXED_SQL)) {
            statement.setInt(1, group);
            try (ResultSet resultSet = statement.executeQuery()) {
                boolean found = false;
                while (resultSet.next()) {
                    if (resultSet.getInt(1) == id) {
                        assertFalse("duplicate id " + id, found);
                        assertEquals(expectedPayload, resultSet.getString(2));
                        found = true;
                    }
                }
                assertTrue("missing id " + id, found);
            }
        }
    }
}
