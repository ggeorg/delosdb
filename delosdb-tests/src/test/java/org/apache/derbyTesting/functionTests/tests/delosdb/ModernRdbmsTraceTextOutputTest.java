/*
 * Derby - Class org.apache.derbyTesting.functionTests.tests.delosdb.ModernRdbmsTraceTextOutputTest
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
import io.github.ggeorg.delosdb.engine.trace.RdbmsTraceFormatter;
import io.github.ggeorg.delosdb.engine.trace.RdbmsTraceRegistry;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Test;

import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.JDBC;
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * Focused proof that captured modern RDBMS trace events can be rendered as reader-facing text.
 *
 * <p>This is diagnostics only. It uses the existing trace sink mechanism, executes a real SELECT,
 * and formats the captured events after execution. It does not add a source guard, alter routing,
 * or change query behavior.</p>
 */
public final class ModernRdbmsTraceTextOutputTest extends BaseJDBCTestCase {
    private static final String TEST_TABLE = "MODERN_TRACE_TEXT_TEST";

    public ModernRdbmsTraceTextOutputTest(String name) {
        super(name);
    }

    public static Test suite() {
        Test test = TestConfiguration.embeddedSuite(
                ModernRdbmsTraceTextOutputTest.class);
        return new CleanDatabaseTestSetup(test) {
            protected void decorateSQL(Statement s) throws SQLException {
                s.execute("create table " + TEST_TABLE
                        + " (id int primary key, name varchar(16))");
                s.execute("insert into " + TEST_TABLE
                        + " values (1, 'alpha'), (2, 'beta')");
            }
        };
    }

    public void testSelectTraceCanBeRenderedAsReadableText() throws Exception {
        List<RdbmsTraceEvent> events = new ArrayList<>();
        RdbmsTraceRegistry.setSink(events::add);
        try {
            Statement s = createStatement();
            ResultSet rs = s.executeQuery(
                    "select id, name from " + TEST_TABLE + " order by id");
            JDBC.assertFullResultSet(rs, new String[][] {
                    { "1", "alpha" },
                    { "2", "beta" }
            });
            rs.close();
            s.close();
        } finally {
            RdbmsTraceRegistry.reset();
        }

        String output = RdbmsTraceFormatter.format(events);

        assertContains(output, "SQL_TEXT_RECEIVED statement");
        assertContains(output, "kind=\"SELECT\"");
        assertContains(output, "PHYSICAL_PLAN_CREATED table-scan");
        assertContains(output, "STORAGE_ACCESSED table-scan");
        assertContains(output, "provider=\"DERBY_HEAP\"");
        assertContains(output, "accessKind=\"HEAP_SCAN\"");
        assertContains(output, "ROWS_PRODUCED table-scan");
        assertContains(output, "EXECUTION_FINISHED table-scan");
    }

    public void testFormatterUsesStableAttributeOrderingAndEscaping() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("zeta", "last");
        attributes.put("alpha", "line\nbreak");
        attributes.put("quote", "a \"quoted\" value");

        RdbmsTraceEvent event = RdbmsTraceEvent.of(
                RdbmsLifecycleStage.SQL_TEXT_RECEIVED,
                "statement",
                attributes);

        String output = RdbmsTraceFormatter.format(event);

        assertEquals("SQL_TEXT_RECEIVED statement "
                + "[alpha=\"line\\nbreak\" quote=\"a \\\"quoted\\\" value\" zeta=\"last\"]",
                output);
    }

    private static void assertContains(String text, String expected) {
        if (text.indexOf(expected) < 0) {
            fail("Expected to find [" + expected + "] in:\n" + text);
        }
    }
}
