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
    private static final String PAGE_LOCAL_DATABASE_ROOT =
            "jdbc-range-scan-page-local-surface-db";
    private static final String PAGE_LOCAL_PROPERTY =
            "delosdb.experimental.heapPageLocalIndexBaseFetch";
    private static final String PAGE_LOCAL_DIAGNOSTIC_PROPERTY =
            "delosdb.diagnostic.heapPageLocalIndexBaseFetch";

    @Override
    protected void tearDown() throws Exception {
        deleteDatabaseDirectory(DATABASE_ROOT + "-heap");
        deleteDatabaseDirectory(DATABASE_ROOT + "-mvcc");
        deleteDatabaseDirectory(PAGE_LOCAL_DATABASE_ROOT + "-heap");
        deleteDatabaseDirectory(PAGE_LOCAL_DATABASE_ROOT + "-mvcc");
        super.tearDown();
    }

    public void testHeapAndMvccRangeScanBenchmarkSurfaceIsEquivalent() throws Exception {
        try (SystemPropertyScope experiment = clearSystemProperty(PAGE_LOCAL_PROPERTY);
             SystemPropertyScope diagnostics =
                     setSystemProperty(PAGE_LOCAL_DIAGNOSTIC_PROPERTY, "true")) {
            resetPageLocalDiagnostics();
            DelosJdbcRangeScanSurfaceValidation.main(
                    new String[] {Path.of(DATABASE_ROOT).toString()});
            long[] observed = pageLocalDiagnostics();
            assertEquals("page-local experiment must be disabled by default", 0L, observed[0]);
            assertEquals("default path must not batch rows", 0L, observed[1]);
            assertEquals("default path must not use page-local acquisitions", 0L, observed[2]);
        }
    }

    public void testPageLocalIndexBaseExperimentPreservesRangeSurface() throws Exception {
        try (SystemPropertyScope experiment = setSystemProperty(PAGE_LOCAL_PROPERTY, "true");
             SystemPropertyScope diagnostics =
                     setSystemProperty(PAGE_LOCAL_DIAGNOSTIC_PROPERTY, "true")) {
            resetPageLocalDiagnostics();
            DelosJdbcRangeScanSurfaceValidation.main(
                    new String[] {Path.of(PAGE_LOCAL_DATABASE_ROOT).toString()});
            long[] observed = pageLocalDiagnostics();
            assertTrue("expected page-local index-to-base batches", observed[0] > 0L);
            assertTrue("expected page-local index-to-base rows", observed[1] > 0L);
            assertTrue("expected page acquisitions", observed[2] > 0L);
            assertTrue("expected at least some same-page coalescing", observed[2] < observed[1]);
        }
    }

    private static void resetPageLocalDiagnostics() throws Exception {
        diagnosticSupport().getMethod("reset").invoke(null);
    }

    private static long[] pageLocalDiagnostics() throws Exception {
        Method snapshot = diagnosticSupport().getMethod("snapshot");
        return (long[]) snapshot.invoke(null);
    }

    private static Class<?> diagnosticSupport() throws ClassNotFoundException {
        return Class.forName(
                "org.apache.derby.impl.sql.execute.HeapPageLocalIndexBaseDiagnosticTestSupport");
    }

    private static void deleteDatabaseDirectory(String databaseName) throws Exception {
        Path databasePath = Path.of(databaseName);
        if (!Files.exists(databasePath)) {
            return;
        }
        File[] notDeleted = PrivilegedFileOpsForTests.persistentRecursiveDelete(databasePath.toFile());
        assertEquals("database cleanup should delete every file under " + databaseName, 0, notDeleted.length);
    }
}
