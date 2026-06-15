/*

   DelosDB - optimizer access-path observability smoke test

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

package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.cost.CostModelDiagnostics;
import io.github.ggeorg.delosdb.engine.extension.cost.CostModelMode;
import io.github.ggeorg.delosdb.engine.extension.cost.CostModelProbe;
import io.github.ggeorg.delosdb.engine.extension.index.IndexProviderCostDiagnostics;
import io.github.ggeorg.delosdb.engine.extension.index.IndexProviderCostMode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Makes Derby's selected scan path visible beside DelosDB's CostModelProvider
 * v2 diagnostics.
 *
 * <p>This is deliberately observability-only. It does not alter optimizer
 * enumeration. It proves that a future path-oriented optimizer model can be
 * grounded in the current Derby access path, selected runtime statistics, and
 * native StoreCostController cost probes.</p>
 */
public final class OptimizerPathObservabilitySmoke {
    private static final String TABLE = "OPTIMIZER_PATH_SMOKE";
    private static final String INDEX = "OPTIMIZER_PATH_CODE_IDX";

    private OptimizerPathObservabilitySmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        String previousCostModelMode = System.getProperty(CostModelMode.PROPERTY_NAME);
        String previousIndexCostMode = System.getProperty(IndexProviderCostMode.PROPERTY_NAME);

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table optimizer_path_smoke("
                    + "id int primary key, code int, name varchar(32))");
            statement.executeUpdate("create index optimizer_path_code_idx "
                    + "on optimizer_path_smoke(code) using btree");
            for (int i = 1; i <= 24; i++) {
                statement.executeUpdate("insert into optimizer_path_smoke values ("
                        + i + ", " + (i * 10) + ", 'name" + i + "')");
            }

            System.setProperty(CostModelMode.PROPERTY_NAME, "enabled");
            System.clearProperty(IndexProviderCostMode.PROPERTY_NAME);
            IndexProviderCostDiagnostics.clear();

            OptimizerPathObservation heap = observeHeapPath(connection, statement);
            OptimizerPathObservation btree = observeBTreePath(connection, statement);

            System.out.println(heap.diagnosticLine());
            System.out.println(btree.diagnosticLine());

