/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlIndexTest

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

/** SQL integration tests for delos_mvcc index behavior. */
public final class MvccSqlIndexTest extends MvccSqlTestSupport {
    public void testMvccPrimaryKeyRejectsDuplicateCommittedKeyAndSurvivesReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-pk-duplicate-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_pk_duplicate_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_pk_duplicate_t values (1, 'first')");
            connection.commit();

            assertDuplicateKey(() -> executeUpdate(connection,
                    "insert into mvcc_pk_duplicate_t values (1, 'duplicate')"));
            connection.rollback();

            assertRows(connection,
                    "select id, name from mvcc_pk_duplicate_t order by id",
                    "1|first");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_pk_duplicate_t order by id",
                    "1|first");
        }
    }


    public void testMvccPrimaryKeyRollbackAllowsReinsertAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-pk-rollback-reinsert-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_pk_rollback_t (id int primary key, name varchar(32)) using delos_mvcc");
            connection.commit();

            executeUpdate(connection, "insert into mvcc_pk_rollback_t values (1, 'rolled-back')");
            connection.rollback();

            executeUpdate(connection, "insert into mvcc_pk_rollback_t values (1, 'committed')");
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_pk_rollback_t order by id",
                    "1|committed");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_pk_rollback_t order by id",
                    "1|committed");
        }
    }


    public void testMvccPrimaryKeyDeleteAllowsReinsertAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-pk-delete-reinsert-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_pk_delete_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_pk_delete_t values (1, 'old')");
            connection.commit();

            assertEquals(1, executeUpdate(connection, "delete from mvcc_pk_delete_t where id = 1"));
            executeUpdate(connection, "insert into mvcc_pk_delete_t values (1, 'new')");
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_pk_delete_t order by id",
                    "1|new");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_pk_delete_t order by id",
                    "1|new");
        }
    }


    public void testMvccPrimaryKeyUpdateCannotCreateDuplicateKeyAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-pk-update-duplicate-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_pk_update_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_pk_update_t values (1, 'one')");
            executeUpdate(connection, "insert into mvcc_pk_update_t values (2, 'two')");
            connection.commit();

            assertDuplicateKey(() -> executeUpdate(connection,
                    "update mvcc_pk_update_t set id = 1 where id = 2"));
            connection.rollback();

            assertRows(connection,
                    "select id, name from mvcc_pk_update_t order by id",
                    "1|one",
                    "2|two");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_pk_update_t order by id",
                    "1|one",
                    "2|two");
        }
    }


    public void testMvccSecondaryIndexReflectsCommittedInsertUpdateAndDeleteAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-secondary-index-commit-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_si_commit_t (id int, tag varchar(16), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create index mvcc_si_commit_tag_idx on mvcc_si_commit_t(tag)");
            executeUpdate(connection, "insert into mvcc_si_commit_t values (1, 'blue', 'one')");
            executeUpdate(connection, "insert into mvcc_si_commit_t values (2, 'red', 'two')");
            executeUpdate(connection, "insert into mvcc_si_commit_t values (3, 'blue', 'three')");
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_si_commit_t --DERBY-PROPERTIES index=mvcc_si_commit_tag_idx\n where tag = 'blue' order by id",
                    "1|one",
                    "3|three");

            assertEquals(1, executeUpdate(connection,
                    "update mvcc_si_commit_t set tag = 'red' where id = 3"));
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_si_commit_t --DERBY-PROPERTIES index=mvcc_si_commit_tag_idx\n where tag = 'blue' order by id",
                    "1|one");
            assertRows(connection,
                    "select id, name from mvcc_si_commit_t --DERBY-PROPERTIES index=mvcc_si_commit_tag_idx\n where tag = 'red' order by id",
                    "2|two",
                    "3|three");

            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_si_commit_t where id = 1"));
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_si_commit_t --DERBY-PROPERTIES index=mvcc_si_commit_tag_idx\n where tag = 'blue' order by id");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_si_commit_t --DERBY-PROPERTIES index=mvcc_si_commit_tag_idx\n where tag = 'blue' order by id");
            assertRows(reopened,
                    "select id, name from mvcc_si_commit_t --DERBY-PROPERTIES index=mvcc_si_commit_tag_idx\n where tag = 'red' order by id",
                    "2|two",
                    "3|three");
        }
    }


    public void testMvccIndexedColumnUpdateWithPrimaryAndUniqueIndexes() throws Exception {
        String databaseName = databaseName("mvcc-sql-indexed-column-update-with-constraints-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_index_projection_t "
                    + "(id int primary key, email varchar(64) unique, tag varchar(16), name varchar(32)) "
                    + "using delos_mvcc");
            executeUpdate(connection, "create index mvcc_index_projection_tag_idx on mvcc_index_projection_t(tag)");
            executeUpdate(connection, "insert into mvcc_index_projection_t values "
                    + "(1, 'a@example.com', 'blue', 'alpha')");
            executeUpdate(connection, "insert into mvcc_index_projection_t values "
                    + "(2, 'b@example.com', 'red', 'beta')");
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "update mvcc_index_projection_t set tag = 'green', name = 'alpha-v2' where id = 1"));
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_index_projection_t --DERBY-PROPERTIES index=mvcc_index_projection_tag_idx\n "
                            + "where tag = 'blue' order by id");
            assertRows(connection,
                    "select id, name from mvcc_index_projection_t --DERBY-PROPERTIES index=mvcc_index_projection_tag_idx\n "
                            + "where tag = 'green' order by id",
                    "1|alpha-v2");
            assertRows(connection,
                    "select id, email, tag, name from mvcc_index_projection_t order by id",
                    "1|a@example.com|green|alpha-v2",
                    "2|b@example.com|red|beta");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_index_projection_t --DERBY-PROPERTIES index=mvcc_index_projection_tag_idx\n "
                            + "where tag = 'green' order by id",
                    "1|alpha-v2");
            assertRows(reopened,
                    "select id, email, tag, name from mvcc_index_projection_t order by id",
                    "1|a@example.com|green|alpha-v2",
                    "2|b@example.com|red|beta");
        }
    }


    public void testMvccSecondaryIndexRollbackRestoresIndexedVisibilityAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-secondary-index-rollback-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_si_rollback_t (id int, tag varchar(16), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create index mvcc_si_rollback_tag_idx on mvcc_si_rollback_t(tag)");
            executeUpdate(connection, "insert into mvcc_si_rollback_t values (1, 'blue', 'one')");
            executeUpdate(connection, "insert into mvcc_si_rollback_t values (2, 'red', 'two')");
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "update mvcc_si_rollback_t set tag = 'blue' where id = 2"));
            connection.rollback();

            assertRows(connection,
                    "select id, name from mvcc_si_rollback_t --DERBY-PROPERTIES index=mvcc_si_rollback_tag_idx\n where tag = 'blue' order by id",
                    "1|one");
            assertRows(connection,
                    "select id, name from mvcc_si_rollback_t --DERBY-PROPERTIES index=mvcc_si_rollback_tag_idx\n where tag = 'red' order by id",
                    "2|two");

            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_si_rollback_t where id = 1"));
            connection.rollback();

            executeUpdate(connection, "insert into mvcc_si_rollback_t values (3, 'blue', 'three')");
            connection.rollback();

            assertRows(connection,
                    "select id, name from mvcc_si_rollback_t --DERBY-PROPERTIES index=mvcc_si_rollback_tag_idx\n where tag = 'blue' order by id",
                    "1|one");
            assertRows(connection,
                    "select id, name from mvcc_si_rollback_t --DERBY-PROPERTIES index=mvcc_si_rollback_tag_idx\n where tag = 'red' order by id",
                    "2|two");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_si_rollback_t --DERBY-PROPERTIES index=mvcc_si_rollback_tag_idx\n where tag = 'blue' order by id",
                    "1|one");
            assertRows(reopened,
                    "select id, name from mvcc_si_rollback_t --DERBY-PROPERTIES index=mvcc_si_rollback_tag_idx\n where tag = 'red' order by id",
                    "2|two");
        }
    }


    public void testMvccSecondaryIndexCanDriveDeleteAndUpdatePredicatesAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-secondary-index-write-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_si_write_t (id int, tag varchar(16), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create index mvcc_si_write_tag_idx on mvcc_si_write_t(tag)");
            executeUpdate(connection, "insert into mvcc_si_write_t values (1, 'blue', 'one')");
            executeUpdate(connection, "insert into mvcc_si_write_t values (2, 'blue', 'two')");
            executeUpdate(connection, "insert into mvcc_si_write_t values (3, 'red', 'three')");
            connection.commit();

            assertEquals(2, executeUpdate(connection,
                    "update mvcc_si_write_t set name = 'seen' where tag = 'blue'"));
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_si_write_t --DERBY-PROPERTIES index=mvcc_si_write_tag_idx\n where tag = 'blue' order by id",
                    "1|seen",
                    "2|seen");

            assertEquals(2, executeUpdate(connection,
                    "delete from mvcc_si_write_t where tag = 'blue'"));
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_si_write_t --DERBY-PROPERTIES index=mvcc_si_write_tag_idx\n where tag = 'blue' order by id");
            assertRows(connection,
                    "select id, name from mvcc_si_write_t --DERBY-PROPERTIES index=mvcc_si_write_tag_idx\n where tag = 'red' order by id",
                    "3|three");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_si_write_t --DERBY-PROPERTIES index=mvcc_si_write_tag_idx\n where tag = 'blue' order by id");
            assertRows(reopened,
                    "select id, name from mvcc_si_write_t --DERBY-PROPERTIES index=mvcc_si_write_tag_idx\n where tag = 'red' order by id",
                    "3|three");
        }
    }


    public void testMvccUniqueIndexRejectsDuplicateCommittedValueAndSurvivesReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-unique-duplicate-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_unique_duplicate_t (id int, email varchar(64), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create unique index mvcc_unique_duplicate_email_idx on mvcc_unique_duplicate_t(email)");
            executeUpdate(connection, "insert into mvcc_unique_duplicate_t values (1, 'a@example.com', 'alpha')");
            connection.commit();

            assertDuplicateKey(() -> executeUpdate(connection,
                    "insert into mvcc_unique_duplicate_t values (2, 'a@example.com', 'duplicate')"));
            connection.rollback();

            assertRows(connection,
                    "select id, email from mvcc_unique_duplicate_t --DERBY-PROPERTIES index=mvcc_unique_duplicate_email_idx\n where email = 'a@example.com' order by id",
                    "1|a@example.com");
            assertRows(connection,
                    "select id, name from mvcc_unique_duplicate_t order by id",
                    "1|alpha");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, email from mvcc_unique_duplicate_t --DERBY-PROPERTIES index=mvcc_unique_duplicate_email_idx\n where email = 'a@example.com' order by id",
                    "1|a@example.com");
            assertRows(reopened,
                    "select id, name from mvcc_unique_duplicate_t order by id",
                    "1|alpha");
        }
    }


    public void testMvccUniqueIndexRollbackAllowsReinsertAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-unique-rollback-reinsert-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_unique_rollback_t (id int, email varchar(64), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create unique index mvcc_unique_rollback_email_idx on mvcc_unique_rollback_t(email)");
            connection.commit();

            executeUpdate(connection, "insert into mvcc_unique_rollback_t values (1, 'a@example.com', 'ghost')");
            connection.rollback();

            executeUpdate(connection, "insert into mvcc_unique_rollback_t values (2, 'a@example.com', 'alpha')");
            connection.commit();

            assertRows(connection,
                    "select id, email from mvcc_unique_rollback_t --DERBY-PROPERTIES index=mvcc_unique_rollback_email_idx\n where email = 'a@example.com' order by id",
                    "2|a@example.com");
            assertRows(connection,
                    "select id, name from mvcc_unique_rollback_t order by id",
                    "2|alpha");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, email from mvcc_unique_rollback_t --DERBY-PROPERTIES index=mvcc_unique_rollback_email_idx\n where email = 'a@example.com' order by id",
                    "2|a@example.com");
            assertRows(reopened,
                    "select id, name from mvcc_unique_rollback_t order by id",
                    "2|alpha");
        }
    }


    public void testMvccUniqueIndexDeleteAllowsReinsertAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-unique-delete-reinsert-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_unique_delete_t (id int, email varchar(64), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create unique index mvcc_unique_delete_email_idx on mvcc_unique_delete_t(email)");
            executeUpdate(connection, "insert into mvcc_unique_delete_t values (1, 'a@example.com', 'alpha')");
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_unique_delete_t where email = 'a@example.com'"));
            connection.commit();

            executeUpdate(connection, "insert into mvcc_unique_delete_t values (2, 'a@example.com', 'beta')");
            connection.commit();

            assertRows(connection,
                    "select id, email from mvcc_unique_delete_t --DERBY-PROPERTIES index=mvcc_unique_delete_email_idx\n where email = 'a@example.com' order by id",
                    "2|a@example.com");
            assertRows(connection,
                    "select id, name from mvcc_unique_delete_t order by id",
                    "2|beta");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, email from mvcc_unique_delete_t --DERBY-PROPERTIES index=mvcc_unique_delete_email_idx\n where email = 'a@example.com' order by id",
                    "2|a@example.com");
            assertRows(reopened,
                    "select id, name from mvcc_unique_delete_t order by id",
                    "2|beta");
        }
    }


    public void testMvccUniqueIndexUpdateCannotCreateDuplicateValueAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-unique-update-duplicate-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_unique_update_t (id int, email varchar(64), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create unique index mvcc_unique_update_email_idx on mvcc_unique_update_t(email)");
            executeUpdate(connection, "insert into mvcc_unique_update_t values (1, 'a@example.com', 'alpha')");
            executeUpdate(connection, "insert into mvcc_unique_update_t values (2, 'b@example.com', 'beta')");
            connection.commit();

            assertDuplicateKey(() -> executeUpdate(connection,
                    "update mvcc_unique_update_t set email = 'a@example.com' where id = 2"));
            connection.rollback();

            assertRows(connection,
                    "select id, email from mvcc_unique_update_t --DERBY-PROPERTIES index=mvcc_unique_update_email_idx\n where email = 'a@example.com' order by id",
                    "1|a@example.com");
            assertRows(connection,
                    "select id, email from mvcc_unique_update_t --DERBY-PROPERTIES index=mvcc_unique_update_email_idx\n where email = 'b@example.com' order by id",
                    "2|b@example.com");
            assertRows(connection,
                    "select id, name from mvcc_unique_update_t order by id",
                    "1|alpha",
                    "2|beta");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, email from mvcc_unique_update_t --DERBY-PROPERTIES index=mvcc_unique_update_email_idx\n where email = 'a@example.com' order by id",
                    "1|a@example.com");
            assertRows(reopened,
                    "select id, email from mvcc_unique_update_t --DERBY-PROPERTIES index=mvcc_unique_update_email_idx\n where email = 'b@example.com' order by id",
                    "2|b@example.com");
            assertRows(reopened,
                    "select id, name from mvcc_unique_update_t order by id",
                    "1|alpha",
                    "2|beta");
        }
    }


    public void testConcurrentMvccPrimaryKeyInsertRejectsSecondWriterAndPreservesWinnerAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-concurrent-pk-insert-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_concurrent_pk_t (id int primary key, name varchar(32)) using delos_mvcc");
            connection.commit();
        }

        try (Connection writerA = openDatabase(databaseName, false);
             Connection writerB = openDatabase(databaseName, false);
             Connection reader = openDatabase(databaseName, false)) {
            writerA.setAutoCommit(false);
            writerB.setAutoCommit(false);

            assertEquals(1, executeUpdate(writerA,
                    "insert into mvcc_concurrent_pk_t values (1, 'from_a')"));

            assertRows(reader,
                    "select id, name from mvcc_concurrent_pk_t where id = 1");

            assertDuplicateKeyOrWriteConflict(() -> executeUpdate(writerB,
                    "insert into mvcc_concurrent_pk_t values (1, 'from_b')"));
            rollbackAfterExpectedConflict(writerB);

            writerA.commit();

            assertRows(reader,
                    "select id, name from mvcc_concurrent_pk_t where id = 1",
                    "1|from_a");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_concurrent_pk_t where id = 1",
                    "1|from_a");
        }
    }


    public void testConcurrentMvccUniqueIndexInsertRejectsSecondWriterAndPreservesWinnerAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-concurrent-unique-insert-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_concurrent_unique_t (id int, email varchar(64), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create unique index mvcc_concurrent_unique_email_idx on mvcc_concurrent_unique_t(email)");
            connection.commit();
        }

        try (Connection writerA = openDatabase(databaseName, false);
             Connection writerB = openDatabase(databaseName, false);
             Connection reader = openDatabase(databaseName, false)) {
            writerA.setAutoCommit(false);
            writerB.setAutoCommit(false);

            assertEquals(1, executeUpdate(writerA,
                    "insert into mvcc_concurrent_unique_t values (1, 'a@example.com', 'from_a')"));

            assertRows(reader,
                    "select id, email from mvcc_concurrent_unique_t --DERBY-PROPERTIES index=mvcc_concurrent_unique_email_idx\n where email = 'a@example.com' order by id");

            assertDuplicateKeyOrWriteConflict(() -> executeUpdate(writerB,
                    "insert into mvcc_concurrent_unique_t values (2, 'a@example.com', 'from_b')"));
            rollbackAfterExpectedConflict(writerB);

            writerA.commit();

            assertRows(reader,
                    "select id, email from mvcc_concurrent_unique_t --DERBY-PROPERTIES index=mvcc_concurrent_unique_email_idx\n where email = 'a@example.com' order by id",
                    "1|a@example.com");
            assertRows(reader,
                    "select id, name from mvcc_concurrent_unique_t order by id",
                    "1|from_a");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, email from mvcc_concurrent_unique_t --DERBY-PROPERTIES index=mvcc_concurrent_unique_email_idx\n where email = 'a@example.com' order by id",
                    "1|a@example.com");
            assertRows(reopened,
                    "select id, name from mvcc_concurrent_unique_t order by id",
                    "1|from_a");
        }
    }


}
