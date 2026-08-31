/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccBaseFetchPagePrefetchTest

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
import java.sql.Statement;

/** Correctness coverage for the experimental bounded MVCC base-fetch page prefetch. */
public final class MvccBaseFetchPagePrefetchTest extends MvccSqlTestSupport {
    private static final String PREFETCH_PROPERTY =
            "delosdb.experimental.mvccBaseFetchPagePrefetch";
    private static final String TABLE = "A6_PREFETCH_T";
    private static final String INDEX = "A6_PREFETCH_ID_IDX";

    public void testRangeOrderAndReadCommittedStatementSnapshot() throws Exception {
        String database = databaseName("range-snapshot");
        try (SystemPropertyScope ignored = setSystemProperty(PREFETCH_PROPERTY, "true");
             Connection reader = openDatabase(database, true);
             Connection writer = openDatabase(database, false)) {
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);
            createFixture(reader);
            executeUpdate(reader,
                    "call syscs_util.syscs_set_database_property("
                            + "'derby.language.bulkFetchDefault', '16')");
            executeUpdate(reader, "call syscs_util.syscs_set_runtimestatistics(1)");
            reader.commit();

            try (PreparedStatement range = reader.prepareStatement(rangeSql())) {
                range.setInt(1, 1);
                range.setInt(2, 96);
                try (ResultSet rows = range.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                    assertEquals("payload-1", rows.getString(2));

                    try (PreparedStatement update = writer.prepareStatement(
                            "update " + TABLE + " set payload = ? where id = ?")) {
                        update.setString(1, "writer-committed");
                        update.setInt(2, 48);
                        assertEquals(1, update.executeUpdate());
                    }
                    writer.commit();

                    int expectedId = 2;
                    while (rows.next()) {
                        assertEquals(expectedId, rows.getInt(1));
                        String expectedPayload = "payload-" + expectedId;
                        assertEquals(expectedPayload, rows.getString(2));
                        expectedId++;
                    }
                    assertEquals(97, expectedId);
                }
            }
            String statistics = runtimeStatistics(reader);
            assertTrue("A6 test must use IndexRowToBaseRow; statistics=" + statistics,
                    statistics.contains("Index Row to Base Row ResultSet"));
            assertTrue("A6 test must use the forced non-covering index; statistics=" + statistics,
                    statistics.contains(INDEX));
            reader.commit();

            assertRows(reader,
                    "select payload from " + TABLE + " where id = 48",
                    "writer-committed");
            reader.commit();
        }
        shutdownDatabase(database);
    }

    private static void createFixture(Connection connection) throws Exception {
        executeUpdate(connection,
                "create table " + TABLE
                        + " (id int not null, payload varchar(64) not null) using delos_mvcc");
        executeUpdate(connection, "create unique index " + INDEX + " on " + TABLE + " (id)");
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + TABLE + " values (?, ?)")) {
            for (int id = 1; id <= 96; id++) {
                insert.setInt(1, id);
                insert.setString(2, "payload-" + id);
                insert.addBatch();
            }
            assertEquals(96, successfulBatchRows(insert.executeBatch()));
        }
        connection.commit();
    }


    private static String runtimeStatistics(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "values syscs_util.syscs_get_runtimestatistics()")) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static int successfulBatchRows(int[] counts) {
        int successful = 0;
        for (int count : counts) {
            if (count >= 0 || count == Statement.SUCCESS_NO_INFO) {
                successful++;
            }
        }
        return successful;
    }

    private static String rangeSql() {
        return "select id, payload from " + TABLE
                + " --DERBY-PROPERTIES index=" + INDEX + "\n"
                + "where id between ? and ? order by id";
    }

    private static String databaseName(String suffix) {
        return "mvcc_base_fetch_page_prefetch_" + suffix + '_'
                + Long.toUnsignedString(System.nanoTime());
    }
}
