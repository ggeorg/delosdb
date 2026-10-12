/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Properties;
import junit.framework.TestCase;

/** Actual EXPLAIN/catalog execution qualifies the relocated shared experiment helpers. */
public final class BenchmarkLegacyExplainTest extends TestCase {
    public void testLegacyExplainAndCaptureOnRealEmbeddedFixture() throws Exception {
        Properties original = (Properties) System.getProperties().clone();
        Path directory = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "delos-legacy-explain-");
        Path database = directory.resolve("database");
        try {
            for (String name : original.stringPropertyNames()) {
                if (name.startsWith(BenchmarkConfiguration.PREFIX)) System.clearProperty(name);
            }
            System.setProperty(BenchmarkConfiguration.PREFIX + "target", "delos_heap");
            System.setProperty(BenchmarkConfiguration.PREFIX + "run", "1");
            BenchmarkConfiguration configuration = BenchmarkConfiguration.fromSystemProperties();
            try (Connection connection = BenchmarkCaseMeasurement.connect(configuration, database)) {
                try {
                    DelosJdbcBenchmarkScenario fixture = new DelosJdbcBenchmarkScenario(
                            connection, "legacy_explain", "", false, DelosBenchmarkConfig.smoke());
                    fixture.prepare();
                    String index = BenchmarkExperimentSupport.phase2BPrimaryKeyIndex(connection, fixture.tableName(), "ID");
                    assertNotNull(index);
                    String sql = "select quantity from " + fixture.tableName() + " where id = 1";
                    var explain = BenchmarkExperimentSupport.capturePhase2NExplain(connection, sql, false);
                    var analyze = BenchmarkExperimentSupport.capturePhase2NExplain(connection, sql, true);
                    assertFalse(explain.text().isBlank());
                    assertFalse(explain.json().isBlank());
                    assertFalse(analyze.text().isBlank());
                    BenchmarkExperimentSupport.writePhase2ACapture(directory, "fixture", explain);
                    assertEquals(explain.text(), Files.readString(directory.resolve("fixture.txt")));
                    assertEquals(explain.json(), Files.readString(directory.resolve("fixture.json")));
                } finally {
                    connection.rollback();
                }
            }
        } finally {
            try {
                assertNull(BenchmarkCaseMeasurement.shutdownDerby(database, null));
                BenchmarkCaseMeasurement.deleteRecursively(database);
            } finally {
                System.setProperties(original);
            }
        }
    }
}
