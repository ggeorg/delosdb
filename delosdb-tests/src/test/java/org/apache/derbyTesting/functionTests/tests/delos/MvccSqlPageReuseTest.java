/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPageReuseTest

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStoragePageDiagnostics;
import org.apache.derby.iapi.store.types.DelosVacuumOutcome;

/** SQL integration tests for explicit delos_mvcc free-page reuse after vacuum. */
public final class MvccSqlPageReuseTest extends MvccSqlTestSupport {
    public void testVacuumMarksReusablePagesAndNextInsertConsumesOne() throws Exception {
        String databaseName = databaseName("mvcc-sql-page-reuse-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;
        long pagesAfterReuse;
        long reusablePagesAfterReuse;
        Path reusablePageIndexFile;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_page_reuse_t "
                    + "(id int primary key, payload varchar(2600)) using delos_mvcc");
            insertPayload(connection, 1, payload('a', 2400));
            insertPayload(connection, 2, payload('b', 2400));
            connection.commit();

            containerId = mvccContainerId(connection, "MVCC_PAGE_REUSE_T");
            DelosStoragePageDiagnostics pages = diagnostics.pageDiagnosticsForTesting(0, containerId);
            long initialPages = pages.pageCount();
            assertTrue("expected at least one MVCC page after insert", initialPages >= 1L);
            assertEquals("fresh table should have two physical versions", 2,
                    pages.physicalVersionCount());
            assertEquals("fresh table should not start with reusable pages", 0L,
                    pages.reusablePageCount());
            diagnostics.assertConsistentForTesting(0, containerId);

            for (int round = 1; round <= 5; round++) {
                updatePayload(connection, 1, payload((char) ('c' + round), 2400));
                updatePayload(connection, 2, payload((char) ('h' + round), 2400));
                connection.commit();
            }

            pages = diagnostics.pageDiagnosticsForTesting(0, containerId);
            long pagesBeforeVacuum = pages.pageCount();
            int versionsBeforeVacuum = pages.physicalVersionCount();
            assertEquals("two rows updated five times should leave twelve physical versions",
                    12, versionsBeforeVacuum);
            assertTrue("expected repeated large updates to occupy more pages before vacuum, initial="
                            + initialPages + ", before=" + pagesBeforeVacuum,
                    pagesBeforeVacuum > initialPages);
            assertEquals("pre-vacuum append-only workload should not expose reusable pages", 0L,
                    pages.reusablePageCount());
            diagnostics.assertConsistentForTesting(0, containerId);

            inPlaceCompressTable(connection, "MVCC_PAGE_REUSE_T");
            connection.commit();

            pages = diagnostics.pageDiagnosticsForTesting(0, containerId);
            long pagesAfterVacuum = pages.pageCount();
            long reusablePagesAfterVacuum = pages.reusablePageCount();
            reusablePageIndexFile = diagnostics.reusablePageIndexFileForTesting(0, containerId);
            DelosVacuumOutcome vacuum = diagnostics.lastVacuumOutcomeForTesting(0, containerId);
            assertFalse("vacuum should not be skipped for the page-reuse workload", vacuum.skipped());
            assertEquals("vacuum should collapse physical versions to the two visible rows",
                    2, pages.physicalVersionCount());
            assertEquals("first free-page milestone preserves page-volume capacity instead of truncating it",
                    pagesBeforeVacuum, pagesAfterVacuum);
            assertTrue("vacuum should mark compacted-away MVCC pages reusable; pages="
                            + pagesAfterVacuum + ", reusable=" + reusablePagesAfterVacuum,
                    reusablePagesAfterVacuum > 0L);
            assertTrue("vacuum should persist the reusable-page allocation index",
                    Files.exists(reusablePageIndexFile));
            diagnostics.assertConsistentForTesting(0, containerId);

            insertPayload(connection, 3, payload('z', 2400));
            connection.commit();

            pages = diagnostics.pageDiagnosticsForTesting(0, containerId);
            pagesAfterReuse = pages.pageCount();
            reusablePagesAfterReuse = pages.reusablePageCount();
            assertEquals("post-vacuum insert should consume a reusable page instead of extending the page volume",
                    pagesAfterVacuum, pagesAfterReuse);
            assertTrue("post-vacuum insert should consume at least one reusable page; before="
                            + reusablePagesAfterVacuum + ", after=" + reusablePagesAfterReuse,
                    reusablePagesAfterReuse < reusablePagesAfterVacuum);
            assertTrue("reusable-page allocation index should remain durable after reuse",
                    Files.exists(reusablePageIndexFile));
            assertEquals(3, pages.physicalVersionCount());
            diagnostics.assertConsistentForTesting(0, containerId);
            assertRows(connection,
                    "select id, length(payload) from mvcc_page_reuse_t order by id",
                    "1|2400",
                    "2|2400",
                    "3|2400");
            connection.rollback();
        }

        shutdownDatabase(databaseName);
        corruptLastByte(reusablePageIndexFile);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_PAGE_REUSE_T");
            DelosStoragePageDiagnostics pages = diagnostics.pageDiagnosticsForTesting(0, reopenedContainerId);
            assertEquals("reopened table should retain page-volume capacity",
                    pagesAfterReuse, pages.pageCount());
            assertEquals("reopened table should recover reusable-page tracking from empty durable pages",
                    reusablePagesAfterReuse, pages.reusablePageCount());
            assertEquals("reopened table should use the same reusable-page allocation index",
                    reusablePageIndexFile, diagnostics.reusablePageIndexFileForTesting(0, reopenedContainerId));
            assertTrue("reopened reusable-page allocation index should exist",
                    Files.exists(diagnostics.reusablePageIndexFileForTesting(0, reopenedContainerId)));
            assertTrue("reopened reusable-page allocation index should be repaired after checksum damage",
                    Files.size(diagnostics.reusablePageIndexFileForTesting(0, reopenedContainerId)) > 0L);
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            assertRows(reopened,
                    "select id, length(payload) from mvcc_page_reuse_t order by id",
                    "1|2400",
                    "2|2400",
                    "3|2400");
        }
    }


    private static void corruptLastByte(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0) {
            throw new AssertionError("cannot corrupt empty file: " + path);
        }
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(path, bytes);
    }

    private static void insertPayload(Connection connection, int id, String payload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into mvcc_page_reuse_t values (?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, payload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void updatePayload(Connection connection, int id, String payload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "update mvcc_page_reuse_t set payload = ? where id = ?")) {
            statement.setString(1, payload);
            statement.setInt(2, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String payload(char value, int length) {
        return String.valueOf(value).repeat(length);
    }
}
