/*
 * Derby - Class org.apache.derbyTesting.functionTests.tests.store.VersionCleanupArchitectureProofTest
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.derbyTesting.functionTests.tests.store;

import java.sql.SQLException;
import java.sql.Statement;

import junit.framework.Test;

import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.JDBC;
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * SQL-visible proof for the future MVCC/version-cleanup pillar.
 *
 * <p>Current DelosDB inherits Derby's lock-based row store, not MVCC. There are
 * no old row versions to vacuum yet. These tests pin down the current contract
 * that a future MVCC storage model must preserve through its visibility and
 * cleanup rules: rolled-back updates and deletes must not leave searchable
 * index state behind, committed updates must move indexed keys, and committed
 * deletes must remove visible rows from both heap and index access paths.</p>
 *
 * <p>The test deliberately uses forced index lookups where possible because
 * MVCC cleanup is not only a heap problem. Once versioned rows exist, indexes
 * may temporarily point at dead versions. DelosDB must eventually make that
 * explicit and crash-safe; this test only protects today's SQL-visible
 * behavior.</p>
 */
public final class VersionCleanupArchitectureProofTest
        extends BaseJDBCTestCase {

    public VersionCleanupArchitectureProofTest(String name) {
        super(name);
    }

    public static Test suite() {
        Test test = TestConfiguration.embeddedSuite(
                VersionCleanupArchitectureProofTest.class);
        return new CleanDatabaseTestSetup(test) {
            protected void decorateSQL(Statement s) throws SQLException {
                s.execute("create table version_cleanup_proof "
                        + "(id int primary key, key_value int not null, "
                        + "payload int)");
                s.execute("create index version_cleanup_key_idx "
                        + "on version_cleanup_proof(key_value)");
            }
        };
    }

    protected void setUp() throws Exception {
        super.setUp();
        Statement s = createStatement();
        s.executeUpdate("delete from version_cleanup_proof");
        commit();
    }

    /**
     * A rolled-back indexed-key update must leave the original key visible and
     * must not leave the new key searchable through the index.
     */
    public void testRolledBackUpdateLeavesOnlyOriginalIndexKey()
            throws Exception {
        Statement s = createStatement();
        s.executeUpdate("insert into version_cleanup_proof values (1, 10, 100)");
        commit();

        getConnection().setAutoCommit(false);
        s.executeUpdate("update version_cleanup_proof "
                + "set key_value = 20, payload = 200 where id = 1");
        rollback();

        JDBC.assertFullResultSet(
                s.executeQuery(indexLookupSql(10)),
                new String[][] { { "1", "10", "100" } });
        JDBC.assertEmpty(s.executeQuery(indexLookupSql(20)));
    }

    /**
     * A committed indexed-key update must make the old key disappear and the
     * new key visible through an index-forced lookup.
     */
    public void testCommittedUpdateMovesVisibleIndexKey() throws Exception {
        Statement s = createStatement();
        s.executeUpdate("insert into version_cleanup_proof values (2, 30, 300)");
        commit();

        s.executeUpdate("update version_cleanup_proof "
                + "set key_value = 40, payload = 400 where id = 2");
        commit();

        JDBC.assertEmpty(s.executeQuery(indexLookupSql(30)));
        JDBC.assertFullResultSet(
                s.executeQuery(indexLookupSql(40)),
                new String[][] { { "2", "40", "400" } });
    }

    /**
     * A rolled-back delete must preserve the row and its searchable index key.
     */
    public void testRolledBackDeletePreservesVisibleIndexKey()
            throws Exception {
        Statement s = createStatement();
        s.executeUpdate("insert into version_cleanup_proof values (3, 50, 500)");
        commit();

        getConnection().setAutoCommit(false);
        s.executeUpdate("delete from version_cleanup_proof where id = 3");
        rollback();

        JDBC.assertFullResultSet(
                s.executeQuery(indexLookupSql(50)),
                new String[][] { { "3", "50", "500" } });
    }

    /**
     * A committed delete must remove the row from both heap-visible and
     * index-visible access paths.
     */
    public void testCommittedDeleteRemovesVisibleIndexKey() throws Exception {
        Statement s = createStatement();
        s.executeUpdate("insert into version_cleanup_proof values (4, 60, 600)");
        commit();

        s.executeUpdate("delete from version_cleanup_proof where id = 4");
        commit();

        JDBC.assertEmpty(s.executeQuery(
                "select id, key_value, payload from version_cleanup_proof "
                + "where id = 4"));
        JDBC.assertEmpty(s.executeQuery(indexLookupSql(60)));
    }

    private String indexLookupSql(int keyValue) {
        return "select id, key_value, payload from version_cleanup_proof "
                + "--derby-properties index=version_cleanup_key_idx\n"
                + "where key_value = " + keyValue;
    }
}
