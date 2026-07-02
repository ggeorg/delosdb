/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlNoLegacySnapshotFallbackCloseoutTest

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

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL closeout gate proving normal MVCC reads no longer enter the legacy in-memory fallback path. */
public final class MvccSqlNoLegacySnapshotFallbackCloseoutTest extends MvccSqlTestSupport {
    public void testNormalSqlReadsNeverUseLegacySnapshotFallback() throws Exception {
        String databaseName = databaseName("mvcc-no-legacy-snapshot-fallback-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table no_legacy_fallback_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(setup, "insert into no_legacy_fallback_t values (1, 'one', 'payload-1')");
            executeUpdate(setup, "insert into no_legacy_fallback_t values (2, 'two', 'payload-2')");
            executeUpdate(setup, "insert into no_legacy_fallback_t values (5, 'five', 'payload-5')");
            setup.commit();
            containerId = mvccContainerId(setup, "NO_LEGACY_FALLBACK_T");

            assertEquals(0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals(0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            setup.commit();
        }

        try (Connection current = openDatabase(databaseName, false)) {
            current.setAutoCommit(false);
            assertRows(current,
                    "select id, code, payload from no_legacy_fallback_t order by id",
                    "1|one|payload-1",
                    "2|two|payload-2",
                    "5|five|payload-5");
            assertRows(current,
                    "select id, code, payload from no_legacy_fallback_t where id = 1",
                    "1|one|payload-1");
            current.commit();
        }

        try (Connection writer = openDatabase(databaseName, false)) {
            writer.setAutoCommit(false);
            executeUpdate(writer, "update no_legacy_fallback_t set payload = 'payload-2-local' where id = 2");
            executeUpdate(writer, "insert into no_legacy_fallback_t values (4, 'four', 'payload-4-local')");
            executeUpdate(writer, "delete from no_legacy_fallback_t where id = 5");
            assertRows(writer,
                    "select id, code, payload from no_legacy_fallback_t order by id",
                    "1|one|payload-1",
                    "2|two|payload-2-local",
                    "4|four|payload-4-local");
            assertRows(writer,
                    "select id, code, payload from no_legacy_fallback_t where id = 5");
            writer.rollback();
        }

        try (Connection oldSnapshot = openDatabase(databaseName, false);
             Connection newerWriter = openDatabase(databaseName, false)) {
            oldSnapshot.setAutoCommit(false);
            oldSnapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            newerWriter.setAutoCommit(false);

            assertRows(oldSnapshot,
                    "select id, code, payload from no_legacy_fallback_t order by id",
                    "1|one|payload-1",
                    "2|two|payload-2",
                    "5|five|payload-5");

            executeUpdate(newerWriter,
                    "update no_legacy_fallback_t set payload = 'payload-1-newer' where id = 1");
            executeUpdate(newerWriter,
                    "insert into no_legacy_fallback_t values (3, 'three', 'payload-3-newer')");
            newerWriter.commit();

            assertRows(oldSnapshot,
                    "select id, code, payload from no_legacy_fallback_t order by id",
                    "1|one|payload-1",
                    "2|two|payload-2",
                    "5|five|payload-5");
            assertRows(oldSnapshot,
                    "select id, code, payload from no_legacy_fallback_t where id = 1",
                    "1|one|payload-1");

            executeUpdate(oldSnapshot,
                    "update no_legacy_fallback_t set payload = 'payload-2-old-local' where id = 2");
            executeUpdate(oldSnapshot,
                    "insert into no_legacy_fallback_t values (4, 'four', 'payload-4-old-local')");
            executeUpdate(oldSnapshot,
                    "delete from no_legacy_fallback_t where id = 5");

            assertRows(oldSnapshot,
                    "select id, code, payload from no_legacy_fallback_t order by id",
                    "1|one|payload-1",
                    "2|two|payload-2-old-local",
                    "4|four|payload-4-old-local");
            assertRows(oldSnapshot,
                    "select id, code, payload from no_legacy_fallback_t where id = 5");
            oldSnapshot.rollback();
        }

        try (Connection verifier = openDatabase(databaseName, false)) {
            verifier.setAutoCommit(false);
            assertRows(verifier,
                    "select id, code, payload from no_legacy_fallback_t order by id",
                    "1|one|payload-1-newer",
                    "2|two|payload-2",
                    "3|three|payload-3-newer",
                    "5|five|payload-5");
            assertEquals("legacy read fallback must remain removed across current and historical read paths",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy scan fallback must remain removed across current and historical scan paths",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            verifier.commit();
        }
    }
}
