/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccInplaceCompressTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */

package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;

/**
 * SYSCS_UTIL.SYSCS_INPLACE_COMPRESS_TABLE with purge, defragment and truncate
 * works on delos_mvcc tables: defragment is skipped, because an MVCC row keeps
 * its logical row id wherever its versions are stored.
 */
public final class MvccInplaceCompressTest extends MvccSqlTestSupport {

    public void testEveryCompressOptionWorksOnMvccTables() throws Exception {
        String database = databaseName("mvcc-inplace-compress");
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            for (String provider : new String[] {"", " using delos_mvcc"}) {
                String table = provider.isEmpty() ? "COMPRESS_HEAP" : "COMPRESS_MVCC";
                executeUpdate(connection, "create table " + table + " (id int not null,"
                        + " code int not null, label varchar(20),"
                        + " constraint " + table + "_PK primary key (id),"
                        + " constraint " + table + "_CODE unique (code))" + provider);
                executeUpdate(connection, "create index " + table + "_LABEL on " + table + "(label)");
                for (int id = 1; id <= 100; id++) {
                    executeUpdate(connection, "insert into " + table + " values (" + id + ", "
                            + (id * 10) + ", 'label-" + (id % 5) + "')");
                }
                executeUpdate(connection, "delete from " + table + " where mod(id, 2) = 0");
                connection.commit();

                // The standard call, then each option alone, in one session.
                compress(connection, table, "1, 1, 1");
                compress(connection, table, "0, 1, 0");
                compress(connection, table, "1, 0, 0");
                compress(connection, table, "0, 0, 1");

                assertRows(connection, "select count(*), min(id), max(id) from " + table,
                        "50|1|99");
                assertRows(connection, "select count(*) from " + table
                        + " --DERBY-PROPERTIES index=" + table + "_LABEL\n where label > ''", "50");
                assertRows(connection,
                        "values syscs_util.syscs_check_table('APP', '" + table + "')", "1");
                connection.commit();
            }
            assertEquals(0, MvccRawStoreMetadataInspection.deletedIndexEntryCount(
                    connection, "COMPRESS_MVCC_LABEL"));
            connection.commit();
        }
        shutdownDatabase(database);
    }

    private static void compress(Connection connection, String table, String options)
            throws Exception {
        executeStatement(connection, "call syscs_util.syscs_inplace_compress_table('APP', '"
                + table + "', " + options + ")");
        connection.commit();
    }
}
