/*
 * Derby - Class org.apache.derbyTesting.functionTests.tests.store.ExternalSortJvmPolicyProofTest
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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import junit.framework.Test;

import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.JDBC;
import org.apache.derbyTesting.junit.RuntimeStatisticsParser;
import org.apache.derbyTesting.junit.SQLUtilities;
import org.apache.derbyTesting.junit.SystemPropertyTestSetup;
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * Proof coverage for the JVM-aware external sort memory policy.
 *
 * <p>The policy change keeps Derby's row-count sort contract while replacing
 * the inherited fixed 1 MiB automatic memory target with a conservative
 * JVM-aware target. These tests exercise the public SQL surface with a tiny
 * {@code derby.storage.sortBufferMax} override so that ORDER BY, DISTINCT, and
 * GROUP BY still behave correctly. The exact choice between in-memory and
 * external sort is intentionally not asserted here: that is a policy detail
 * covered by {@code SortMemoryPolicyProbe}, and it can legitimately vary with
 * row estimates, payload size, and JVM configuration.</p>
 */
public class ExternalSortJvmPolicyProofTest extends BaseJDBCTestCase {

    private static final int ROW_COUNT = 24;

    public ExternalSortJvmPolicyProofTest(String name) {
        super(name);
    }

    public static Test suite() {
        Properties sysProps = new Properties();
        sysProps.put("derby.storage.sortBufferMax", "5");

        Test test = TestConfiguration.embeddedSuite(
                ExternalSortJvmPolicyProofTest.class);
        return new CleanDatabaseTestSetup(
                new SystemPropertyTestSetup(test, sysProps, true)) {
            protected void decorateSQL(Statement s) throws SQLException {
                s.execute("create table sort_policy_proof "
                        + "(id int not null, grp int not null, payload varchar(256))");
                PreparedStatement ps = s.getConnection().prepareStatement(
                        "insert into sort_policy_proof values (?, ?, ?)");
                for (int i = 0; i < ROW_COUNT; i++) {
                    int id = ROW_COUNT - i;
                    ps.setInt(1, id);
                    ps.setInt(2, id % 4);
                    ps.setString(3, paddedPayload(id));
                    ps.executeUpdate();
                }
                ps.close();
            }
        };
    }

    /**
     * ORDER BY must preserve ordering and require a sort node. The test avoids
     * asserting the exact internal/external sorter choice because the JVM-aware
     * policy is allowed to keep small proof data in memory.
     */
    public void testOrderByRequiresSortAndPreservesOrder()
            throws Exception {
        Statement s = createStatement();
        s.execute("CALL SYSCS_UTIL.SYSCS_SET_RUNTIMESTATISTICS(1)");

        ResultSet rs = s.executeQuery(
                "select id from sort_policy_proof order by id");
        for (int expected = 1; expected <= ROW_COUNT; expected++) {
            assertTrue("missing row " + expected, rs.next());
            assertEquals(expected, rs.getInt(1));
        }
        assertFalse(rs.next());
        rs.close();

        RuntimeStatisticsParser parser =
                SQLUtilities.getRuntimeStatisticsParser(s);
        assertTrue("Expected ORDER BY to require a sort node",
                parser.whatSortingRequired());
    }

    /**
     * DISTINCT must keep duplicate elimination stable under the same tiny
     * sort-buffer override.
     */
    public void testDistinctResultsRemainStableWithTinySortBuffer()
            throws Exception {
        JDBC.assertFullResultSet(
                createStatement().executeQuery(
                        "select distinct grp from sort_policy_proof order by grp"),
                new String[][] { { "0" }, { "1" }, { "2" }, { "3" } });
    }

    /**
     * GROUP BY must preserve aggregate results under the tiny sort-buffer
     * override.
     */
    public void testGroupByResultsRemainStableWithTinySortBuffer()
            throws Exception {
        JDBC.assertFullResultSet(
                createStatement().executeQuery(
                        "select grp, count(*) from sort_policy_proof "
                        + "group by grp order by grp"),
                new String[][] {
                    { "0", "6" }, { "1", "6" },
                    { "2", "6" }, { "3", "6" }
                });
    }

    private static String paddedPayload(int id) {
        String seed = "payload-" + id + "-";
        StringBuilder builder = new StringBuilder(192);
        while (builder.length() < 192) {
            builder.append(seed);
        }
        return builder.substring(0, 192);
    }
}
