/*
 * Derby - Class org.apache.derbyTesting.functionTests.tests.delosdb.ModernRdbmsSelectLifecycleTraceTest
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

import io.github.ggeorg.delosdb.engine.rdbms.model.RdbmsStatementKind;
import io.github.ggeorg.delosdb.engine.rdbms.pipeline.RdbmsLifecycleStage;
import io.github.ggeorg.delosdb.engine.rdbms.trace.RdbmsTraceEvent;
import io.github.ggeorg.delosdb.engine.rdbms.trace.RdbmsTraceRegistry;

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
 * First executable proof that the DelosDB modern RDBMS model observes real Derby execution.
 *
 * <p>This test does not replace Derby planning or execution. It installs a trace sink, executes a
 * simple SELECT through the inherited JDBC/SQL path, and proves that the DelosDB model receives a
 * small lifecycle trace from real engine result-set execution.</p>
 */
public final class ModernRdbmsSelectLifecycleTraceTest extends BaseJDBCTestCase {
    private static final String TEST_TABLE = "MODERN_TRACE_TEST";

    public ModernRdbmsSelectLifecycleTraceTest(String name) {
        super(name);
    }

    public static Test suite() {
        Test test = TestConfiguration.embeddedSuite(
                ModernRdbmsSelectLifecycleTraceTest.class);
        return new CleanDatabaseTestSetup(test) {
            protected void decorateSQL(Statement s) throws SQLException {
                s.execute("create table " + TEST_TABLE
                        + " (id int primary key, name varchar(16))");
                s.execute("insert into " + TEST_TABLE
                        + " values (1, 'alpha'), (2, 'beta')");
            }
        };
    }

    public void testSimpleSelectEmitsLifecycleTrace() throws Exception {
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

        assertHasStage(events, RdbmsLifecycleStage.SQL_TEXT_RECEIVED,
                "statement");
        assertHasStatementKind(events, RdbmsLifecycleStage.SQL_TEXT_RECEIVED,
                RdbmsStatementKind.SELECT);
        assertHasStatementKind(events, RdbmsLifecycleStage.EXECUTION_STARTED,
                RdbmsStatementKind.SELECT);
        assertHasStage(events, RdbmsLifecycleStage.PHYSICAL_PLAN_CREATED,
                "table-scan");
        assertHasStage(events, RdbmsLifecycleStage.STORAGE_ACCESSED,
                "table-scan");
        assertHasStage(events, RdbmsLifecycleStage.ROWS_PRODUCED,
                "table-scan");
        assertHasStage(events, RdbmsLifecycleStage.EXECUTION_FINISHED,
                "table-scan");
    }

    private static void assertHasStage(
            List<RdbmsTraceEvent> events,
            RdbmsLifecycleStage stage,
            String subject) {
        for (RdbmsTraceEvent event : events) {
            if (event.stage() == stage && subject.equals(event.subject())) {
                return;
            }
        }
        fail("Expected trace stage " + stage + " for subject " + subject
                + " in events: " + events);
    }

    private static void assertHasStatementKind(
            List<RdbmsTraceEvent> events,
            RdbmsLifecycleStage stage,
            RdbmsStatementKind kind) {
        for (RdbmsTraceEvent event : events) {
            if (event.stage() == stage
                    && "statement".equals(event.subject())
                    && kind.name().equals(event.attributes().get("kind"))) {
                return;
            }
        }
        fail("Expected statement kind " + kind + " at stage " + stage
                + " in events: " + events);
    }
}
