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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import org.apache.derby.iapi.services.context.ContextManager;
import org.apache.derby.iapi.services.context.ContextService;
import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.access.ScanController;
import org.apache.derby.iapi.store.access.StoreCostController;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodUniqueConstraintLifecycle;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.LockingPolicy;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.iapi.types.SQLLongint;
import org.apache.derby.shared.common.i18n.MessageService;
import org.apache.derby.shared.common.reference.SQLState;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;

/** Low-level RawStore inspection used by focused MVCC convergence proofs. */
final class MvccRawStoreMetadataInspection {
    static final String CONTAINER_PROPERTY =
            "delosdb.mvcc.rawStore.databaseMetadataContainerId";

    private static final int NEXT_TRANSACTION_ID_FIELD = 3;
    private static final int NEXT_COMMIT_SEQUENCE_FIELD = 4;
    private static final int RECOVERY_PUBLICATION_CEILING_FIELD = 5;

    private static final int CONTROL_VERSION_CONTAINER_FIELD = 4;
    private static final int CONTROL_COLUMN_COUNT_FIELD = 5;
    private static final int CONTROL_FIXED_FIELDS = 7;

    private static final int ORDERED_INDEX_CONTROL_KIND_FIELD = 0;
    private static final int ORDERED_INDEX_CONTAINER_KIND = 6;
    private static final int ORDERED_INDEX_BTREE_DESCRIPTOR_KIND = 8;
    private static final int ORDERED_INDEX_DIRECTORY_COLUMN_ID_FIELD = 2;
    private static final int ORDERED_INDEX_DIRECTORY_BTREE_CONGLOMERATE_FIELD = 3;
    private static final int ORDERED_INDEX_KEY_FIELD = 0;
    private static final int ORDERED_INDEX_ROW_ID_FIELD = 1;
    private static final int ORDERED_INDEX_VERSION_ID_FIELD = 2;
    private static final int ORDERED_INDEX_ROW_LOCATION_FIELD = 3;
    private static final int ORDERED_INDEX_FIELD_COUNT = 4;

