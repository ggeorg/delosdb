/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.derbyTesting.functionTests.util.PrivilegedFileOpsForTests;

import io.github.ggeorg.delosdb.benchmark.jdbc.DelosJdbcRangeScanSurfaceValidation;

/** Proves the ordered PK range-scan benchmark surface on heap and MVCC. */
public final class JdbcRangeScanBenchmarkSupportTest extends MvccSqlTestSupport {
    private static final String DATABASE_ROOT = "jdbc-range-scan-surface-db";

    @Override
    protected void tearDown() throws Exception {
        deleteDatabaseDirectory(DATABASE_ROOT + "-heap");
        deleteDatabaseDirectory(DATABASE_ROOT + "-mvcc");
        super.tearDown();
    }

    public void testHeapAndMvccRangeScanBenchmarkSurfaceIsEquivalent() throws Exception {
        DelosJdbcRangeScanSurfaceValidation.main(new String[] {Path.of(DATABASE_ROOT).toString()});
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
