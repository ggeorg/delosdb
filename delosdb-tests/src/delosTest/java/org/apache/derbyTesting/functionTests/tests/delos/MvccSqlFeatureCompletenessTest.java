/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlFeatureCompletenessTest

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
import java.sql.SQLException;
import java.sql.Savepoint;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** Broad SQL-surface gate for delos_mvcc feature-completeness hardening. */
public final class MvccSqlFeatureCompletenessTest extends MvccSqlTestSupport {
    public void testPreparedInsertSelectDefaultsAggregatesSavepointsVacuumAndReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-feature-completeness-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        long targetContainerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_feature_source_t ("
                    + "id int primary key, "
                    + "category varchar(16) not null, "
                    + "payload varchar(200), "
                    + "amount int default 10, "
                    + "note varchar(32) default 'seed') using delos_mvcc");
            executeUpdate(connection, "create table mvcc_feature_target_t ("
                    + "id int primary key, "
                    + "category varchar(16) not null, "
                    + "payload varchar(240), "
                    + "amount int, "
                    + "note varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create index mvcc_feature_target_cat_idx "
                    + "on mvcc_feature_target_t(category, amount)");

            insertSource(connection, 1, "alpha", "first", 1, "one");
            insertSource(connection, 2, "beta", "second", 2, "two");
            executeUpdate(connection, "insert into mvcc_feature_source_t (id, category, payload) "
                    + "values (3, 'alpha', 'third')");
            connection.commit();

            try (PreparedStatement insertSelect = connection.prepareStatement(
                    "insert into mvcc_feature_target_t (id, category, payload, amount, note) "
                            + "select id + 100, category, payload || '-copied', amount + ?, note "
                            + "from mvcc_feature_source_t where id <= ?")) {
                insertSelect.setInt(1, 5);
                insertSelect.setInt(2, 3);
                assertEquals(3, insertSelect.executeUpdate());
            }
            connection.commit();

            targetContainerId = mvccContainerId(connection, "MVCC_FEATURE_TARGET_T");
            assertMvccConsistent(diagnostics, targetContainerId);
            assertRows(connection,
                    "select id, category, payload, amount, note from mvcc_feature_target_t order by id",
                    "101|alpha|first-copied|6|one",
                    "102|beta|second-copied|7|two",
                    "103|alpha|third-copied|15|seed");

            Savepoint savepoint = connection.setSavepoint("FEATURE_SP");
            executeUpdate(connection, "update mvcc_feature_target_t set amount = 999 where id = 101");
            executeUpdate(connection, "insert into mvcc_feature_target_t values "
                    + "(199, 'rollback', 'rollback-row', 199, 'rollback')");
            executeUpdate(connection, "delete from mvcc_feature_target_t where id = 103");
            connection.rollback(savepoint);

            assertRows(connection,
                    "select id, category, payload, amount, note from mvcc_feature_target_t order by id",
                    "101|alpha|first-copied|6|one",
                    "102|beta|second-copied|7|two",
                    "103|alpha|third-copied|15|seed");

            try (PreparedStatement update = connection.prepareStatement(
                    "update mvcc_feature_target_t set payload = payload || '-u', amount = amount + ? "
                            + "where category = ?")) {
                update.setInt(1, 7);
                update.setString(2, "alpha");
                assertEquals(2, update.executeUpdate());
            }
            assertEquals(1, executeUpdate(connection, "delete from mvcc_feature_target_t where id = 102"));
            connection.commit();

            assertRows(connection,
                    "select id, category, payload, amount from mvcc_feature_target_t "
                            + "--DERBY-PROPERTIES index=mvcc_feature_target_cat_idx\n "
                            + "where category = 'alpha' order by id",
                    "101|alpha|first-copied-u|13",
                    "103|alpha|third-copied-u|22");
            assertRows(connection,
                    "select category, count(*), sum(amount) from mvcc_feature_target_t "
                            + "group by category order by category",
                    "alpha|2|35");
            assertMvccConsistent(diagnostics, targetContainerId);

            inPlaceCompressTable(connection, "MVCC_FEATURE_TARGET_T");
            connection.commit();
            assertFalse("feature-completeness vacuum should not be skipped",
                    diagnostics.lastVacuumSkippedForTesting(0, targetContainerId));
            assertMvccConsistent(diagnostics, targetContainerId);
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_FEATURE_TARGET_T");
            assertMvccConsistent(diagnostics, reopenedContainerId);
            assertRows(reopened,
                    "select id, category, payload, amount from mvcc_feature_target_t order by id",
                    "101|alpha|first-copied-u|13",
                    "103|alpha|third-copied-u|22");
            assertRows(reopened,
                    "select category, count(*), sum(amount) from mvcc_feature_target_t "
                            + "group by category order by category",
                    "alpha|2|35");
        }
    }

    private static void insertSource(
            Connection connection,
            int id,
            String category,
            String payload,
            int amount,
            String note) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into mvcc_feature_source_t values (?, ?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, category);
            statement.setString(3, payload);
            statement.setInt(4, amount);
            statement.setString(5, note);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertMvccConsistent(DelosStorageDiagnostics diagnostics, long containerId) {
        diagnostics.assertConsistentForTesting(0, containerId);
        assertEquals("expected no MVCC consistency errors", 0,
                diagnostics.consistencyDiagnosticsForTesting(0, containerId).errorCount());
    }
}
