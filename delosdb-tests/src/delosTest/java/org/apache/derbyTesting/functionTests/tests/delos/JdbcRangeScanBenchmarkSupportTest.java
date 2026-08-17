/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.derbyTesting.functionTests.util.PrivilegedFileOpsForTests;

import io.github.ggeorg.delosdb.benchmark.jdbc.DelosJdbcRangeScanSurfaceValidation;

/** Proves the ordered PK range-scan benchmark surface on heap and MVCC. */
public final class JdbcRangeScanBenchmarkSupportTest extends MvccSqlTestSupport {
    private static final String DATABASE_ROOT = "jdbc-range-scan-surface-db";
    private static final String REUSABLE_FETCH_DATABASE_ROOT =
            "jdbc-range-scan-reusable-fetch-surface-db";
    private static final String REUSABLE_FETCH_PROPERTY =
            "delosdb.experimental.heapReusableFetchDescriptor";
    private static final String REUSABLE_FETCH_DIAGNOSTIC_PROPERTY =
            "delosdb.diagnostic.heapReusableFetchDescriptor";

    @Override
    protected void tearDown() throws Exception {
        deleteDatabaseDirectory(DATABASE_ROOT + "-heap");
        deleteDatabaseDirectory(DATABASE_ROOT + "-mvcc");
        deleteDatabaseDirectory(REUSABLE_FETCH_DATABASE_ROOT + "-heap");
        deleteDatabaseDirectory(REUSABLE_FETCH_DATABASE_ROOT + "-mvcc");
        super.tearDown();
    }

    public void testHeapAndMvccRangeScanBenchmarkSurfaceIsEquivalent() throws Exception {
        try (SystemPropertyScope experiment = clearSystemProperty(REUSABLE_FETCH_PROPERTY);
             SystemPropertyScope diagnostics =
                     setSystemProperty(REUSABLE_FETCH_DIAGNOSTIC_PROPERTY, "true")) {
            resetReusableFetchDiagnostics();
            DelosJdbcRangeScanSurfaceValidation.main(
                    new String[] {Path.of(DATABASE_ROOT).toString()});
            assertEquals("reusable fetch experiment must be disabled by default",
                    0L, reusableFetches());
        }
    }

    public void testReusableHeapFetchDescriptorPreservesRangeSurface() throws Exception {
        try (SystemPropertyScope experiment = setSystemProperty(REUSABLE_FETCH_PROPERTY, "true");
             SystemPropertyScope diagnostics =
                     setSystemProperty(REUSABLE_FETCH_DIAGNOSTIC_PROPERTY, "true")) {
            resetReusableFetchDiagnostics();
            DelosJdbcRangeScanSurfaceValidation.main(
                    new String[] {Path.of(REUSABLE_FETCH_DATABASE_ROOT).toString()});
            assertTrue("expected reusable Heap fetch-descriptor activity", reusableFetches() > 0L);
        }
    }

    private static void resetReusableFetchDiagnostics() throws Exception {
        diagnosticSupport().getMethod("reset").invoke(null);
    }

    private static long reusableFetches() throws Exception {
        Method fetches = diagnosticSupport().getMethod("fetches");
        return ((Long) fetches.invoke(null)).longValue();
    }

    private static Class<?> diagnosticSupport() throws ClassNotFoundException {
        return Class.forName(
                "org.apache.derby.impl.sql.execute.HeapReusableFetchDescriptorDiagnosticTestSupport");
    }

    private static void deleteDatabaseDirectory(String databaseName) throws Exception {
        Path databasePath = Path.of(databaseName);
        if (!Files.exists(databasePath)) {
            return;
        }
        File[] notDeleted = PrivilegedFileOpsForTests.persistentRecursiveDelete(databasePath.toFile());
        assertEquals("database cleanup should delete every file under " + databaseName,
                0, notDeleted.length);
    }
}