            statement.executeUpdate("drop table optimizer_path_smoke");
        } finally {
            restoreProperty(CostModelMode.PROPERTY_NAME, previousCostModelMode);
            restoreProperty(IndexProviderCostMode.PROPERTY_NAME, previousIndexCostMode);
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB optimizer path observability smoke test passed.");
    }

    private static OptimizerPathObservation observeHeapPath(
            Connection connection,
            Statement statement) throws SQLException {
        CostModelDiagnostics.clear();
        IndexProviderCostDiagnostics.clear();
        enableRuntimeStatistics(statement);

        String sql = "select id from optimizer_path_smoke "
                + "where name = ? and 101 = 101";
        try (PreparedStatement prepared = connection.prepareStatement(sql)) {
            prepared.setString(1, "name7");
            assertSingleRow(prepared, 7, "heap path query");
        }

        String runtimeStatistics = runtimeStatistics(statement);
        SmokeUtils.assertContains(runtimeStatistics, "Table Scan ResultSet for " + TABLE, "heap runtime statistics");
        assertDoesNotContain(runtimeStatistics, INDEX, "heap runtime statistics");

        CostModelProbe probe = probeFor("heap", 0);
        assertCommonProbe(probe, "heap path");
        assertLegacyIndexProviderBridgeDidNotFire("heap path query");
        return new OptimizerPathObservation(
                "heap-path",
                "table-scan",
                "heap",
                "none",
                probe,
                runtimeStatistics.length());
    }

    private static OptimizerPathObservation observeBTreePath(
            Connection connection,
            Statement statement) throws SQLException {
        CostModelDiagnostics.clear();
        IndexProviderCostDiagnostics.clear();
        enableRuntimeStatistics(statement);

        String sql = "select id from optimizer_path_smoke "
                + "--DERBY-PROPERTIES index=" + INDEX + "\n"
                + "where code = ? and 202 = 202";
        try (PreparedStatement prepared = connection.prepareStatement(sql)) {
            prepared.setInt(1, 180);
            assertSingleRow(prepared, 18, "btree path query");
        }

        String runtimeStatistics = runtimeStatistics(statement);
        SmokeUtils.assertContains(runtimeStatistics, "Index Scan ResultSet for " + TABLE, "btree runtime statistics");
        SmokeUtils.assertContains(runtimeStatistics, INDEX, "btree runtime statistics");

        CostModelProbe probe = probeFor("btree", 1);
        assertCommonProbe(probe, "btree path");
        assertLegacyIndexProviderBridgeDidNotFire("btree path query");
        return new OptimizerPathObservation(
                "btree-path",
                "index-scan",
                "btree",
                INDEX,
                probe,
                runtimeStatistics.length());
    }

    private static void enableRuntimeStatistics(Statement statement) throws SQLException {
        statement.execute("call SYSCS_UTIL.SYSCS_SET_RUNTIMESTATISTICS(1)");
    }

    private static String runtimeStatistics(Statement statement) throws SQLException {
        return SmokeUtils.singleString(statement, "values SYSCS_UTIL.SYSCS_GET_RUNTIMESTATISTICS()");
    }

    private static void assertSingleRow(
            PreparedStatement statement,
            int expectedId,
            String label) throws SQLException {
        try (ResultSet results = statement.executeQuery()) {
            if (!results.next()) {
                throw new AssertionError("No row returned for " + label);
            }
            int actualId = results.getInt(1);
            if (actualId != expectedId) {
                throw new AssertionError(label + " expected id " + expectedId + " but was " + actualId);
            }
            if (results.next()) {
                throw new AssertionError("More than one row returned for " + label);
            }
        }
    }

    private static CostModelProbe probeFor(String providerName, int factoryId) {
        return CostModelDiagnostics.probes().stream()
                .filter(candidate -> providerName.equals(candidate.providerName()))
                .filter(candidate -> candidate.factoryId() == factoryId)
                .filter(CostModelProbe::consumed)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected consumed CostModelProvider probe for "
                                + providerName + " factory id " + factoryId
                                + " but saw " + CostModelDiagnostics.probes()));
    }

    private static void assertCommonProbe(CostModelProbe probe, String label) {
        SmokeUtils.assertEquals("enabled", probe.mode(), label + " cost mode");
        SmokeUtils.assertEquals(probe.providerName(), probe.accessMethod(), label + " access method");
        SmokeUtils.assertEquals(CostModelProbe.ADAPTER_PATH, probe.adapterPath(), label + " adapter path");
        SmokeUtils.assertEquals("provider", probe.costSource(), label + " cost source");
        SmokeUtils.assertEquals("consumed", probe.decision(), label + " decision");
        if (probe.conglomerateId() <= 0L) {
            throw new AssertionError(label + " expected positive conglomerate id: " + probe.diagnosticLine());
        }
        if (probe.providerRows() < 0L || probe.derbyRows() < 0L) {
            throw new AssertionError(label + " expected non-negative row estimates: " + probe.diagnosticLine());
        }
        if (probe.providerTotalCost() <= 0.0d || probe.derbyCost() <= 0.0d) {
            throw new AssertionError(label + " expected positive costs: " + probe.diagnosticLine());
        }
    }

    private static void assertLegacyIndexProviderBridgeDidNotFire(String label) {
        if (IndexProviderCostDiagnostics.lastProbe() != null) {
            throw new AssertionError(
                    "Legacy IndexProviderCostBridge fired during " + label
                            + "; optimizer path observability must use StoreCostControllerBridge");
        }
    }

    private static void assertDoesNotContain(String actual, String unexpected, String label) {
        if (actual != null && actual.contains(unexpected)) {
            throw new AssertionError(label + " should not contain '" + unexpected + "' but was: " + actual);
        }
    }

    private static void restoreProperty(String propertyName, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(propertyName);
        } else {
            System.setProperty(propertyName, previousValue);
        }
    }

    private record OptimizerPathObservation(
            String label,
            String selectedResultSet,
            String selectedAccessMethod,
            String selectedIndex,
            CostModelProbe probe,
            int runtimeStatisticsLength) {
        String diagnosticLine() {
            return "DelosDBOptimizerPath{"
                    + "type=optimizer-path"
                    + ", label=" + label
                    + ", selectedResultSet=" + selectedResultSet
                    + ", selectedAccessMethod=" + selectedAccessMethod
                    + ", selectedIndex=" + selectedIndex
                    + ", path=" + probe.adapterPath()
                    + ", provider=" + probe.providerName()
                    + ", factoryId=" + probe.factoryId()
                    + ", conglomId=" + probe.conglomerateId()
                    + ", scanType=" + probe.scanType()
                    + ", costSource=" + probe.costSource()
                    + ", consumed=" + probe.consumed()
                    + ", derbyCost=" + probe.derbyCost()
                    + ", derbyRows=" + probe.derbyRows()
                    + ", providerTotalCost=" + probe.providerTotalCost()
                    + ", providerRows=" + probe.providerRows()
                    + ", runtimeStatisticsLength=" + runtimeStatisticsLength
                    + "}";
        }
    }
}
