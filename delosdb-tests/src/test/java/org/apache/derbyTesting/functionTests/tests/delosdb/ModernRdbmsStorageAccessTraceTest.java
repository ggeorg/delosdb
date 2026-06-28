/*
 * Derby - Class org.apache.derbyTesting.functionTests.tests.delosdb.ModernRdbmsStorageAccessTraceTest
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
 * Focused proof that the modern RDBMS trace exposes storage-provider and access-method facts.
 *
 * <p>The test observes existing Derby execution choices. It does not change planning, optimizer
 * costing, storage routing, or row production.</p>
 */
public final class ModernRdbmsStorageAccessTraceTest extends BaseJDBCTestCase {
    private static final String TEST_TABLE = "MODERN_STORAGE_TRACE_TEST";
    private static final String NAME_INDEX = "MODERN_STORAGE_TRACE_NAME_IDX";

    public ModernRdbmsStorageAccessTraceTest(String name) {
        super(name);
    }

    public static Test suite() {
        Test test = TestConfiguration.embeddedSuite(
                ModernRdbmsStorageAccessTraceTest.class);
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

    public void testHeapSelectReportsDerbyHeapScan() throws Exception {
        List<RdbmsTraceEvent> events = traceQuery(
                "select id, name from " + TEST_TABLE + " order by id",
                new String[][] {
                        { "1", "alpha" },
                        { "2", "beta" },
                        { "3", "gamma" }
                });

        assertStorageObservation(
                events,
                RdbmsStorageProviderKind.DERBY_HEAP,
                "",
                RdbmsStorageAccessKind.HEAP_SCAN);
    }

    public void testForcedIndexSelectReportsDerbyBtreeAccess() throws Exception {
        List<RdbmsTraceEvent> events = traceQuery(
                "select name from " + TEST_TABLE
                        + " --DERBY-PROPERTIES index=" + NAME_INDEX + "\n"
                        + " where name = 'beta'",
                new String[][] {{ "beta" }});

        assertStorageObservation(
                events,
                RdbmsStorageProviderKind.DERBY_BTREE,
                NAME_INDEX,
                RdbmsStorageAccessKind.BTREE_INDEX_SCAN,
                RdbmsStorageAccessKind.BTREE_KEYED_LOOKUP);
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

    private static void assertStorageObservation(
            List<RdbmsTraceEvent> events,
            RdbmsStorageProviderKind expectedProvider,
            String expectedIndex,
            RdbmsStorageAccessKind... expectedAccessKinds) {
        for (RdbmsTraceEvent event : events) {
            if (event.stage() != RdbmsLifecycleStage.STORAGE_ACCESSED
                    || !"table-scan".equals(event.subject())) {
                continue;
            }
            if (!TEST_TABLE.equals(event.attributes().get("table"))) {
                continue;
            }
            if (!expectedProvider.name().equals(event.attributes().get("provider"))) {
                continue;
            }
            if (!matchesAnyAccessKind(event.attributes().get("accessKind"), expectedAccessKinds)) {
                continue;
            }
            if (!expectedIndex.isEmpty()
                    && !expectedIndex.equals(event.attributes().get("index"))) {
                continue;
            }
            return;
        }
        fail("Expected storage observation provider=" + expectedProvider
                + ", accessKind=" + accessKinds(expectedAccessKinds)
                + ", index=" + expectedIndex
                + " in events: " + events);
    }

    private static boolean matchesAnyAccessKind(
            String actual,
            RdbmsStorageAccessKind[] expectedAccessKinds) {
        for (int i = 0; i < expectedAccessKinds.length; i++) {
            if (expectedAccessKinds[i].name().equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private static String accessKinds(RdbmsStorageAccessKind[] accessKinds) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < accessKinds.length; i++) {
            if (i > 0) {
                builder.append('|');
            }
            builder.append(accessKinds[i].name());
        }
        return builder.toString();
    }
}
