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

        System.out.println("ASM experimental SQL compiler smoke passed: backend=asm sql=VALUES 1");
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
            if (results.next()) {
                throw new AssertionError(label + ": more than one row returned");
            }
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