    private static final int DIRECTORY_KIND_FIELD = 0;
    private static final int DIRECTORY_KIND = 3;
    private static final int DIRECTORY_ROW_ID_FIELD = 2;
    private static final int DIRECTORY_HEAD_VERSION_ID_FIELD = 3;
    private static final int DIRECTORY_HEAD_HINT_PAGE_FIELD = 4;
    private static final int DIRECTORY_HEAD_HINT_RECORD_FIELD = 5;
    private static final int DIRECTORY_HEAD_CREATOR_TRANSACTION_ID_FIELD = 6;
    private static final int DIRECTORY_HEAD_BEGIN_SEQUENCE_FIELD = 7;
    private static final int DIRECTORY_HEAD_FLAGS_FIELD = 8;
    private static final int DIRECTORY_BASE_FIELD_COUNT = 4;
    private static final int DIRECTORY_HINT_FIELD_COUNT = 6;
    private static final int DIRECTORY_HEAD_SUMMARY_FIELD_COUNT = 9;
    private static final int VERSION_KIND_FIELD = 0;
    private static final int VERSION_KIND = 5;
    private static final int VERSION_ROW_ID_FIELD = 2;
    private static final int VERSION_ID_FIELD = 3;
    private static final int VERSION_CREATOR_TRANSACTION_ID_FIELD = 4;
    private static final int VERSION_BEGIN_SEQUENCE_FIELD = 5;
    private static final int VERSION_END_SEQUENCE_FIELD = 6;
    private static final int VERSION_PREVIOUS_VERSION_ID_FIELD = 7;
    private static final int VERSION_FLAGS_FIELD = 8;
    private static final int VERSION_PAYLOAD_START = 9;
    private static final int VERSION_LOOKUP_HINT_FIELD_COUNT = 2;

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
                    longField(raw, page, Page.FIRST_SLOT_NUMBER, RECOVERY_PUBLICATION_CEILING_FIELD));
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }


    static List<UniqueConstraintIdentity> uniqueConstraints(
            Connection connection,
            String tableName) throws Exception {
        long metadataContainerId = baseConglomerateId(connection, tableName);
        Transaction raw = transactionManager(connection).getRawStoreXact();
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
            int slot = Page.FIRST_SLOT_NUMBER;
            int columnCount = intField(raw, page, slot, CONTROL_COLUMN_COUNT_FIELD);
            int uniqueCountField = CONTROL_FIXED_FIELDS + (columnCount * 2) + 1;
            if (page.fetchNumFieldsAtSlot(slot) <= uniqueCountField) {
                return List.of();
            }
            int count = intField(raw, page, slot, uniqueCountField);
            int field = uniqueCountField + 1;
            List<UniqueConstraintIdentity> result = new ArrayList<>(count);
            for (int ordinal = 1; ordinal <= count; ordinal++) {
                boolean duplicateNullsAllowed = intField(raw, page, slot, field++) != 0;
                int keyWidth = intField(raw, page, slot, field++);
                int[] columns = new int[keyWidth];
                for (int index = 0; index < keyWidth; index++) {
                    columns[index] = intField(raw, page, slot, field++);
                }
                result.add(new UniqueConstraintIdentity(
                        ordinal,
                        columns,
                        duplicateNullsAllowed));
            }
            return List.copyOf(result);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    static void insertBaseRowDirect(
            Connection connection,
            String tableName,
            StoreDataValue[] row) throws Exception {
        TransactionManager manager = transactionManager(connection);
        ConglomerateController controller = manager.openConglomerate(
                baseConglomerateId(connection, tableName),
                false,
                TransactionController.OPENMODE_FORUPDATE,
                TransactionController.MODE_TABLE,
                TransactionController.ISOLATION_READ_COMMITTED);
        try {
            controller.insert(row);
        } finally {
            controller.close();
        }
    }

    static long storeCostEstimatedRowCount(
            Connection connection, String tableName) throws Exception {
        if (!(connection instanceof EmbedConnection embedded)) {
            throw new AssertionError("Embedded connection required for RawStore inspection");
        }
        LanguageConnectionContext lcc = embedded.getLanguageConnection();
        ContextManager contextManager = lcc.getContextManager();
        ContextService contextService = ContextService.getFactory();
        // A direct StoreCostController open can fault a conglomerate into the
        // access-method cache. That cache loader resolves the transaction from
        // the current Derby context, just as the normal compiler path does.
        boolean installed = contextService.getCurrentContextManager() != contextManager;
        if (installed) {
            contextService.setCurrentContextManager(contextManager);
        }
        try {
            TransactionManager manager = transactionManager(connection);
            StoreCostController cost =
                    manager.openStoreCost(baseConglomerateId(connection, tableName));
            try {
                return cost.getEstimatedRowCount();
            } finally {
                cost.close();
            }
        } finally {
            if (installed) {
                contextService.resetCurrentContextManager(contextManager);
            }
        }
    }

    static void setBaseScanEstimatedRowCount(
            Connection connection, String tableName, long count) throws Exception {
        TransactionManager manager = transactionManager(connection);
        ScanController scan = manager.openScan(
                baseConglomerateId(connection, tableName),
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_READ_UNCOMMITTED,
                null,
                null,
                ScanController.NA,
                null,
                null,
                ScanController.NA);
        try {
            scan.setEstimatedRowCount(count);
        } finally {
            scan.close();
        }
    }

    static void addNativeUniqueConstraint(
            Connection connection,
            String tableName,
            int... columns) throws Exception {
        TransactionManager manager = transactionManager(connection);
        ConglomerateController controller = manager.openConglomerate(
                baseConglomerateId(connection, tableName),
                false,
                TransactionController.OPENMODE_FORUPDATE,
                TransactionController.MODE_TABLE,
                TransactionController.ISOLATION_READ_COMMITTED);
        try {
            if (!(controller instanceof AccessMethodUniqueConstraintLifecycle lifecycle)) {
                throw new AssertionError("MVCC base controller lacks native unique lifecycle");
            }
            lifecycle.addUniqueConstraint(columns, true, false);
        } finally {
            controller.close();
        }
    }

    static long orderedIndexContainerId(Connection connection, String tableName) throws Exception {
        long metadataContainerId = baseConglomerateId(connection, tableName);
        TableLayout layout = tableLayout(
                transactionManager(connection).getRawStoreXact(),
                metadataContainerId);
        return layout.orderedIndexContainerId();
    }

    static PhysicalLayout physicalLayout(Connection connection, String tableName) throws Exception {
        TransactionManager manager = transactionManager(connection);
        Serializable value = manager.getProperty(CONTAINER_PROPERTY);
        if (value == null) {
            throw new AssertionError("RawStore MVCC database metadata property is absent");
        }
        long metadataContainerId = baseConglomerateId(connection, tableName);
        Transaction raw = manager.getRawStoreXact();
        TableLayout layout = tableLayout(raw, metadataContainerId);
        List<Long> btrees = orderedIndexMappings(raw, layout).stream()
                .map(OrderedIndexMapping::btreeConglomerate)
                .toList();
        return new PhysicalLayout(
                Long.parseLong(value.toString()),
                metadataContainerId,
                layout.versionContainerId(),
                layout.orderedIndexContainerId(),
                btrees);
    }

    static boolean containerExists(Connection connection, long containerId) throws Exception {
        if (containerId <= 0L) {
            return false;
        }
        Transaction raw = transactionManager(connection).getRawStoreXact();
        ContainerHandle container = raw.openContainer(
                new ContainerKey(0L, containerId),
                lockingPolicy(raw),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            return false;
        }
        container.close();
        return true;
    }

    static long orderedIndexPageCount(Connection connection, String tableName) throws Exception {
        TransactionManager manager = transactionManager(connection);
        Transaction raw = manager.getRawStoreXact();
        TableLayout layout = tableLayout(raw, baseConglomerateId(connection, tableName));
        if (layout.orderedIndexContainerId() <= 0L) {
            return 0L;
        }
        long pageCount = countContainerPages(
                raw, new ContainerKey(0L, layout.orderedIndexContainerId()));
        for (OrderedIndexMapping mapping : orderedIndexMappings(raw, layout)) {
            pageCount += countContainerPages(
                    raw, new ContainerKey(0L, manager.findContainerid(mapping.btreeConglomerate())));
        }
        return pageCount;
    }

    static int orderedIndexBtreeCount(Connection connection, String tableName) throws Exception {
        TransactionManager manager = transactionManager(connection);
        Transaction raw = manager.getRawStoreXact();
        TableLayout layout = tableLayout(raw, baseConglomerateId(connection, tableName));
        return orderedIndexMappings(raw, layout).size();
    }

    static RowLocationIdentity insertAndFetchBaseRowLocation(
            Connection connection,
            String tableName,
            int value) throws Exception {
        TransactionManager manager = transactionManager(connection);
        long baseConglomerate = baseConglomerateId(connection, tableName);
        ConglomerateController base = manager.openConglomerate(
                baseConglomerate,
                false,
                TransactionController.OPENMODE_FORUPDATE,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_SERIALIZABLE);
        try {
            StoreRowLocation location = base.newRowLocationTemplate();
            base.insertAndFetchLocation(
                    new StoreDataValue[] {new SQLInteger(value)},
                    location);
            return rowLocationIdentity((StoreDataValue) location);
        } finally {
            base.close();
        }
    }

    static List<RowLocationIdentity> baseScanRowLocations(
            Connection connection,
            String tableName) throws Exception {
        TransactionManager manager = transactionManager(connection);
        long baseConglomerate = baseConglomerateId(connection, tableName);
        ConglomerateController base = manager.openConglomerate(
                baseConglomerate,
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_READ_UNCOMMITTED);
        ScanController scan = manager.openScan(
                baseConglomerate,
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_READ_UNCOMMITTED,
                null,
                null,
                ScanController.NA,
                null,
                null,
                ScanController.NA);
        List<RowLocationIdentity> result = new ArrayList<>();
        try {
            StoreRowLocation location = base.newRowLocationTemplate();
            while (scan.next()) {
                scan.fetchLocation(location);
                result.add(rowLocationIdentity((StoreDataValue) location));
            }
        } finally {
            scan.close();
            base.close();
        }
        return List.copyOf(result);
    }

    static OrderedIndexProbeStats orderedIndexProbeStats(
            Connection connection,
            String tableName,
            int columnId,
            int key) throws Exception {
        TransactionManager manager = transactionManager(connection);
        long metadataContainerId = baseConglomerateId(connection, tableName);
        Transaction raw = manager.getRawStoreXact();
        TableLayout layout = tableLayout(raw, metadataContainerId);
        OrderedIndexMapping mapping = orderedIndexMappings(raw, layout).stream()
                .filter(candidate -> candidate.columnId() == columnId)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No RawStore MVCC ordered-index mapping for " + tableName
                                + " column " + columnId));
        ConglomerateController base = manager.openConglomerate(
                metadataContainerId,
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_READ_UNCOMMITTED);
        StoreDataValue[] bound = new StoreDataValue[] {new SQLInteger(key)};
        ScanController scan = manager.openScan(
                mapping.btreeConglomerate(),
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_READ_UNCOMMITTED,
                null,
                bound,
                ScanController.GE,
                null,
                bound,
                ScanController.GT);
        int nextCalls = 0;
        int candidates = 0;
        try {
            StoreDataValue[] row = orderedIndexRowTemplate(
                    raw, layout, columnId, base.newRowLocationTemplate());
            while (true) {
                nextCalls++;
                if (!scan.fetchNext(row)) {
                    break;
                }
                candidates++;
            }
            Properties properties = scan.getScanInfo().getAllScanInfo(null);
            return new OrderedIndexProbeStats(
                    metric(properties, SQLState.STORE_RTS_NUM_PAGES_VISITED),
                    metric(properties, SQLState.STORE_RTS_NUM_ROWS_VISITED),
                    metric(properties, SQLState.STORE_RTS_NUM_ROWS_QUALIFIED),
                    metric(properties, SQLState.STORE_RTS_TREE_HEIGHT),
                    nextCalls,
                    candidates);
        } finally {
            scan.close();
            base.close();
        }
    }

    private static long metric(Properties properties, String sqlState) {
        String key = MessageService.getTextMessage(sqlState);
        String value = properties.getProperty(key);
        if (value == null) {
            throw new AssertionError("Missing scan metric " + key + ": " + properties);
        }
        return Long.parseLong(value);
    }

    static List<OrderedIndexIdentity> orderedIndexEntries(
            Connection connection,
            String tableName) throws Exception {
        TransactionManager manager = transactionManager(connection);
        long metadataContainerId = baseConglomerateId(connection, tableName);
        Transaction raw = manager.getRawStoreXact();
        TableLayout layout = tableLayout(raw, metadataContainerId);
        if (layout.orderedIndexContainerId() <= 0L) {
            return List.of();
        }
        List<OrderedIndexMapping> mappings = orderedIndexMappings(raw, layout);
        if (mappings.isEmpty()) {
            return List.of();
        }
        ConglomerateController base = manager.openConglomerate(
                metadataContainerId,
                false,
                0,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_READ_UNCOMMITTED);
        List<OrderedIndexIdentity> result = new ArrayList<>();
        try {
            for (OrderedIndexMapping mapping : mappings) {
                ScanController scan = manager.openScan(
                        mapping.btreeConglomerate(),
                        false,
                        0,
                        TransactionController.MODE_RECORD,
                        TransactionController.ISOLATION_READ_UNCOMMITTED,
                        null,
                        null,
                        ScanController.NA,
                        null,
                        null,
                        ScanController.NA);
                try {
                    while (scan.next()) {
                        StoreDataValue[] row = orderedIndexRowTemplate(
                                raw, layout, mapping.columnId(), base.newRowLocationTemplate());
                        scan.fetch(row);
                        Object keyObject = StoreTypeUtil.getObject(row[ORDERED_INDEX_KEY_FIELD]);
                        RowLocationIdentity rowLocation = rowLocationIdentity(
                                row[ORDERED_INDEX_ROW_LOCATION_FIELD]);
                        result.add(new OrderedIndexIdentity(
                                mapping.columnId(),
                                keyObject == null ? null : keyObject.toString(),
                                StoreTypeUtil.getLong(row[ORDERED_INDEX_ROW_ID_FIELD]),
                                StoreTypeUtil.getLong(row[ORDERED_INDEX_VERSION_ID_FIELD]),
                                rowLocation.hasLocator(),
                                rowLocation.pageId(),
                                rowLocation.slotId()));
                    }
                } finally {
                    scan.close();
                }
            }
        } finally {
            base.close();
        }
        result.sort(Comparator
                .comparingInt(OrderedIndexIdentity::columnId)
                .thenComparing(OrderedIndexIdentity::key,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(OrderedIndexIdentity::rowId)
                .thenComparingLong(OrderedIndexIdentity::versionId));
        return List.copyOf(result);
    }

    private static RowLocationIdentity rowLocationIdentity(StoreDataValue value)
            throws Exception {
        Object rowLocation = StoreTypeUtil.getObject(value);
        String text = String.valueOf(rowLocation);
        int pageMarker = text.indexOf("@page:");
        if (pageMarker < 0) {
            if (text.endsWith("@locator:none")) {
                return new RowLocationIdentity(false, 0L, -1);
            }
            throw new AssertionError("Unexpected MVCC row-location text: " + text);
        }
        int slotMarker = text.indexOf(":slot:", pageMarker + 6);
        if (slotMarker < 0) {
            throw new AssertionError("Unexpected MVCC row-location text: " + text);
        }
        long pageId = Long.parseLong(text.substring(pageMarker + 6, slotMarker));
        int slotId = Integer.parseInt(text.substring(slotMarker + 6));
        return new RowLocationIdentity(true, pageId, slotId);
    }

    static void removeOrderedIndexForCompatibility(
            Connection connection,
            String tableName) throws Exception {
        long metadataContainerId = baseConglomerateId(connection, tableName);
        TransactionManager manager = transactionManager(connection);
        Transaction raw = manager.getRawStoreXact();
        TableLayout layout = tableLayout(raw, metadataContainerId);
        if (layout.orderedIndexContainerId() <= 0L) {
            throw new AssertionError("RawStore MVCC ordered-index container is absent");
        }
        for (OrderedIndexMapping mapping : orderedIndexMappings(raw, layout)) {
            manager.dropConglomerate(mapping.btreeConglomerate());
        }
        ContainerHandle container = raw.openContainer(
                new ContainerKey(0L, metadataContainerId),
                lockingPolicy(raw),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            page = container.getFirstPage();
            Object[] oldShape = new Object[CONTROL_FIXED_FIELDS + (layout.columnCount() * 2)];
            oldShape[0] = new SQLLongint(0L);
            oldShape[1] = new SQLInteger(0);
            oldShape[2] = new SQLInteger(0);
            oldShape[3] = new SQLLongint(0L);
            oldShape[4] = new SQLLongint(0L);
            oldShape[5] = new SQLInteger(0);
            oldShape[6] = new SQLInteger(0);
            for (int field = CONTROL_FIXED_FIELDS; field < oldShape.length; field++) {
                oldShape[field] = new SQLInteger(0);
            }
            page.fetchFromSlot(null, Page.FIRST_SLOT_NUMBER, oldShape, null, false);
            page.updateAtSlot(Page.FIRST_SLOT_NUMBER, oldShape, null);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
        raw.dropContainer(new ContainerKey(0L, layout.orderedIndexContainerId()));
    }

    static List<VersionIdentity> versions(Connection connection, String tableName) throws Exception {
        long metadataContainerId = baseConglomerateId(connection, tableName);
        Transaction raw = transactionManager(connection).getRawStoreXact();
        TableLayout layout = tableLayout(raw, metadataContainerId);
        ContainerHandle container = raw.openContainer(
                new ContainerKey(0L, layout.versionContainerId()),
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
                    int fieldCount = page.fetchNumFieldsAtSlot(slot);
                    int baseFieldCount = VERSION_PAYLOAD_START + layout.columnCount();
                    if (fieldCount != baseFieldCount
                            && fieldCount != baseFieldCount + VERSION_LOOKUP_HINT_FIELD_COUNT) {
                        throw new AssertionError(
                                "Unexpected RawStore MVCC version field count: " + fieldCount);
                    }
                    RecordHandle handle = page.getRecordHandleAtSlot(slot);
                    boolean hasHint = fieldCount > baseFieldCount;
                    result.add(new VersionIdentity(
                            longField(raw, page, slot, VERSION_ROW_ID_FIELD),
                            longField(raw, page, slot, VERSION_ID_FIELD),
                            longField(raw, page, slot, VERSION_CREATOR_TRANSACTION_ID_FIELD),
                            longField(raw, page, slot, VERSION_BEGIN_SEQUENCE_FIELD),
                            longField(raw, page, slot, VERSION_END_SEQUENCE_FIELD),
                            longField(raw, page, slot, VERSION_PREVIOUS_VERSION_ID_FIELD),
                            intField(raw, page, slot, VERSION_FLAGS_FIELD),
                            handle.getPageNumber(),
                            handle.getId(),
                            hasHint ? longField(raw, page, slot, baseFieldCount) : 0L,
                            hasHint ? intField(raw, page, slot, baseFieldCount + 1) : 0,
                            hasHint));
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

    static List<DirectoryIdentity> directories(Connection connection, String tableName) throws Exception {
        long metadataContainerId = baseConglomerateId(connection, tableName);
        Transaction raw = transactionManager(connection).getRawStoreXact();
        ContainerHandle container = raw.openContainer(
                new ContainerKey(0L, metadataContainerId),
                lockingPolicy(raw),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new AssertionError("RawStore MVCC metadata container is absent");
        }
        List<DirectoryIdentity> result = new ArrayList<>();
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 2
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)
                            || intField(raw, page, slot, DIRECTORY_KIND_FIELD) != DIRECTORY_KIND) {
                        continue;
                    }
                    int fieldCount = page.fetchNumFieldsAtSlot(slot);
                    if (fieldCount != DIRECTORY_BASE_FIELD_COUNT
                            && fieldCount != DIRECTORY_HINT_FIELD_COUNT
                            && fieldCount != DIRECTORY_HEAD_SUMMARY_FIELD_COUNT) {
                        throw new AssertionError(
                                "Unexpected RawStore MVCC directory field count: " + fieldCount);
                    }
                    boolean hasHint = fieldCount >= DIRECTORY_HINT_FIELD_COUNT;
                    result.add(new DirectoryIdentity(
                            longField(raw, page, slot, DIRECTORY_ROW_ID_FIELD),
                            longField(raw, page, slot, DIRECTORY_HEAD_VERSION_ID_FIELD),
                            hasHint
                                    ? longField(raw, page, slot, DIRECTORY_HEAD_HINT_PAGE_FIELD)
                                    : 0L,
                            hasHint
                                    ? intField(raw, page, slot, DIRECTORY_HEAD_HINT_RECORD_FIELD)
                                    : 0,
                            hasHint));
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
        result.sort(Comparator.comparingLong(DirectoryIdentity::rowId));
        return result;
    }

    static void invalidateLookupHints(Connection connection, String tableName, long rowId)
            throws Exception {
        long metadataContainerId = baseConglomerateId(connection, tableName);
        Transaction raw = transactionManager(connection).getRawStoreXact();
        TableLayout layout = tableLayout(raw, metadataContainerId);
        long invalidPage = Long.MAX_VALUE - rowId;
        int invalidRecord = Integer.MAX_VALUE;
        long headVersionId = invalidateDirectoryHint(
                raw,
                metadataContainerId,
                rowId,
                invalidPage,
                invalidRecord);
        invalidatePreviousHint(
                raw,
                layout,
                rowId,
                headVersionId,
                invalidPage,
                invalidRecord);
    }

    static void stripLookupHints(Connection connection, String tableName) throws Exception {
        long metadataContainerId = baseConglomerateId(connection, tableName);
        Transaction raw = transactionManager(connection).getRawStoreXact();
        TableLayout layout = tableLayout(raw, metadataContainerId);
        stripDirectoryHints(raw, metadataContainerId);
        stripVersionHints(raw, layout);
    }

    private static long invalidateDirectoryHint(
            Transaction raw,
            long metadataContainerId,
            long rowId,
            long invalidPage,
            int invalidRecord) throws Exception {
        ContainerHandle container = raw.openContainer(
                new ContainerKey(0L, metadataContainerId),
                lockingPolicy(raw),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 2
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)
                            || intField(raw, page, slot, DIRECTORY_KIND_FIELD) != DIRECTORY_KIND
                            || longField(raw, page, slot, DIRECTORY_ROW_ID_FIELD) != rowId) {
                        continue;
                    }
                    int fieldCount = page.fetchNumFieldsAtSlot(slot);
                    if (fieldCount != DIRECTORY_HINT_FIELD_COUNT
                            && fieldCount != DIRECTORY_HEAD_SUMMARY_FIELD_COUNT) {
                        throw new AssertionError("Directory lookup hint is absent");
                    }
                    long headVersionId = longField(
                            raw,
                            page,
                            slot,
                            DIRECTORY_HEAD_VERSION_ID_FIELD);
                    page.updateFieldAtSlot(
                            slot,
                            DIRECTORY_HEAD_HINT_PAGE_FIELD,
                            new SQLLongint(invalidPage),
                            null);
                    page.updateFieldAtSlot(
                            slot,
                            DIRECTORY_HEAD_HINT_RECORD_FIELD,
                            new SQLInteger(invalidRecord),
                            null);
                    return headVersionId;
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
        throw new AssertionError("No directory row for logical row " + rowId);
    }

    private static void invalidatePreviousHint(
            Transaction raw,
            TableLayout layout,
            long rowId,
            long versionId,
            long invalidPage,
            int invalidRecord) throws Exception {
        ContainerHandle container = raw.openContainer(
                new ContainerKey(0L, layout.versionContainerId()),
                lockingPolicy(raw),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)
                            || intField(raw, page, slot, VERSION_KIND_FIELD) != VERSION_KIND
                            || longField(raw, page, slot, VERSION_ROW_ID_FIELD) != rowId
                            || longField(raw, page, slot, VERSION_ID_FIELD) != versionId) {
                        continue;
                    }
                    int baseFieldCount = VERSION_PAYLOAD_START + layout.columnCount();
                    if (page.fetchNumFieldsAtSlot(slot)
                            != baseFieldCount + VERSION_LOOKUP_HINT_FIELD_COUNT) {
                        throw new AssertionError("Version predecessor lookup hint is absent");
                    }
                    page.updateFieldAtSlot(
                            slot,
                            baseFieldCount,
                            new SQLLongint(invalidPage),
                            null);
                    page.updateFieldAtSlot(
                            slot,
                            baseFieldCount + 1,
                            new SQLInteger(invalidRecord),
                            null);
                    return;
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
        throw new AssertionError("No head version " + versionId + " for logical row " + rowId);
    }

    private static void stripDirectoryHints(Transaction raw, long metadataContainerId)
            throws Exception {
        ContainerHandle container = raw.openContainer(
                new ContainerKey(0L, metadataContainerId),
                lockingPolicy(raw),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 2
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)
                            || intField(raw, page, slot, DIRECTORY_KIND_FIELD) != DIRECTORY_KIND
                            || page.fetchNumFieldsAtSlot(slot) == DIRECTORY_BASE_FIELD_COUNT) {
                        continue;
                    }
                    Object[] row = new Object[] {
                            new SQLInteger(0),
                            new SQLInteger(0),
                            new SQLLongint(0L),
                            new SQLLongint(0L),
                            new SQLLongint(0L),
                            new SQLInteger(0),
                            new SQLLongint(0L),
                            new SQLLongint(0L),
                            new SQLInteger(0)
                    };
                    page.fetchFromSlot(null, slot, row, null, false);
                    page.updateAtSlot(
                            slot,
                            Arrays.copyOf(row, DIRECTORY_BASE_FIELD_COUNT),
                            null);
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
    }

    private static void stripVersionHints(Transaction raw, TableLayout layout) throws Exception {
        ContainerHandle container = raw.openContainer(
                new ContainerKey(0L, layout.versionContainerId()),
                lockingPolicy(raw),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)
                            || intField(raw, page, slot, VERSION_KIND_FIELD) != VERSION_KIND) {
                        continue;
                    }
                    int baseFieldCount = VERSION_PAYLOAD_START + layout.columnCount();
                    if (page.fetchNumFieldsAtSlot(slot) == baseFieldCount) {
                        continue;
                    }
                    Object[] row = versionTemplate(raw, layout, true);
                    page.fetchFromSlot(null, slot, row, null, false);
                    page.updateAtSlot(slot, Arrays.copyOf(row, baseFieldCount), null);
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
    }

    private static Object[] versionTemplate(
            Transaction raw,
            TableLayout layout,
            boolean includeHint) throws Exception {
        int baseFieldCount = VERSION_PAYLOAD_START + layout.columnCount();
        Object[] row = new Object[includeHint
                ? baseFieldCount + VERSION_LOOKUP_HINT_FIELD_COUNT
                : baseFieldCount];
        row[VERSION_KIND_FIELD] = new SQLInteger(0);
        row[1] = new SQLInteger(0);
        for (int field = VERSION_ROW_ID_FIELD; field <= VERSION_PREVIOUS_VERSION_ID_FIELD; field++) {
            row[field] = new SQLLongint(0L);
        }
        row[VERSION_FLAGS_FIELD] = new SQLInteger(0);
        for (int index = 0; index < layout.columnCount(); index++) {
            row[VERSION_PAYLOAD_START + index] = raw.getDataValueFactory().getNull(
                    layout.formatIds()[index],
                    layout.collationIds()[index]);
        }
        if (includeHint) {
            row[baseFieldCount] = new SQLLongint(0L);
            row[baseFieldCount + 1] = new SQLInteger(0);
        }
        return row;
    }

    private static List<OrderedIndexMapping> orderedIndexMappings(
            Transaction raw,
            TableLayout layout) throws Exception {
        if (layout.orderedIndexContainerId() <= 0L) {
            return List.of();
        }
        ContainerHandle container = raw.openContainer(
                new ContainerKey(0L, layout.orderedIndexContainerId()),
                lockingPolicy(raw),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new AssertionError("RawStore MVCC ordered-index directory is absent");
        }
        List<OrderedIndexMapping> mappings = new ArrayList<>();
        Page page = null;
        try {
            page = container.getFirstPage();
            if (page == null
                    || intField(raw, page, Page.FIRST_SLOT_NUMBER,
                            ORDERED_INDEX_CONTROL_KIND_FIELD) != ORDERED_INDEX_CONTAINER_KIND) {
                throw new AssertionError("RawStore MVCC ordered-index control row is invalid");
            }
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)
                            || intField(raw, page, slot, ORDERED_INDEX_CONTROL_KIND_FIELD)
                                    != ORDERED_INDEX_BTREE_DESCRIPTOR_KIND) {
                        continue;
                    }
                    mappings.add(new OrderedIndexMapping(
                            intField(raw, page, slot,
                                    ORDERED_INDEX_DIRECTORY_COLUMN_ID_FIELD),
                            longField(raw, page, slot,
                                    ORDERED_INDEX_DIRECTORY_BTREE_CONGLOMERATE_FIELD)));
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
        mappings.sort(Comparator.comparingInt(OrderedIndexMapping::columnId));
        return List.copyOf(mappings);
    }

    private static long countContainerPages(Transaction raw, ContainerKey key) throws Exception {
        ContainerHandle container = raw.openContainer(
                key,
                lockingPolicy(raw),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new AssertionError("RawStore container is absent: " + key);
        }
        long count = 0L;
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                count++;
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
        return count;
    }

    private static StoreDataValue[] orderedIndexRowTemplate(
            Transaction raw,
            TableLayout layout,
            int columnId,
            StoreRowLocation rowLocation) throws Exception {
        StoreDataValue[] row = new StoreDataValue[ORDERED_INDEX_FIELD_COUNT];
        row[ORDERED_INDEX_KEY_FIELD] = raw.getDataValueFactory().getNull(
                layout.formatIds()[columnId],
                layout.collationIds()[columnId]);
        for (int field = ORDERED_INDEX_ROW_ID_FIELD;
                field <= ORDERED_INDEX_VERSION_ID_FIELD;
                field++) {
            row[field] = new SQLLongint(0L);
        }
        row[ORDERED_INDEX_ROW_LOCATION_FIELD] = rowLocation;
        return row;
    }

    private static TableLayout tableLayout(Transaction raw, long metadataContainerId)
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
            int slot = Page.FIRST_SLOT_NUMBER;
            long versionContainerId = longField(
                    raw,
                    page,
                    slot,
                    CONTROL_VERSION_CONTAINER_FIELD);
            int columnCount = intField(raw, page, slot, CONTROL_COLUMN_COUNT_FIELD);
            int[] formatIds = new int[columnCount];
            int[] collationIds = new int[columnCount];
            for (int index = 0; index < columnCount; index++) {
                formatIds[index] = intField(
                        raw,
                        page,
                        slot,
                        CONTROL_FIXED_FIELDS + index);
                collationIds[index] = intField(
                        raw,
                        page,
                        slot,
                        CONTROL_FIXED_FIELDS + columnCount + index);
            }
            int orderedIndexField = CONTROL_FIXED_FIELDS + (columnCount * 2);
            long orderedIndexContainerId = page.fetchNumFieldsAtSlot(slot) > orderedIndexField
                    ? longField(raw, page, slot, orderedIndexField)
                    : 0L;
            return new TableLayout(
                    metadataContainerId,
                    versionContainerId,
                    orderedIndexContainerId,
                    columnCount,
                    formatIds,
                    collationIds);
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

    record Counters(long nextTransactionId, long nextCommitSequence, long recoveryPublicationCeiling) {
    }

    record DirectoryIdentity(
            long rowId,
            long headVersionId,
            long headHintPage,
            int headHintRecord,
            boolean hasHint) {
    }

    record VersionIdentity(
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginCommitSequence,
            long endCommitSequence,
            long previousVersionId,
            int flags,
            long physicalPage,
            int physicalRecord,
            long previousHintPage,
            int previousHintRecord,
            boolean hasPreviousHintFields) {
        boolean tombstone() {
            return (flags & 1) != 0;
        }
    }

    record RowLocationIdentity(boolean hasLocator, long pageId, int slotId) {
    }

    record OrderedIndexProbeStats(
            long pagesVisited,
            long rowsVisited,
            long rowsQualified,
            long treeHeight,
            int nextCalls,
            int candidates) {
    }

    record OrderedIndexIdentity(
            int columnId,
            String key,
            long rowId,
            long versionId,
            boolean hasDirectoryLocator,
            long directoryPage,
            int directorySlot) {
    }

    record PhysicalLayout(
            long databaseMetadataContainerId,
            long tableMetadataContainerId,
            long versionContainerId,
            long orderedIndexDirectoryContainerId,
            List<Long> orderedIndexBtreeContainerIds) {
        PhysicalLayout {
            orderedIndexBtreeContainerIds = List.copyOf(orderedIndexBtreeContainerIds);
        }
    }

    record UniqueConstraintIdentity(
            int ordinal,
            int[] columns,
            boolean duplicateNullsAllowed) {
        UniqueConstraintIdentity {
            columns = columns.clone();
        }

        @Override
        public int[] columns() {
            return columns.clone();
        }
    }

    private record OrderedIndexMapping(int columnId, long btreeConglomerate) {
    }

    private record TableLayout(
            long metadataContainerId,
            long versionContainerId,
            long orderedIndexContainerId,
            int columnCount,
            int[] formatIds,
            int[] collationIds) {
        private TableLayout {
            formatIds = formatIds.clone();
            collationIds = collationIds.clone();
        }
    }
}
