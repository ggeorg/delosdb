/*
 * Derby - Class org.apache.derbyTesting.functionTests.tests.store.WalRecoveryArchitectureProofTest
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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import junit.framework.Test;

import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.BaseTestSuite;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.JDBC;
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * Source-level proof for DelosDB's next storage/concurrency campaign.
 *
 * <p>MVCC cannot be designed safely until the inherited Derby WAL and
 * restart-recovery boundaries are pinned down. This test deliberately avoids
 * changing recovery behavior. It proves the current contract that future MVCC
 * work must preserve: committed log records survive a dirty restart, while the
 * loser transaction that is active at process exit is undone during restart
 * recovery.</p>
 */
public final class WalRecoveryArchitectureProofTest extends BaseJDBCTestCase {

    public WalRecoveryArchitectureProofTest(String name) {
        super(name);
    }

    public static Test suite() {
        BaseTestSuite suite = new BaseTestSuite(
                "WalRecoveryArchitectureProofTest");
        suite.addTest(new CleanDatabaseTestSetup(
                TestConfiguration.embeddedSuite(
                        WalRecoveryArchitectureProofTest.class)));
        return suite;
    }

    /**
     * Dirty restart must replay committed work and roll back the in-flight
     * transaction left behind by the crashed process.
     */
    public void testDirtyRestartReplaysCommittedAndRollsBackLoser()
            throws Exception {
        Connection c = getConnection();
        c.setAutoCommit(false);
        Statement s = createStatement();
        s.executeUpdate("create table wal_arch_proof "
                + "(id int primary key, label varchar(40))");
        c.commit();

        TestConfiguration.getCurrent().shutdownDatabase();
        s.close();
        c.close();

        assertLaunchedJUnitTestMethod(
                "org.apache.derbyTesting.functionTests.tests.store."
                + "WalRecoveryArchitectureProofTest.launchDirtyRestartWorkload");

        s = createStatement();
        ResultSet rs = s.executeQuery(
                "select id, label from wal_arch_proof order by id");
        JDBC.assertFullResultSet(rs, new String[][] {
            { "1", "committed-before-crash" },
            { "3", "committed-before-crash-2" }
        });
    }

    /**
     * Launched in a separate JVM by {@link #testDirtyRestartReplaysCommittedAndRollsBackLoser()}.
     *
     * <p>The method intentionally does not close the connection or shutdown the
     * database. The process exits with one committed transaction and one active
     * transaction. Reconnecting in the parent JVM forces Derby restart recovery
     * to redo committed log records and undo the active transaction.</p>
     */
    public void launchDirtyRestartWorkload() throws Exception {
        Connection c = getConnection();
        c.setAutoCommit(false);
        Statement s = createStatement();

        s.executeUpdate("insert into wal_arch_proof values "
                + "(1, 'committed-before-crash')");
        c.commit();

        s.executeUpdate("insert into wal_arch_proof values "
                + "(3, 'committed-before-crash-2')");
        c.commit();

        s.executeUpdate("insert into wal_arch_proof values "
                + "(2, 'loser-transaction-at-crash')");
    }
}
