/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccDrdaNetworkClientTest

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

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import junit.framework.Test;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.BaseTestSuite;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.TestConfiguration;

/** End-to-end DRDA/JDBC network-client gate for delos_mvcc tables. */
public final class MvccDrdaNetworkClientTest extends BaseJDBCTestCase {
    private static final String TABLE = "mvcc_drda_client_t";
    private static final String TABLE_UPPER = "MVCC_DRDA_CLIENT_T";

    public MvccDrdaNetworkClientTest(String name) {
        super(name);
    }

    public static Test suite() {
        BaseTestSuite suite = new BaseTestSuite(MvccDrdaNetworkClientTest.class);
        return TestConfiguration.clientServerDecorator(new CleanDatabaseTestSetup(suite));
    }

    public void testNetworkClientPreparedLobIndexRollbackVacuumAndReopen()
            throws Exception {
        assertTrue("test must run through Derby network client",
                getTestConfiguration().getJDBCClient().isDerbyNetClient());
        assertTrue("configured JDBC URL should be network-client URL: "
                        + getTestConfiguration().getJDBCUrl(),
                getTestConfiguration().getJDBCUrl().startsWith("jdbc:derby://"));

        DelosStorageDiagnostics diagnostics = MvccSqlTestSupport.mvccDiagnostics();
        byte[] originalBlob = blobPayload(66_000, 13);
        String originalClob = clobPayload(66_000, 'b');
        byte[] updatedBlob = blobPayload(72_000, 29);
        String updatedClob = clobPayload(72_000, 'u');
        byte[] rolledBackBlob = blobPayload(68_000, 47);
        String rolledBackClob = clobPayload(68_000, 'r');
        byte[] deletedBlob = blobPayload(70_000, 61);
        String deletedClob = clobPayload(70_000, 'd');
        long containerId;
        long overflowPagesAfterVacuum;

        try (Connection connection = openDefaultConnection()) {
            connection.setAutoCommit(false);
            createSchema(connection);
            connection.commit();
            containerId = MvccSqlTestSupport.mvccContainerId(connection, TABLE_UPPER);

            insertLobRow(connection, 1, 10, originalBlob, originalClob, "original");
            insertLobRow(connection, 2, 20, deletedBlob, deletedClob, "delete-me");
            connection.commit();
            assertLobRow(connection, 1, 10, originalBlob, originalClob, "original");
            assertLobRow(connection, 2, 20, deletedBlob, deletedClob, "delete-me");
            assertIndexProbe(connection, 10, 1);
            assertConsistent(diagnostics, containerId);
            connection.rollback();
        }

        assertTrue("network-client large LOB insert should allocate MVCC overflow pages",
                diagnostics.overflowPageCountForTesting(0, containerId) > 0L);

        try (Connection connection = openDefaultConnection()) {
            connection.setAutoCommit(false);
            Savepoint savepoint = connection.setSavepoint("DRDA_MVCC_SP");
            updateLobRow(connection, 1, 99, rolledBackBlob, rolledBackClob,
                    "rolled-back-update");
            insertLobRow(connection, 3, 30, rolledBackBlob, rolledBackClob,
                    "rolled-back-insert");
            executeUpdate(connection, "delete from " + TABLE + " where id = 1");
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            assertLobRow(connection, 1, 10, originalBlob, originalClob, "original");
            assertNoRow(connection, 3);

            updateLobRow(connection, 1, 11, updatedBlob, updatedClob, "updated");
            executeUpdate(connection, "delete from " + TABLE + " where id = 2");
            connection.commit();

            assertLobRow(connection, 1, 11, updatedBlob, updatedClob, "updated");
            assertNoRow(connection, 2);
            assertNoRow(connection, 3);
            assertGroupedState(connection);
            assertIndexProbe(connection, 11, 1);
            assertConsistent(diagnostics, containerId);
            connection.rollback();
        }

        try (Connection connection = openDefaultConnection()) {
            connection.setAutoCommit(false);
            MvccSqlTestSupport.inPlaceCompressTable(connection, TABLE_UPPER);
            connection.commit();
            assertEquals("in-place compress must keep MVCC base container stable",
                    containerId, MvccSqlTestSupport.mvccContainerId(connection, TABLE_UPPER));
            assertFalse("network-client MVCC vacuum should not be skipped",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertLobRow(connection, 1, 11, updatedBlob, updatedClob, "updated");
            assertNoRow(connection, 2);
            assertNoRow(connection, 3);
            assertConsistent(diagnostics, containerId);
            overflowPagesAfterVacuum = diagnostics.overflowPageCountForTesting(0, containerId);
            assertTrue("vacuum must retain live network-client LOB overflow chains",
                    overflowPagesAfterVacuum > 0L);
            connection.rollback();
        }

        TestConfiguration.getCurrent().shutdownDatabase();
        diagnostics.clearRuntimeStateForTesting();

        try (Connection reopened = openDefaultConnection()) {
            reopened.setAutoCommit(false);
            long reopenedContainerId = MvccSqlTestSupport.mvccContainerId(reopened, TABLE_UPPER);
            assertEquals("network-client reopen should preserve MVCC overflow page count",
                    overflowPagesAfterVacuum,
                    diagnostics.overflowPageCountForTesting(0, reopenedContainerId));
            assertLobRow(reopened, 1, 11, updatedBlob, updatedClob, "updated");
            assertNoRow(reopened, 2);
            assertNoRow(reopened, 3);
            assertGroupedState(reopened);
            assertIndexProbe(reopened, 11, 1);
            assertConsistent(diagnostics, reopenedContainerId);
            reopened.rollback();
        }
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + TABLE + " ("
                    + "id int primary key, "
                    + "bucket int not null, "
                    + "blob_payload blob(262144), "
                    + "clob_payload clob(262144), "
                    + "note varchar(64)) using delos_mvcc");
            statement.executeUpdate("create index mvcc_drda_client_bucket_idx on "
                    + TABLE + "(bucket)");
        }
    }

    private static int executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static void insertLobRow(
            Connection connection,
            int id,
            int bucket,
            byte[] blobPayload,
            String clobPayload,
            String note) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into " + TABLE + " values (?, ?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setInt(2, bucket);
            statement.setBinaryStream(3, new ByteArrayInputStream(blobPayload), blobPayload.length);
            statement.setCharacterStream(4, new StringReader(clobPayload), clobPayload.length());
            statement.setString(5, note);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void updateLobRow(
            Connection connection,
            int id,
            int bucket,
            byte[] blobPayload,
            String clobPayload,
            String note) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update " + TABLE
                        + " set bucket = ?, blob_payload = ?, clob_payload = ?, note = ?"
                        + " where id = ?")) {
            statement.setInt(1, bucket);
            statement.setBinaryStream(2, new ByteArrayInputStream(blobPayload), blobPayload.length);
            statement.setCharacterStream(3, new StringReader(clobPayload), clobPayload.length());
            statement.setString(4, note);
            statement.setInt(5, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertLobRow(
            Connection connection,
            int id,
            int expectedBucket,
            byte[] expectedBlob,
            String expectedClob,
            String expectedNote) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select bucket, blob_payload, clob_payload, note from " + TABLE
                        + " where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue("expected row " + id, rs.next());
                assertEquals("bucket mismatch for row " + id, expectedBucket, rs.getInt(1));
                assertTrue("BLOB mismatch for row " + id,
                        Arrays.equals(expectedBlob, rs.getBytes(2)));
                assertEquals("CLOB mismatch for row " + id, expectedClob, rs.getString(3));
                assertEquals("note mismatch for row " + id, expectedNote, rs.getString(4));
                assertFalse("expected one row " + id, rs.next());
            }
        }
    }

    private static void assertNoRow(Connection connection, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from " + TABLE + " where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertFalse("expected no row " + id, rs.next());
            }
        }
    }

    private static void assertIndexProbe(Connection connection, int bucket, int expectedId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from " + TABLE + " where bucket = ?")) {
            statement.setInt(1, bucket);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue("expected indexed bucket " + bucket, rs.next());
                assertEquals(expectedId, rs.getInt(1));
                assertFalse("expected one indexed bucket " + bucket, rs.next());
            }
        }
    }

    private static void assertGroupedState(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select bucket, count(*), sum(id) from " + TABLE
                             + " group by bucket order by bucket")) {
            assertTrue("expected grouped row", rs.next());
            assertEquals(11, rs.getInt(1));
            assertEquals(1, rs.getInt(2));
            assertEquals(1, rs.getInt(3));
            assertFalse("expected one grouped row", rs.next());
        }
    }

    private static void assertConsistent(DelosStorageDiagnostics diagnostics, long containerId) {
        diagnostics.assertConsistentForTesting(0, containerId);
        assertEquals("expected no MVCC consistency errors", 0,
                diagnostics.consistencyDiagnosticsForTesting(0, containerId).errorCount());
    }

    private static byte[] blobPayload(int size, int seed) {
        byte[] payload = new byte[size];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((seed + (i * 23)) & 0xff);
        }
        return payload;
    }

    private static String clobPayload(int size, char base) {
        StringBuilder builder = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            builder.append((char) (base + (i % 17)));
        }
        return builder.toString();
    }
}
