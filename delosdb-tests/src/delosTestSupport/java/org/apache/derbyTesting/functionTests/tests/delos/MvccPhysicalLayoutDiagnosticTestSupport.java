/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccPhysicalLayoutDiagnosticTestSupport

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements. See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/** Test-source-only bridge exposing the MVCC RawStore container roles to diagnostics. */
public final class MvccPhysicalLayoutDiagnosticTestSupport {
    private MvccPhysicalLayoutDiagnosticTestSupport() {
    }

    public static String[] describe(Connection connection, String tableName) throws Exception {
        MvccRawStoreMetadataInspection.PhysicalLayout layout =
                MvccRawStoreMetadataInspection.physicalLayout(connection, tableName);
        List<String> rows = new ArrayList<>();
        rows.add("database_metadata\t" + layout.databaseMetadataContainerId());
        rows.add("directory\t" + layout.tableMetadataContainerId());
        rows.add("version\t" + layout.versionContainerId());
        rows.add("ordered_index_directory\t" + layout.orderedIndexDirectoryContainerId());
        for (long containerId : layout.orderedIndexBtreeContainerIds()) {
            rows.add("ordered_index_btree\t" + containerId);
        }
        return rows.toArray(String[]::new);
    }
}
