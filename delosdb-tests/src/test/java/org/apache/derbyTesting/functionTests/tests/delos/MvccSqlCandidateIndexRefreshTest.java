/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlCandidateIndexRefreshTest

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

/** SQL gate for rebuilding the conservative MVCC candidate index from visible rows. */
public final class MvccSqlCandidateIndexRefreshTest extends MvccSqlTestSupport {
    public void testCandidateIndexRefreshDropsStaleCommittedKeys() throws Exception {
        String databaseName = databaseName("mvcc-candidate-index-refresh-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table candidate_refresh_t "
                    + "(id int primary key, code varchar(32), payload varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into candidate_refresh_t values (1, 'old-code', 'payload-1')");
            executeUpdate(connection, "insert into candidate_refresh_t values (2, 'delete-code', 'payload-2')");
            connection.commit();

            assertRows(connection,
                    "select id, code from candidate_refresh_t where code = 'old-code'",
                    "1|old-code");
            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(connection,
                    "select id from candidate_refresh_t where code = 'old-code'",
                    "1");
            assertEquals("fresh visible key should narrow to one row id",
                    1, diagnostics.candidateIndexRowIdCountForTesting());

            executeUpdate(connection, "update candidate_refresh_t set code = 'new-code' where id = 1");
            executeUpdate(connection, "delete from candidate_refresh_t where id = 2");
            connection.commit();

            assertRows(connection,
                    "select id, code from candidate_refresh_t where code = 'new-code'",
                    "1|new-code");

            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(connection,
                    "select id from candidate_refresh_t where code = 'old-code'");
            assertEquals("stale updated key should remain indexed as a known empty candidate set",
                    1, diagnostics.candidateIndexLookupCountForTesting());
            assertEquals("stale updated key must not retain the row id after refresh",
                    0, diagnostics.candidateIndexRowIdCountForTesting());
            assertEquals("stale updated key should not require qualifier rejection after refresh",
                    0, diagnostics.candidateIndexQualifierRejectCountForTesting());

            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(connection,
                    "select id from candidate_refresh_t where code = 'delete-code'");
            assertEquals("stale deleted key should remain indexed as a known empty candidate set",
                    1, diagnostics.candidateIndexLookupCountForTesting());
            assertEquals("stale deleted key must not retain the deleted row id after refresh",
                    0, diagnostics.candidateIndexRowIdCountForTesting());
            assertEquals("stale deleted key should not require visibility rejection after refresh",
                    0, diagnostics.candidateIndexVisibilityRejectCountForTesting());

            connection.commit();
        }
    }
    public void testRepeatableReadSnapshotFallsBackWhenCandidateIndexWasRefreshed() throws Exception {
        String databaseName = databaseName("mvcc-candidate-index-refresh-repeatable-read-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();

        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table candidate_refresh_rr_t "
                    + "(id int primary key, code varchar(32), payload varchar(32)) using delos_mvcc");
            executeUpdate(setup, "insert into candidate_refresh_rr_t values (1, 'survivor', 'payload')");
            setup.commit();
        }

        try (Connection reader = openDatabase(databaseName, false);
             Connection writer = openDatabase(databaseName, false)) {
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            assertRows(reader,
                    "select id, code from candidate_refresh_rr_t where code = 'survivor'",
                    "1|survivor");

            executeUpdate(writer, "delete from candidate_refresh_rr_t where id = 1");
            writer.commit();

            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(reader,
                    "select id, code from candidate_refresh_rr_t where code = 'survivor'",
                    "1|survivor");
            assertEquals("repeatable-read scans must not narrow through the current committed candidate index",
                    0, diagnostics.candidateIndexLookupCountForTesting());

            reader.commit();
            writer.commit();
        }
    }

}
