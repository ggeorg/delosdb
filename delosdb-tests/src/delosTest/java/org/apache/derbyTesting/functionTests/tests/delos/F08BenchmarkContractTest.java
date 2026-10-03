/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
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

/** Exercises the authoritative F08 benchmark-mode contract through the real option validator. */
public final class F08BenchmarkContractTest extends TestCase {
    private static final String PREFIX = "delosdb.benchmark.crossEngineConcurrency.";
    private final Map<String, String> saved = new HashMap<>();

    @Override
    protected void setUp() {
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith(PREFIX)) {
                saved.put(key, System.getProperty(key));
            }
        }
        clearBenchmarkProperties();
        configureTransactionStatusBaseline();
    }

    @Override
    protected void tearDown() {
        clearBenchmarkProperties();
        saved.forEach(System::setProperty);
    }

    public void testTransactionStatusContractAcceptsCanonicalInsert() throws Exception {
        validate();
    }

    public void testF08ControlsRequireExplicitContract() throws Exception {
        clear("benchmarkContract");
        rejected("explicit benchmark contract");
    }

    public void testFixedCostContractOwnsScalingShape() throws Exception {
        set("benchmarkContract", "f08-fixed-cost-scaling");
        set("f08FixedCostClientScaling", "true");
        set("clients", "1,2,4,8");
        set("payload", "16");
        validate();

        set("payload", "128");
        rejected("payload=16");
    }

    public void testFixedCostBareShapeRequiresMultiInsertPageMode() throws Exception {
        set("benchmarkContract", "f08-fixed-cost-scaling");
        set("f08FixedCostClientScaling", "true");
        set("clients", "1,2,4,8");
        set("payload", "16");
        set("insertTableShape", "BARE");
        set("mvccGen2A1Server", "true");
        set("mvccGen2BServer", "false");

        rejected("PRIMARY_KEY_ONLY");
        set("rawStoreMultiInsertPageServer", "true");
        validate();
    }

    public void testContentionSliceOwnsAttributionTargetsAndProfile() throws Exception {
        set("benchmarkContract", "f08-contention-slice");
        set("f08FixedCostClientScaling", "true");
        set("f08ContentionScalingSlice", "true");
        set("targets", "delos_heap_drda,delos_mvcc_drda,upstream_derby_drda");
        set("target", "delos_heap_drda");
        set("clients", "4");
        set("payload", "16");
        set("profileServerTargets", "delos_heap_drda,delos_mvcc_drda,upstream_derby_drda");
        validate();

        clear("profileServerTargets");
        rejected("requires server profiling");
    }

    public void testLeafAndStructuralProofContractsAreDistinct() throws Exception {
        configureBTreeProof("f08-btree-leaf-latch-proof", false);
        validate();

        set("btreeInsertStructuralDiagnostics", "true");
        rejected("must not enable structural diagnostics");

        set("benchmarkContract", "f08-btree-split-authority-proof");
        validate();
    }

    private static void configureTransactionStatusBaseline() {
        set("benchmarkContract", "f08-transaction-status");
        set("targets", "delos_heap_drda,delos_mvcc_drda,upstream_derby_drda,h2_server,postgresql,mariadb");
        set("target", "delos_heap_drda");
        set("run", "1");
        set("remoteJdbcUrl", "jdbc:derby://127.0.0.1:1527/validation-only");
        set("f08TransactionStatusCrossEngine", "true");
        set("sqlSemanticOracle", "true");
        set("mvccGen2BServer", "true");
        set("mvccGen2TransactionStatusVisibilityServer", "true");
        set("workloads", "INSERT_100");
        set("insertTableShape", "PRIMARY_KEY_ONLY");
        set("rows", "10000");
        set("clients", "8");
        set("widths", "1");
        set("payload", "128");
        set("transactionsPerClient", "2500");
        set("fixedWorkloadOperationBudgetPerClient", "250000");
        set("fixtureBatch", "100");
        set("warmups", "0");
        set("iterations", "1");
        set("minimumWarmupSeconds", "0.0");
        set("maximumWarmupIterations", "1");
        set("minimumMeasuredSeconds", "0.0");
        set("maximumMeasuredIterations", "1");
        set("runs", "1");
    }

    private static void configureBTreeProof(String contract, boolean structural) {
        clearBenchmarkProperties();
        set("benchmarkContract", contract);
        set("targets", "delos_heap");
        set("target", "delos_heap");
        set("run", "1");
        set("f08BTreeLeafLatchProof", "true");
        set("pageLatchDiagnostics", "true");
        set("heapAuthorityDiagnostics", "true");
        set("btreeInsertStructuralDiagnostics", Boolean.toString(structural));
        set("workloads", "INSERT_100");
        set("insertTableShape", "PRIMARY_KEY_ONLY");
        set("rows", "10000");
        set("clients", "1,2,4,8");
        set("widths", "1");
        set("payload", "16");
        set("transactionsPerClient", "2500");
        set("fixedWorkloadOperationBudgetPerClient", "250000");
        set("fixtureBatch", "100");
        set("warmups", "0");
        set("iterations", "1");
        set("minimumWarmupSeconds", "0.0");
        set("maximumWarmupIterations", "1");
        set("minimumMeasuredSeconds", "0.0");
        set("maximumMeasuredIterations", "1");
        set("runs", "1");
    }

    private static void rejected(String message) throws Exception {
        try {
            validate();
            fail("expected invalid configuration containing: " + message);
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

    private static void set(String key, String value) {
        System.setProperty(PREFIX + key, value);
    }

    private static void clear(String key) {
        System.clearProperty(PREFIX + key);
    }

    private static void clearBenchmarkProperties() {
        for (String key : System.getProperties().stringPropertyNames().toArray(String[]::new)) {
            if (key.startsWith(PREFIX)) {
                System.clearProperty(key);
            }
        }
    }
}
