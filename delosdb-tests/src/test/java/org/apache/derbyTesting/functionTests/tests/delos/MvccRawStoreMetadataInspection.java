/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreMetadataInspection

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.LockingPolicy;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.iapi.types.SQLLongint;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;

/** RawStore inspection used only by the database-wide MVCC identity proofs. */
final class MvccRawStoreMetadataInspection {
    static final String CONTAINER_PROPERTY =
            "delosdb.mvcc.rawStore.databaseMetadataContainerId";

    private static final int NEXT_TRANSACTION_ID_FIELD = 3;
    private static final int NEXT_COMMIT_SEQUENCE_FIELD = 4;
    private static final int COMMITTED_HIGH_WATER_FIELD = 5;

    private static final int CONTROL_VERSION_CONTAINER_FIELD = 4;
    private static final int VERSION_KIND_FIELD = 0;
    private static final int VERSION_KIND = 5;
    private static final int VERSION_ROW_ID_FIELD = 2;
    private static final int VERSION_ID_FIELD = 3;
    private static final int VERSION_CREATOR_TRANSACTION_ID_FIELD = 4;
    private static final int VERSION_BEGIN_SEQUENCE_FIELD = 5;
    private static final int VERSION_END_SEQUENCE_FIELD = 6;
    private static final int VERSION_PREVIOUS_VERSION_ID_FIELD = 7;
    private static final int VERSION_FLAGS_FIELD = 8;

    private MvccRawStoreMetadataInspection() {
    }

    static Counters counters(Connection connection) throws Exception {
        TransactionManager transactionManager = transactionManager(connection);
        Serializable value = transactionManager.getProperty(CONTAINER_PROPERTY);
        if (value == null) {
            throw new AssertionError("RawStore MVCC database metadata property is absent");
        }
        long containerId = Long.parseLong(value.toString());
        Transaction raw = transactionManager.getRawStoreXact();
        ContainerHandle container = raw.openContainer(
                new ContainerKey(0L, containerId),
                lockingPolicy(raw),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new AssertionError("RawStore MVCC database metadata container is absent");
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            return new Counters(
                    longField(raw, page, Page.FIRST_SLOT_NUMBER, NEXT_TRANSACTION_ID_FIELD),
                    longField(raw, page, Page.FIRST_SLOT_NUMBER, NEXT_COMMIT_SEQUENCE_FIELD),
                    longField(raw, page, Page.FIRST_SLOT_NUMBER, COMMITTED_HIGH_WATER_FIELD));
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    static List<VersionIdentity> versions(Connection connection, String tableName) throws Exception {
        long metadataContainerId = baseConglomerateId(connection, tableName);
        Transaction raw = transactionManager(connection).getRawStoreXact();
        long versionContainerId = versionContainerId(raw, metadataContainerId);
        ContainerHandle container = raw.openContainer(
                new ContainerKey(0L, versionContainerId),
                lockingPolicy(raw),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new AssertionError("RawStore MVCC version container is absent");
        }

        List<VersionIdentity> result = new ArrayList<>();
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    if (intField(raw, page, slot, VERSION_KIND_FIELD) != VERSION_KIND) {
                        continue;
                    }
                    result.add(new VersionIdentity(
                            longField(raw, page, slot, VERSION_ROW_ID_FIELD),
                            longField(raw, page, slot, VERSION_ID_FIELD),
                            longField(raw, page, slot, VERSION_CREATOR_TRANSACTION_ID_FIELD),
                            longField(raw, page, slot, VERSION_BEGIN_SEQUENCE_FIELD),
                            longField(raw, page, slot, VERSION_END_SEQUENCE_FIELD),
                            longField(raw, page, slot, VERSION_PREVIOUS_VERSION_ID_FIELD),
                            intField(raw, page, slot, VERSION_FLAGS_FIELD)));
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
        result.sort(Comparator.comparingLong(VersionIdentity::versionId));
        return result;
    }

    private static long versionContainerId(Transaction raw, long metadataContainerId)
            throws Exception {
        ContainerHandle container = raw.openContainer(
                new ContainerKey(0L, metadataContainerId),
                lockingPolicy(raw),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new AssertionError("RawStore MVCC table metadata container is absent");
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            return longField(
                    raw,
                    page,
                    Page.FIRST_SLOT_NUMBER,
                    CONTROL_VERSION_CONTAINER_FIELD);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static long baseConglomerateId(Connection connection, String tableName) throws Exception {
        String sql = "select c.conglomeratenumber "
                + "from sys.sysconglomerates c, sys.systables t, sys.sysschemas s "
                + "where c.tableid = t.tableid and t.schemaid = s.schemaid "
                + "and c.isindex = false and s.schemaname = 'APP' and t.tablename = '"
                + tableName.toUpperCase(java.util.Locale.ROOT) + "'";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new AssertionError("No base conglomerate for " + tableName);
            }
            return resultSet.getLong(1);
        }
    }

    private static TransactionManager transactionManager(Connection connection) {
        if (!(connection instanceof EmbedConnection embedded)) {
            throw new AssertionError("Embedded connection required for RawStore inspection");
        }
        LanguageConnectionContext lcc = embedded.getLanguageConnection();
        TransactionController controller = lcc.getTransactionExecute();
        if (!(controller instanceof TransactionManager transactionManager)) {
            throw new AssertionError("Derby transaction manager required for RawStore inspection");
        }
        return transactionManager;
    }

    private static long longField(Transaction raw, Page page, int slot, int field)
            throws Exception {
        StoreDataValue value = new SQLLongint(0L);
        page.fetchFieldFromSlot(slot, field, value);
        return StoreTypeUtil.getLong(value);
    }

    private static int intField(Transaction raw, Page page, int slot, int field)
            throws Exception {
        StoreDataValue value = new SQLInteger(0);
        page.fetchFieldFromSlot(slot, field, value);
        return Math.toIntExact(StoreTypeUtil.getLong(value));
    }

    private static LockingPolicy lockingPolicy(Transaction raw) {
        return raw.newLockingPolicy(
                LockingPolicy.MODE_NONE,
                TransactionController.ISOLATION_NOLOCK,
                false);
    }

    record Counters(long nextTransactionId, long nextCommitSequence, long committedHighWater) {
    }

    record VersionIdentity(
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginCommitSequence,
            long endCommitSequence,
            long previousVersionId,
            int flags) {
        boolean tombstone() {
            return (flags & 1) != 0;
        }
    }
}
