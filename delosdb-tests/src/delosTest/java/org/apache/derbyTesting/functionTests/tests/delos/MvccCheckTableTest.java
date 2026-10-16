/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccCheckTableTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */

package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;

/**
 * SYSCS_UTIL.SYSCS_CHECK_TABLE verifies the indexes of delos_mvcc tables as it
 * verifies heap tables. The checker fetches base rows with a column list that
 * covers only the indexed columns, which can be shorter than the row.
 */
public final class MvccCheckTableTest extends MvccSqlTestSupport {

    public void testCheckTableVerifiesEveryIndexKind() throws Exception {
        String database = databaseName("mvcc-check-table");
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            for (String provider : new String[] {"", " using delos_mvcc"}) {
                String table = provider.isEmpty() ? "CHECK_HEAP" : "CHECK_MVCC";
                executeUpdate(connection, "create table " + table + " (id int not null,"
                        + " code int not null, label varchar(20), amount int,"
                        + " constraint " + table + "_PK primary key (id),"
                        + " constraint " + table + "_CODE unique (code))" + provider);
                executeUpdate(connection,
                        "create index " + table + "_LABEL on " + table + "(label, amount)");
                for (int id = 1; id <= 50; id++) {
                    executeUpdate(connection, "insert into " + table + " values (" + id + ", "
                            + (id * 10) + ", 'label-" + (id % 7) + "', " + id + ")");
                }
                executeUpdate(connection,
                        "update " + table + " set label = 'moved', code = code + 1 where id <= 10");
                executeUpdate(connection, "delete from " + table + " where mod(id, 5) = 0");
                connection.commit();
                assertRows(connection,
                        "values syscs_util.syscs_check_table('APP', '" + table + "')", "1");
            }
            connection.commit();
        }
        shutdownDatabase(database);
    }
}
