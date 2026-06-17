/*

   Derby - Class org.apache.derbyBuild.asm.AsmExperimentalSqlCompilerSmoke

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derbyBuild.asm;

import delosdb.smoke.SmokeUtils;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.apache.derby.impl.services.bytecode.ExperimentalBytecodeJavaFactory;

/**
 * Boots the real embedded SQL compiler with the experimental JavaFactory
 * selector and explicitly selects the ASM backend. BCJava remains the default
 * in modules.properties; this smoke uses only JVM properties inside this test
 * process.
 */
public final class AsmExperimentalSqlCompilerSmoke {
    private static final String MODULE_PROPERTY = "derby.module.javaCompiler";
    private static final String MODULE_PROPERTY_COMPAT = "derby.module.JavaCompiler";
    private static final String SELECTOR_CLASS = ExperimentalBytecodeJavaFactory.class.getName();

    private AsmExperimentalSqlCompilerSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String previousModule = System.getProperty(MODULE_PROPERTY);
        String previousCompatModule = System.getProperty(MODULE_PROPERTY_COMPAT);
        String previousBackend = System.getProperty(ExperimentalBytecodeJavaFactory.BACKEND_PROPERTY);

        String databasePath = args[0];
        try {
            ExperimentalBytecodeJavaFactory.resetLastBootedBackendName();
            System.setProperty(MODULE_PROPERTY, SELECTOR_CLASS);
            System.setProperty(MODULE_PROPERTY_COMPAT, SELECTOR_CLASS);
            System.setProperty(ExperimentalBytecodeJavaFactory.BACKEND_PROPERTY,
                    ExperimentalBytecodeJavaFactory.BACKEND_ASM);

            SmokeUtils.loadEmbeddedDriver();
            try (Connection connection = SmokeUtils.connect(databasePath, true);
                 Statement statement = connection.createStatement()) {
                assertSingleInt(statement, "values 1", 1, "ASM VALUES integer compile/execute");
                assertSingleLong(statement, "values cast(1 as bigint)", 1L,
                        "ASM VALUES bigint cast compile/execute");
                assertSingleDouble(statement, "values cast(1 as double)", 1.0d,
                        "ASM VALUES double cast compile/execute");
                assertSingleString(statement, "values 'abc'", "abc",
                        "ASM VALUES string compile/execute");
                assertSingleInt(statement, "values case when 1 = 1 then 10 else 20 end", 10,
                        "ASM CASE true branch compile/execute");
                assertSingleInt(statement, "values case when 1 = 0 then 10 else 20 end", 20,
                        "ASM CASE false branch compile/execute");
                assertSingleNull(statement, "values cast(null as varchar(10))",
                        "ASM typed NULL compile/execute");
                assertSingleString(statement,
                        "values coalesce(cast(null as varchar(10)), cast('x' as varchar(10)))",
                        "x", "ASM COALESCE compile/execute");

                statement.executeUpdate("create table t (id int, name varchar(20))");
                int inserted = statement.executeUpdate("insert into t values (1, 'a')");
                if (inserted != 1) {
                    throw new AssertionError("ASM INSERT expected update count 1 but got " + inserted);
                }
                assertSingleTableRow(statement, "select id, name from t", 1, "a",
                        "ASM table scan compile/execute");
                assertSingleInt(statement, "select id from t where id = 1", 1,
                        "ASM predicate select compile/execute");
            }

            String selected = ExperimentalBytecodeJavaFactory.lastBootedBackendName();
            if (!ExperimentalBytecodeJavaFactory.BACKEND_ASM.equals(selected)) {
                throw new AssertionError("Expected real SQL compiler boot path to select ASM backend but saw: "
                        + selected);
            }
        } finally {
            try {
                SmokeUtils.shutdown(databasePath);
            } finally {
                restoreProperty(MODULE_PROPERTY, previousModule);
                restoreProperty(MODULE_PROPERTY_COMPAT, previousCompatModule);
                restoreProperty(ExperimentalBytecodeJavaFactory.BACKEND_PROPERTY, previousBackend);
            }
        }

        System.out.println("ASM experimental SQL compiler smoke passed: backend=asm matrix="
                + "values,int-casts,double-casts,string,case,null,coalesce,table,predicate");
    }

    private static void assertSingleInt(Statement statement, String sql, int expected, String label) throws Exception {
        try (ResultSet results = statement.executeQuery(sql)) {
            if (!results.next()) {
                throw new AssertionError(label + ": no row returned");
            }
            int actual = results.getInt(1);
            if (actual != expected) {
                throw new AssertionError(label + ": expected " + expected + " but got " + actual);
            }
            assertNoExtraRows(results, label);
        }
    }

    private static void assertSingleLong(Statement statement, String sql, long expected, String label) throws Exception {
        try (ResultSet results = statement.executeQuery(sql)) {
            if (!results.next()) {
                throw new AssertionError(label + ": no row returned");
            }
            long actual = results.getLong(1);
            if (actual != expected) {
                throw new AssertionError(label + ": expected " + expected + " but got " + actual);
            }
            assertNoExtraRows(results, label);
        }
    }

    private static void assertSingleDouble(Statement statement, String sql, double expected, String label)
            throws Exception {
        try (ResultSet results = statement.executeQuery(sql)) {
            if (!results.next()) {
                throw new AssertionError(label + ": no row returned");
            }
            double actual = results.getDouble(1);
            if (Double.compare(actual, expected) != 0) {
                throw new AssertionError(label + ": expected " + expected + " but got " + actual);
            }
            assertNoExtraRows(results, label);
        }
    }

    private static void assertSingleString(Statement statement, String sql, String expected, String label)
            throws Exception {
        try (ResultSet results = statement.executeQuery(sql)) {
            if (!results.next()) {
                throw new AssertionError(label + ": no row returned");
            }
            String actual = results.getString(1);
            if (!expected.equals(actual)) {
                throw new AssertionError(label + ": expected " + expected + " but got " + actual);
            }
            assertNoExtraRows(results, label);
        }
    }

    private static void assertSingleNull(Statement statement, String sql, String label) throws Exception {
        try (ResultSet results = statement.executeQuery(sql)) {
            if (!results.next()) {
                throw new AssertionError(label + ": no row returned");
            }
            Object actual = results.getObject(1);
            if (actual != null) {
                throw new AssertionError(label + ": expected NULL but got " + actual);
            }
            assertNoExtraRows(results, label);
        }
    }

    private static void assertSingleTableRow(Statement statement, String sql, int expectedId, String expectedName,
            String label) throws Exception {
        try (ResultSet results = statement.executeQuery(sql)) {
            if (!results.next()) {
                throw new AssertionError(label + ": no row returned");
            }
            int actualId = results.getInt(1);
            String actualName = results.getString(2);
            if (actualId != expectedId || !expectedName.equals(actualName)) {
                throw new AssertionError(label + ": expected (" + expectedId + ", " + expectedName + ") but got ("
                        + actualId + ", " + actualName + ")");
            }
            assertNoExtraRows(results, label);
        }
    }

    private static void assertNoExtraRows(ResultSet results, String label) throws Exception {
        if (results.next()) {
            throw new AssertionError(label + ": more than one row returned");
        }
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
