/*
 * Derby - Class org.apache.derbyTesting.functionTests.tests.store.HeapSanityCheckerTest
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
import org.apache.derbyTesting.junit.TestConfiguration;

/**
 * SQL-visible proof that SYSCS_CHECK_TABLE reaches the Derby heap sanity
 * checker while preserving healthy heap behavior.
 */
public final class HeapSanityCheckerTest extends BaseJDBCTestCase {

    public HeapSanityCheckerTest(String name) {
        super(name);
    }

    public static Test suite() {
        return new CleanDatabaseTestSetup(
                TestConfiguration.embeddedSuite(HeapSanityCheckerTest.class));
    }

    public void testHealthyHeapShapesPassSyscsCheckTable() throws Exception {
        Statement s = createStatement();

        s.execute("create table heap_sanity_empty "
                + "(id int not null primary key, payload varchar(32))");
        assertCheckTable("HEAP_SANITY_EMPTY");

        s.execute("create table heap_sanity_rows "
                + "(id int not null primary key, key_value int not null, "
                + "payload varchar(128))");
        s.execute("create index heap_sanity_rows_key_idx "
                + "on heap_sanity_rows(key_value)");

        PreparedStatement ps = prepareStatement(
                "insert into heap_sanity_rows values (?, ?, ?)");
        for (int i = 1; i <= 32; i++) {
            ps.setInt(1, i);
            ps.setInt(2, i % 7);
            ps.setString(3, payload(i));
            ps.executeUpdate();
        }
        ps.close();
        assertCheckTable("HEAP_SANITY_ROWS");

        s.executeUpdate("delete from heap_sanity_rows where id in (3, 7, 11)");
        assertCheckTable("HEAP_SANITY_ROWS");

        s.executeUpdate("update heap_sanity_rows "
                + "set payload = payload || '-updated' "
                + "where id in (5, 9, 13)");
        assertCheckTable("HEAP_SANITY_ROWS");

        commit();
        getConnection().close();
        assertCheckTable("HEAP_SANITY_EMPTY");
        assertCheckTable("HEAP_SANITY_ROWS");
    }

    private static String payload(int id) {
        String seed = "row-" + id + "-";
        StringBuilder builder = new StringBuilder(96);
        while (builder.length() < 96) {
            builder.append(seed);
        }
        return builder.substring(0, 96);
    }
}
