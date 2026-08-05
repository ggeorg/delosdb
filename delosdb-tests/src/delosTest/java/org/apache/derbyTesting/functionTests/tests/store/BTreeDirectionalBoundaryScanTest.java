/*
 * Derby - Class org.apache.derbyTesting.functionTests.tests.store.BTreeDirectionalBoundaryScanTest
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
import java.sql.Statement;
import junit.framework.Test;
import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.JDBC;
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * Boundary checks for indexed forward range scans and backward max scans.
 *
 * <p>This test deliberately exercises the public SQL surface rather than
 * reaching into B-tree pages. The inherited B-tree search code is sensitive:
 * {@code ControlRow.searchForEntry()} and its backward-search sibling encode
 * page-slot boundary rules used by forward scans, max scans, and split/restart
 * logic. These cases protect the observable boundaries before any attempt to
 * remove duplication inside the B-tree search implementation.</p>
 */
public class BTreeDirectionalBoundaryScanTest extends BaseJDBCTestCase {

    public BTreeDirectionalBoundaryScanTest(String name) {
        super(name);
    }

    public static Test suite() {
        return new CleanDatabaseTestSetup(
                TestConfiguration.embeddedSuite(BTreeDirectionalBoundaryScanTest.class));
    }

    protected void tearDown() throws Exception {
        dropTable("T");
        super.tearDown();
    }

    /**
     * Forward index scans should preserve lower/upper boundary behavior for
     * exact hits, gaps between keys, and duplicate keys.
     */
    public void testForwardRangeScanBoundaries() throws Exception {
        Statement s = createStatement();
        createBoundaryTable(s);

        JDBC.assertFullResultSet(
                s.executeQuery("select x from t --derby-properties index=i\n"
                        + "where x >= -5 and x <= 2 order by x"),
                new String[][] { {"-5"}, {"0"}, {"1"}, {"2"}, {"2"} });

        JDBC.assertFullResultSet(
                s.executeQuery("select x from t --derby-properties index=i\n"
                        + "where x > 2 and x < 100 order by x"),
                new String[][] { {"3"} });

        JDBC.assertFullResultSet(
                s.executeQuery("select x from t --derby-properties index=i\n"
                        + "where x > -10 and x < -5 order by x"),
                new String[][] { });
    }

    /**
     * Backward max scans should return the correct key at the right edge, at a
     * duplicate boundary, before the first key, and inside a gap between keys.
     */
    public void testBackwardMaxScanBoundaries() throws Exception {
        Statement s = createStatement();
        createBoundaryTable(s);

        JDBC.assertSingleValueResultSet(
                s.executeQuery("select max(x) from t --derby-properties index=i"),
                "100");

        JDBC.assertSingleValueResultSet(
                s.executeQuery("select max(x) from t --derby-properties index=i\n"
                        + "where x < 100"),
                "3");

        JDBC.assertSingleValueResultSet(
                s.executeQuery("select max(x) from t --derby-properties index=i\n"
                        + "where x <= 2"),
                "2");

        JDBC.assertSingleValueResultSet(
                s.executeQuery("select max(x) from t --derby-properties index=i\n"
                        + "where x < -10"),
                null);
    }

    private void createBoundaryTable(Statement s) throws Exception {
        s.execute("create table t(x int, payload int)");
        PreparedStatement insert = prepareStatement(
                "insert into t(x, payload) values (?, ?)");
        int[] values = { -10, -5, 0, 1, 2, 2, 3, 100 };
        for (int i = 0; i < values.length; i++) {
            insert.setInt(1, values[i]);
            insert.setInt(2, i);
            insert.executeUpdate();
        }
        s.execute("create index i on t(x)");
    }
}
