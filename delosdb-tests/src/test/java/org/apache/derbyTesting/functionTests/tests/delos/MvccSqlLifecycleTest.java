/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlLifecycleTest

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

/** SQL integration tests for delos_mvcc lifecycle behavior. */
public final class MvccSqlLifecycleTest extends MvccSqlTestSupport {
    public void testDroppedMvccTableRecreateDoesNotSeeDroppedRowsAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-drop-recreate-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_drop_recreate_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_drop_recreate_t values (1, 'dropped')");
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_drop_recreate_t order by id",
                    "1|dropped");

            executeUpdate(connection, "drop table mvcc_drop_recreate_t");
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            executeUpdate(reopened, "create table mvcc_drop_recreate_t (id int primary key, name varchar(32)) using delos_mvcc");
            reopened.commit();

            assertRows(reopened,
                    "select id, name from mvcc_drop_recreate_t order by id");

            executeUpdate(reopened, "insert into mvcc_drop_recreate_t values (2, 'fresh')");
            reopened.commit();
            reopened.setAutoCommit(true);

            assertRows(reopened,
                    "select id, name from mvcc_drop_recreate_t order by id",
                    "2|fresh");
        }

        shutdownDatabase(databaseName);

        try (Connection reopenedAgain = openDatabase(databaseName, false)) {
            assertRows(reopenedAgain,
                    "select id, name from mvcc_drop_recreate_t order by id",
                    "2|fresh");
        }
    }


    public void testDroppedMvccTableRemovesRawStoreConglomerateAcrossReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-drop-cleanup-db");
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table mvcc_drop_cleanup_t "
                            + "(id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_drop_cleanup_t values (1, 'alpha')");
            executeUpdate(connection, "insert into mvcc_drop_cleanup_t values (2, 'beta')");
            connection.commit();

            containerId = mvccContainerId(connection, "MVCC_DROP_CLEANUP_T");
            assertConglomeratePresent(connection, containerId);

            executeUpdate(connection, "drop table mvcc_drop_cleanup_t");
            connection.commit();
            connection.setAutoCommit(true);
            assertConglomerateMissing(connection, containerId);
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertConglomerateMissing(reopened, containerId);
        }
    }


}
