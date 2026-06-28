/*
 * Derby - Class org.apache.derbyTesting.functionTests.tests.delosdb.ModernRdbmsTraceSummaryTest
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
import io.github.ggeorg.delosdb.engine.trace.RdbmsTraceSummary;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import junit.framework.Test;

import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.JDBC;
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * Focused proof that an already-captured modern RDBMS trace can be summarized as row-flow facts.
 *
 * <p>This is diagnostics only. It uses the existing trace sink mechanism and derives the summary
 * after a real SELECT finishes. It does not add new Derby hooks, source guards, storage routing, or
 * query behavior.</p>
 */
public final class ModernRdbmsTraceSummaryTest extends BaseJDBCTestCase {
    private static final String TEST_TABLE = "MODERN_TRACE_SUMMARY_TEST";

    public ModernRdbmsTraceSummaryTest(String name) {
        super(name);
    }

    public static Test suite() {
        Test test = TestConfiguration.embeddedSuite(
                ModernRdbmsTraceSummaryTest.class);
        return new CleanDatabaseTestSetup(test) {
            protected void decorateSQL(Statement s) throws SQLException {
                s.execute("create table " + TEST_TABLE
                        + " (id int, name varchar(16))");
                s.execute("insert into " + TEST_TABLE
                        + " values (1, 'alpha'), (2, 'beta'), (3, 'gamma')");
            }
        };
    }

    public void testSelectTraceCanBeSummarizedAsRowFlow() throws Exception {
        List<RdbmsTraceEvent> events = new ArrayList<>();
        RdbmsTraceRegistry.setSink(events::add);
        try {
            Statement s = createStatement();
            ResultSet rs = s.executeQuery(
                    "select id, name from " + TEST_TABLE);
            JDBC.assertUnorderedResultSet(rs, new String[][] {
                    { "1", "alpha" },
                    { "2", "beta" },
                    { "3", "gamma" }
            });
            rs.close();
            s.close();
        } finally {
            RdbmsTraceRegistry.reset();
        }

        RdbmsTraceSummary summary = RdbmsTraceSummary.summarize(events);

        assertEquals("SELECT", summary.statementKind());
        assertTrue(summary.executionStarted());
        assertTrue(summary.executionFinished());
        assertEquals(1, summary.storageAccesses());
        assertEquals("DERBY_HEAP", summary.storageProvider());
        assertEquals("HEAP_SCAN", summary.storageAccessKind());
        assertEquals(3L, summary.rowsProduced());

        String output = summary.format();
        assertContains(output, "statement kind: SELECT");
        assertContains(output, "execution started: true");
        assertContains(output, "execution finished: true");
        assertContains(output, "storage accesses: 1");
        assertContains(output, "storage provider: DERBY_HEAP");
        assertContains(output, "storage access kind: HEAP_SCAN");
        assertContains(output, "rows produced: 3");
    }

    public void testSummaryAggregatesSyntheticRowCounters() {
        List<RdbmsTraceEvent> events = new ArrayList<>();
        events.add(RdbmsTraceEvent.of(
                RdbmsLifecycleStage.SQL_TEXT_RECEIVED,
                "statement",
                Map.of("kind", "SELECT")));
        events.add(RdbmsTraceEvent.of(
                RdbmsLifecycleStage.STORAGE_ACCESSED,
                "table-scan",
                Map.of(
                        "provider", "DERBY_BTREE",
                        "accessKind", "BTREE_INDEX_SCAN")));
        events.add(RdbmsTraceEvent.of(
                RdbmsLifecycleStage.ROWS_PRODUCED,
                "table-scan",
                Map.of("rowsThisScan", "2")));
        events.add(RdbmsTraceEvent.of(
                RdbmsLifecycleStage.ROWS_PRODUCED,
                "table-scan",
                Map.of("rowsThisScan", "4")));
        events.add(RdbmsTraceEvent.of(
                RdbmsLifecycleStage.EXECUTION_FINISHED,
                "table-scan",
                Map.of(
                        "rowsSeen", "7",
                        "rowsFiltered", "1")));
        events.add(RdbmsTraceEvent.of(
                RdbmsLifecycleStage.TRANSACTION_COMMITTED,
                "transaction",
                Map.of("outcome", "COMMIT")));

        RdbmsTraceSummary summary = RdbmsTraceSummary.summarize(events);

        assertEquals("SELECT", summary.statementKind());
        assertEquals(1, summary.storageAccesses());
        assertEquals("DERBY_BTREE", summary.storageProvider());
        assertEquals("BTREE_INDEX_SCAN", summary.storageAccessKind());
        assertEquals(6L, summary.rowsProduced());
        assertEquals(7L, summary.rowsSeen());
        assertEquals(1L, summary.rowsFiltered());
        assertEquals("COMMIT", summary.transactionOutcome());
    }

    private static void assertContains(String text, String expected) {
        if (text.indexOf(expected) < 0) {
            fail("Expected to find [" + expected + "] in:\n" + text);
        }
    }
}
