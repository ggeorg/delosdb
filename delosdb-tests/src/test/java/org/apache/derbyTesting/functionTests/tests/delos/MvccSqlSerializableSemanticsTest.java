/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlSerializableSemanticsTest

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

/** Truth gate for the current delos_mvcc SERIALIZABLE compatibility mapping. */
public final class MvccSqlSerializableSemanticsTest extends MvccSqlTestSupport {
    public void testSerializableUsesTransactionSnapshotButDoesNotPreventWriteSkew() throws Exception {
        String databaseName = databaseName("mvcc-serializable-semantics-db");

        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table serializable_on_call_t "
                    + "(doctor_id int primary key, on_call int not null) using delos_mvcc");
            executeUpdate(setup, "insert into serializable_on_call_t values (1, 1)");
            executeUpdate(setup, "insert into serializable_on_call_t values (2, 1)");
            setup.commit();
        }

        try (Connection first = openDatabase(databaseName, false);
             Connection second = openDatabase(databaseName, false)) {
            first.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            second.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            first.setAutoCommit(false);
            second.setAutoCommit(false);

            assertRows(first, "select sum(on_call) from serializable_on_call_t", "2");
            assertRows(second, "select sum(on_call) from serializable_on_call_t", "2");

            executeUpdate(first,
                    "update serializable_on_call_t set on_call = 0 where doctor_id = 1");
            executeUpdate(second,
                    "update serializable_on_call_t set on_call = 0 where doctor_id = 2");

            first.commit();

            assertRows(second,
                    "select doctor_id, on_call from serializable_on_call_t order by doctor_id",
                    "1|1",
                    "2|0");
            second.commit();
        }

        try (Connection observer = openDatabase(databaseName, false)) {
            assertRows(observer,
                    "select doctor_id, on_call from serializable_on_call_t order by doctor_id",
                    "1|0",
                    "2|0");
            assertRows(observer, "select sum(on_call) from serializable_on_call_t", "0");
        }

        shutdownDatabase(databaseName);
    }
}
