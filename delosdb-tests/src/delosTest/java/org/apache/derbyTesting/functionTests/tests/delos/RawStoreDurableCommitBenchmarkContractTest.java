/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import junit.framework.TestCase;

/** Executes the actual option validator; it does not match Java/Gradle source text. */
public final class RawStoreDurableCommitBenchmarkContractTest extends TestCase {
    private static final String PREFIX = "delosdb.benchmark.crossEngineConcurrency.";
    private final Map<String, String> saved = new HashMap<>();

    @Override
    protected void setUp() {
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith(PREFIX)) {
                saved.put(key, System.getProperty(key));
            }
        }
        saved.keySet().forEach(System::clearProperty);
        Map.ofEntries(
                Map.entry("targets", "delos_heap_drda,delos_mvcc_drda,upstream_derby_drda,h2_server,postgresql,mariadb"),
                Map.entry("target", "delos_heap_drda"),
                Map.entry("run", "1"),
                Map.entry("remoteJdbcUrl", "jdbc:derby://127.0.0.1:1527/validation-only"),
                Map.entry("benchmarkContract", "rawstore-durable-commit-canonical"),
                Map.entry("f08TransactionStatusCrossEngine", "true"),
                Map.entry("sqlSemanticOracle", "true"),
                Map.entry("mvccGen2BServer", "true"),
                Map.entry("mvccGen2TransactionStatusVisibilityServer", "true"),
                Map.entry("rawStorePageValiditySnapshotServer", "true"),
                Map.entry("rawStoreMultiInsertPageServer", "true"),
                Map.entry("btreeInsertRootRoutingSnapshotServer", "true"),
                Map.entry("btreeInsertBranchRoutingSnapshotServer", "true"),
                Map.entry("rawStoreDurableCommitDiagnosticsServer", "true"),
                Map.entry("workloads", "INSERT_100"),
                Map.entry("insertTableShape", "PRIMARY_KEY_ONLY"),
                Map.entry("rows", "10000"), Map.entry("clients", "8"),
                Map.entry("widths", "1"), Map.entry("payload", "128"),
                Map.entry("transactionsPerClient", "2500"),
                Map.entry("fixedWorkloadOperationBudgetPerClient", "250000"),
                Map.entry("fixtureBatch", "100"), Map.entry("warmups", "0"),
                Map.entry("iterations", "1"), Map.entry("maximumWarmupIterations", "1"),
                Map.entry("maximumMeasuredIterations", "1"), Map.entry("runs", "8"))
                .forEach((key, value) -> System.setProperty(PREFIX + key, value));
    }

    @Override
    protected void tearDown() {
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith(PREFIX)) {
                System.clearProperty(key);
            }
        }
        saved.forEach(System::setProperty);
    }

    public void testBothCanonicalArmsPassTheExistingValidator() throws Exception {
        for (boolean enabled : new boolean[] {false, true}) {
            System.setProperty(PREFIX + "rawStoreDurableCommitServer", Boolean.toString(enabled));
            validate();
        }
    }

    public void testNoSyncCannotMasqueradeAsDurableCoordinatorEvidence() throws Exception {
        System.setProperty(PREFIX + "rawStoreDurableCommitServer", "true");
        System.setProperty(PREFIX + "rawStoreDurabilityTestNoSyncServer", "true");
        rejected("normal durability");
    }

    public void testRejectedWalExperimentsCannotContaminateTheComparison() throws Exception {
        System.setProperty(PREFIX + "rawStoreDurableCommitServer", "true");
        for (String control : new String[] {"rawStoreConcurrentLogAppendServer",
                "rawStorePreframedLogAppendServer", "rawStoreCombinedLogAppendServer"}) {
            System.setProperty(PREFIX + control, "true");
            try {
                rejected("inherited WAL append");
            } finally {
                System.clearProperty(PREFIX + control);
            }
        }
    }

    public void testOriginalClientAndTableShapeRestrictionsRemainInForce() throws Exception {
        System.setProperty(PREFIX + "rawStoreDurableCommitServer", "true");
        System.setProperty(PREFIX + "clients", "1,2,4,8");
        rejected("invalid clients");
        System.setProperty(PREFIX + "clients", "8");
        System.setProperty(PREFIX + "insertTableShape", "BARE");
        rejected("table shape");
    }

    private static void rejected(String message) throws Exception {
        try {
            validate();
            fail("expected invalid configuration: " + message);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }

    private static void validate() throws Exception {
        Class<?> options = Class.forName(
                "io.github.ggeorg.delosdb.benchmark.jdbc.DelosJdbcCrossEngineConcurrency$Options");
        Method read = options.getDeclaredMethod("fromSystemProperties");
        Method validate = options.getDeclaredMethod("validate");
        read.setAccessible(true);
        validate.setAccessible(true);
        try {
            validate.invoke(read.invoke(null));
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw failure;
        }
    }
}
