/*
 * Derby - Class org.apache.derbyTesting.functionTests.tests.delosdb.ModernRdbmsTransactionTraceTest
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

package org.apache.derbyTesting.functionTests.tests.delosdb;

import io.github.ggeorg.delosdb.engine.trace.RdbmsLifecycleStage;
import io.github.ggeorg.delosdb.engine.trace.RdbmsTraceEvent;
import io.github.ggeorg.delosdb.engine.trace.RdbmsTraceRegistry;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import junit.framework.Test;

import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.JDBC;
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * Focused proof that the modern RDBMS model observes transaction boundaries.
 *
 * <p>The test watches inherited Derby transaction behavior. It does not change commit, rollback,
 * locking, logging, isolation, or MVCC semantics.</p>
 */
public final class ModernRdbmsTransactionTraceTest extends BaseJDBCTestCase {
    private static final String TEST_TABLE = "MODERN_TRANSACTION_TRACE_TEST";

    public ModernRdbmsTransactionTraceTest(String name) {
        super(name);
    }

    public static Test suite() {
        Test test = TestConfiguration.embeddedSuite(
                ModernRdbmsTransactionTraceTest.class);
        return new CleanDatabaseTestSetup(test) {
            protected void decorateSQL(Statement s) throws SQLException {
                s.execute("create table " + TEST_TABLE
                        + " (id int primary key, name varchar(16))");
            }
        };
    }

    public void testCommitReportsTransactionBoundary() throws Exception {
        Connection conn = getConnection();
        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);

        List<RdbmsTraceEvent> events = new ArrayList<>();
        RdbmsTraceRegistry.setSink(events::add);
        try {
            Statement s = conn.createStatement();
            s.executeUpdate("insert into " + TEST_TABLE
                    + " values (1, 'commit')");
            s.close();
            conn.commit();
        } finally {
            RdbmsTraceRegistry.reset();
            conn.setAutoCommit(originalAutoCommit);
        }

        assertTransactionBoundary(
                events,
                RdbmsLifecycleStage.TRANSACTION_COMMITTED,
                "COMMIT");
    }

    public void testRollbackReportsTransactionBoundary() throws Exception {
        Connection conn = getConnection();
        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);

        List<RdbmsTraceEvent> events = new ArrayList<>();
        RdbmsTraceRegistry.setSink(events::add);
        try {
            Statement s = conn.createStatement();
            s.executeUpdate("insert into " + TEST_TABLE
                    + " values (2, 'rollback')");
            s.close();
            conn.rollback();
        } finally {
            RdbmsTraceRegistry.reset();
            conn.setAutoCommit(originalAutoCommit);
        }

        assertTransactionBoundary(
                events,
                RdbmsLifecycleStage.TRANSACTION_ROLLED_BACK,
                "ROLLBACK");

        Statement verify = createStatement();
        ResultSet rs = verify.executeQuery(
                "select count(*) from " + TEST_TABLE + " where id = 2");
        JDBC.assertFullResultSet(rs, new String[][] {{ "0" }});
        rs.close();
        verify.close();
    }

    private static void assertTransactionBoundary(
            List<RdbmsTraceEvent> events,
            RdbmsLifecycleStage expectedStage,
            String expectedOutcome) {
        for (RdbmsTraceEvent event : events) {
            if (event.stage() != expectedStage
                    || !"transaction".equals(event.subject())) {
                continue;
            }
            if (!"TRANSACTION".equals(event.attributes().get("concept"))) {
                continue;
            }
            if (!expectedOutcome.equals(event.attributes().get("outcome"))) {
                continue;
            }
            if (!"DERBY_TRANSACTION".equals(event.attributes().get("provider"))) {
                continue;
            }
            return;
        }
        fail("Expected transaction boundary stage=" + expectedStage
                + ", outcome=" + expectedOutcome + " in events: " + events);
    }
}
