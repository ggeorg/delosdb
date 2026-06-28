/*
 * Derby - Class org.apache.derbyTesting.functionTests.tests.delosdb.ModernRdbmsObservedPlanTest
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

import io.github.ggeorg.delosdb.engine.trace.RdbmsObservedPlan;
import io.github.ggeorg.delosdb.engine.trace.RdbmsPlanNodeKind;
import io.github.ggeorg.delosdb.engine.trace.RdbmsStorageAccessKind;
import io.github.ggeorg.delosdb.engine.trace.RdbmsStorageProviderKind;
import io.github.ggeorg.delosdb.engine.trace.RdbmsTraceEvent;
import io.github.ggeorg.delosdb.engine.trace.RdbmsTraceRegistry;

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
 * Focused proof that an already-captured trace can explain Derby's observed table access path.
 *
 * <p>This is a Phase 25 baseline observation, not an optimizer replacement. It derives a
 * reader-facing plan summary from events that the existing Derby execution hooks already emitted.
 * It does not add optimizer hooks, change costing, change storage routing, or influence execution.</p>
 */
public final class ModernRdbmsObservedPlanTest extends BaseJDBCTestCase {
    private static final String TEST_TABLE = "MODERN_OBSERVED_PLAN_TEST";
    private static final String NAME_INDEX = "MODERN_OBSERVED_PLAN_NAME_IDX";

    public ModernRdbmsObservedPlanTest(String name) {
        super(name);
    }

    public static Test suite() {
        Test test = TestConfiguration.embeddedSuite(
                ModernRdbmsObservedPlanTest.class);
        return new CleanDatabaseTestSetup(test) {
            protected void decorateSQL(Statement s) throws SQLException {
                s.execute("create table " + TEST_TABLE
                        + " (id int, name varchar(16))");
                s.execute("create index " + NAME_INDEX + " on " + TEST_TABLE
                        + " (name)");
                s.execute("insert into " + TEST_TABLE
                        + " values (1, 'alpha'), (2, 'beta'), (3, 'gamma')");
            }
        };
    }

    public void testHeapSelectCanBeExplainedAsObservedTableScanPlan() throws Exception {
        List<RdbmsTraceEvent> events = traceQuery(
                "select id, name from " + TEST_TABLE + " order by id",
                new String[][] {
                        { "1", "alpha" },
                        { "2", "beta" },
                        { "3", "gamma" }
                });

        RdbmsObservedPlan plan = RdbmsObservedPlan.observe(events);

        assertEquals(RdbmsPlanNodeKind.TABLE_SCAN, plan.nodeKind());
        assertTrue(plan.physicalPlanObserved());
        assertEquals(TEST_TABLE, plan.table());
        assertEquals("", plan.index());
        assertEquals(RdbmsStorageProviderKind.DERBY_HEAP, plan.storageProvider());
        assertEquals(RdbmsStorageAccessKind.HEAP_SCAN, plan.storageAccessKind());
        assertFalse(plan.predicatePushdownObserved());
        assertFalse(plan.keyedAccessObserved());

        String output = plan.format();
        assertContains(output, "plan node: TABLE_SCAN");
        assertContains(output, "storage provider: DERBY_HEAP");
        assertContains(output, "storage access kind: HEAP_SCAN");
    }

    public void testForcedIndexSelectCanBeExplainedAsObservedIndexScanPlan() throws Exception {
        List<RdbmsTraceEvent> events = traceQuery(
                "select name from " + TEST_TABLE
                        + " --DERBY-PROPERTIES index=" + NAME_INDEX + "\n"
                        + " where name = 'beta'",
                new String[][] {{ "beta" }});

        RdbmsObservedPlan plan = RdbmsObservedPlan.observe(events);

        assertEquals(RdbmsPlanNodeKind.INDEX_SCAN, plan.nodeKind());
        assertTrue(plan.physicalPlanObserved());
        assertEquals(TEST_TABLE, plan.table());
        assertEquals(NAME_INDEX, plan.index());
        assertEquals(RdbmsStorageProviderKind.DERBY_BTREE, plan.storageProvider());
        assertTrue(plan.storageAccessKind() == RdbmsStorageAccessKind.BTREE_INDEX_SCAN
                || plan.storageAccessKind() == RdbmsStorageAccessKind.BTREE_KEYED_LOOKUP);
        assertTrue(plan.predicatePushdownObserved());
        assertTrue(plan.keyedAccessObserved());

        String output = plan.format();
        assertContains(output, "plan node: INDEX_SCAN");
        assertContains(output, "index: " + NAME_INDEX);
        assertContains(output, "storage provider: DERBY_BTREE");
    }

    private List<RdbmsTraceEvent> traceQuery(String sql, String[][] expectedRows)
            throws Exception {
        List<RdbmsTraceEvent> events = new ArrayList<>();
        RdbmsTraceRegistry.setSink(events::add);
        try {
            Statement s = createStatement();
            ResultSet rs = s.executeQuery(sql);
            JDBC.assertFullResultSet(rs, expectedRows);
            rs.close();
            s.close();
        } finally {
            RdbmsTraceRegistry.reset();
        }
        return events;
    }

    private static void assertContains(String text, String expected) {
        if (text.indexOf(expected) < 0) {
            fail("Expected to find [" + expected + "] in:\n" + text);
        }
    }
}
