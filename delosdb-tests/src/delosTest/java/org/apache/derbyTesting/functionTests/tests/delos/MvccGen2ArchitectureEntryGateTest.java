/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccGen2ArchitectureEntryGateTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;

/**
 * Entry gate for MVCC Gen2 physical design.
 *
 * <p>This test intentionally proves two Gen1 facts that Gen2 must not confuse:
 * commit publication recovery is block-reserved rather than durably advanced on
 * every default-path commit, while transaction identity allocation still performs
 * one database-wide durable reservation for each writing transaction.</p>
 */
public final class MvccGen2ArchitectureEntryGateTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";

    public void testRecoveryPublicationCeilingIsNotPersistedPerDefaultPathCommit()
            throws Exception {
        String database = databaseName("mvcc-gen2-entry-publication");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table gen2_entry_t (id int) using delos_mvcc");
                connection.commit();

                MvccRawStoreMetadataInspection.Counters initial =
                        MvccRawStoreMetadataInspection.counters(connection);
                assertEquals("initial transaction id", 1L, initial.nextTransactionId());
                assertEquals("initial commit sequence", 1L, initial.nextCommitSequence());
                assertEquals("initial recovery ceiling", 0L, initial.recoveryPublicationCeiling());

                executeUpdate(connection, "insert into gen2_entry_t values 1");
                connection.commit();
                MvccRawStoreMetadataInspection.Counters first =
                        MvccRawStoreMetadataInspection.counters(connection);

                assertEquals("first write reserves one transaction id", 2L, first.nextTransactionId());
                assertEquals("first commit reserves one 64-sequence block", 65L, first.nextCommitSequence());
                assertEquals("first commit advances recovery ceiling to block end", 64L,
                        first.recoveryPublicationCeiling());

                executeUpdate(connection, "insert into gen2_entry_t values 2");
                connection.commit();
                MvccRawStoreMetadataInspection.Counters second =
                        MvccRawStoreMetadataInspection.counters(connection);

                assertEquals("second write reserves another transaction id", 3L,
                        second.nextTransactionId());
                assertEquals("second commit reuses reserved commit block", first.nextCommitSequence(),
                        second.nextCommitSequence());
                assertEquals("second commit does not rewrite recovery ceiling", first.recoveryPublicationCeiling(),
                        second.recoveryPublicationCeiling());

                // The final metadata inspection starts a read transaction while auto-commit is disabled.
                // End it explicitly so connection close does not fail with SQLState 25001.
                connection.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testGen1RemainsRunnableSemanticReference() throws Exception {
        String database = databaseName("mvcc-gen2-entry-semantic-reference");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table gen1_reference_t (id int primary key, value int) using delos_mvcc");
                connection.commit();

                executeUpdate(connection, "insert into gen1_reference_t values (1, 10)");
                connection.commit();
                assertRows(connection, "select id, value from gen1_reference_t order by id", "1|10");

                executeUpdate(connection, "update gen1_reference_t set value = 20 where id = 1");
                connection.rollback();
                assertRows(connection, "select id, value from gen1_reference_t order by id", "1|10");
                connection.commit();
            }
            shutdownDatabase(database);
        }
    }
}
