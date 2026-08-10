/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Test-source-only physical-container classification for delete/reinsert attribution. */
public final class DelosDeleteReinsertPageTopologyTestSupport {
    private DelosDeleteReinsertPageTopologyTestSupport() {
    }

    public static Layout inspect(Connection connection, String tableName, boolean mvcc)
            throws Exception {
        CatalogLayout catalog = catalogLayout(connection, tableName);
        if (!mvcc) {
            return new Layout(
                    -1L,
                    catalog.baseContainerId(),
                    -1L,
                    -1L,
                    Set.of(),
                    catalog.indexContainerIds());
        }

        MvccRawStoreMetadataInspection.PhysicalLayout physical =
                MvccRawStoreMetadataInspection.physicalLayout(connection, tableName);
        return new Layout(
                physical.databaseMetadataContainerId(),
                physical.tableMetadataContainerId(),
                physical.versionContainerId(),
                physical.orderedIndexDirectoryContainerId(),
                Set.copyOf(physical.orderedIndexBtreeContainerIds()),
                catalog.indexContainerIds());
    }

    private static CatalogLayout catalogLayout(Connection connection, String tableName)
            throws Exception {
        long base = -1L;
        Set<Long> indexes = new HashSet<>();
        String sql = "select c.conglomeratenumber, c.isindex "
                + "from sys.sysconglomerates c, sys.systables t, sys.sysschemas s "
                + "where c.tableid = t.tableid and t.schemaid = s.schemaid "
                + "and s.schemaname = 'APP' and t.tablename = '"
                + tableName.toUpperCase(Locale.ROOT) + "'";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                long id = result.getLong(1);
                if (result.getBoolean(2)) {
                    indexes.add(id);
                } else {
                    base = id;
                }
            }
        }
        if (base < 0L) {
            throw new AssertionError("No base conglomerate for " + tableName);
        }
        return new CatalogLayout(base, Set.copyOf(indexes));
    }

    public enum Role {
        ALL,
        HEAP_TABLE,
        HEAP_BTREE,
        MVCC_DATABASE_METADATA,
        MVCC_METADATA_DIRECTORY,
        MVCC_VERSION,
        MVCC_ORDERED_INDEX_DIRECTORY,
        MVCC_ORDERED_INDEX_BTREE,
        OTHER
    }

    public record Layout(
            long databaseMetadataContainerId,
            long baseContainerId,
            long versionContainerId,
            long orderedIndexDirectoryContainerId,
            Set<Long> orderedIndexBtreeContainerIds,
            Set<Long> catalogIndexContainerIds) {
        public Layout {
            orderedIndexBtreeContainerIds = Set.copyOf(orderedIndexBtreeContainerIds);
            catalogIndexContainerIds = Set.copyOf(catalogIndexContainerIds);
        }

        public Role role(long containerId, boolean mvcc) {
            if (!mvcc) {
                if (containerId == baseContainerId) {
                    return Role.HEAP_TABLE;
                }
                return catalogIndexContainerIds.contains(containerId)
                        ? Role.HEAP_BTREE
                        : Role.OTHER;
            }
            if (containerId == databaseMetadataContainerId) {
                return Role.MVCC_DATABASE_METADATA;
            }
            if (containerId == baseContainerId) {
                return Role.MVCC_METADATA_DIRECTORY;
            }
            if (containerId == versionContainerId) {
                return Role.MVCC_VERSION;
            }
            if (containerId == orderedIndexDirectoryContainerId) {
                return Role.MVCC_ORDERED_INDEX_DIRECTORY;
            }
            if (orderedIndexBtreeContainerIds.contains(containerId)) {
                return Role.MVCC_ORDERED_INDEX_BTREE;
            }
            return Role.OTHER;
        }
    }

    private record CatalogLayout(long baseContainerId, Set<Long> indexContainerIds) {
    }
}
