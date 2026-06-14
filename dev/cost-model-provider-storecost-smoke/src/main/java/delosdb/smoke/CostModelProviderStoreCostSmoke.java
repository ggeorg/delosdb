/*

   DelosDB - native StoreCostController cost-model provider smoke test

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
import io.github.ggeorg.delosdb.engine.extension.index.IndexProviderCostMode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Proves CostModelProvider's intended integration point: DelosDB adapts
 * provider cost into Derby's native StoreCostController#getScanCost path.
 *
 * <p>The smoke deliberately leaves the older FromBaseTable IndexProvider bridge
 * disabled. The observable diagnostics must therefore come from the store-cost
 * adapter, not from the optimizer reflection bridge.</p>
 */
public final class CostModelProviderStoreCostSmoke {
    private CostModelProviderStoreCostSmoke() {
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
            statement.executeUpdate("create table cost_model_store_smoke(id int, code int, name varchar(32))");
            statement.executeUpdate("create index cost_model_store_idx on cost_model_store_smoke(code) using btree");
            for (int i = 1; i <= 16; i++) {
                statement.executeUpdate("insert into cost_model_store_smoke values ("
                        + i + ", " + (i * 10) + ", 'name" + i + "')");
            }

            System.clearProperty(IndexProviderCostMode.PROPERTY_NAME);

            System.setProperty(CostModelMode.PROPERTY_NAME, "diagnostic");
            assertHeapProvider(connection, "diagnostic", false, 1);
            assertBTreeProvider(connection, "diagnostic", false, 2);

            System.setProperty(CostModelMode.PROPERTY_NAME, "enabled");
            assertHeapProvider(connection, "enabled", true, 3);
            assertBTreeProvider(connection, "enabled", true, 4);

            statement.executeUpdate("drop table cost_model_store_smoke");
        } finally {
            restoreProperty(CostModelMode.PROPERTY_NAME, previousCostModelMode);
            restoreProperty(IndexProviderCostMode.PROPERTY_NAME, previousIndexCostMode);
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB CostModelProvider StoreCostController smoke test passed.");
    }

    private static void assertHeapProvider(
            Connection connection,
            String expectedMode,
            boolean expectedConsumed,
            int recompileToken) throws SQLException {
        CostModelDiagnostics.clear();
        assertSingleIdByHeapScan(connection, "name6", 6, expectedMode + " heap store-cost query", recompileToken);
        CostModelProbe probe = assertProbe(expectedMode, "heap", 0, expectedConsumed);
        System.out.println(probe.diagnosticLine());
    }

    private static void assertBTreeProvider(
            Connection connection,
            String expectedMode,
            boolean expectedConsumed,
            int recompileToken) throws SQLException {
        CostModelDiagnostics.clear();
        assertSingleIdByBTreeScan(connection, 120, 12, expectedMode + " btree store-cost query", recompileToken);
        CostModelProbe probe = assertProbe(expectedMode, "btree", 1, expectedConsumed);
        System.out.println(probe.diagnosticLine());
    }

    private static void assertSingleIdByHeapScan(
            Connection connection,
            String name,
            int expectedId,
            String label,
            int recompileToken) throws SQLException {
        // The name column is intentionally not indexed, so the optimizer must
        // cost the heap access-method path.
        String sql = "select id from cost_model_store_smoke "
                + "where name = ? and " + recompileToken + " = " + recompileToken;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            assertSingleRow(statement, expectedId, label);
        }
    }

    private static void assertSingleIdByBTreeScan(
            Connection connection,
            int code,
            int expectedId,
            String label,
            int recompileToken) throws SQLException {
        // Keep optimizer probes as distinct SQL texts. Derby can reuse compiled
        // statements inside a connection; if a later pass reuses an earlier
        // plan, no new StoreCostController probe is produced.
        String sql = "select id from cost_model_store_smoke "
                + "--DERBY-PROPERTIES index=COST_MODEL_STORE_IDX\n"
                + "where code = ? and " + recompileToken + " = " + recompileToken;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, code);
            assertSingleRow(statement, expectedId, label);
        }
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

    private static CostModelProbe assertProbe(
            String expectedMode,
            String expectedProvider,
            int expectedFactoryId,
            boolean expectedConsumed) {
        CostModelProbe probe = CostModelDiagnostics.probes().stream()
                .filter(candidate -> expectedMode.equals(candidate.mode()))
                .filter(candidate -> expectedProvider.equals(candidate.providerName()))
                .filter(candidate -> candidate.factoryId() == expectedFactoryId)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected native StoreCostController adapter probe for "
                                + expectedProvider + " factory id " + expectedFactoryId
                                + " in mode " + expectedMode
                                + " but saw " + CostModelDiagnostics.probes()));
        SmokeUtils.assertEquals(expectedMode, probe.mode(), "cost model mode");
        SmokeUtils.assertEquals(expectedProvider, probe.providerName(), "cost model provider");
        SmokeUtils.assertEquals(expectedFactoryId, probe.factoryId(), "access-method factory id");
        SmokeUtils.assertEquals(true, probe.estimatePresent(), "provider estimate present");
        SmokeUtils.assertEquals(expectedConsumed, probe.consumed(), "provider cost consumed");
        SmokeUtils.assertEquals(expectedConsumed ? "provider" : "derby", probe.costSource(), "cost source");
        SmokeUtils.assertEquals(expectedConsumed ? "consumed" : "available", probe.decision(), "decision");
        if (!probe.canSafelyReplaceDerbyCost()) {
            throw new AssertionError("Expected provider estimate to pass safety checks: "
                    + probe.diagnosticLine());
        }
        if (probe.providerTotalCost() <= 0.0d) {
            throw new AssertionError("Expected positive provider total cost: " + probe.diagnosticLine());
        }
        if (probe.derbyCost() <= 0.0d) {
            throw new AssertionError("Expected positive Derby cost before adaptation: " + probe.diagnosticLine());
        }

        String line = probe.diagnosticLine();
        SmokeUtils.assertContains(line, "type=cost-model-provider", "diagnostic line");
        SmokeUtils.assertContains(line, "provider=" + expectedProvider, "diagnostic line");
        SmokeUtils.assertContains(line, "factoryId=" + expectedFactoryId, "diagnostic line");
        SmokeUtils.assertContains(line, "costSource=" + (expectedConsumed ? "provider" : "derby"), "diagnostic line");
        return probe;
    }

    private static void restoreProperty(String propertyName, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(propertyName);
        } else {
            System.setProperty(propertyName, previousValue);
        }
    }
}
