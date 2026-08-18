/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.access.mvcc;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.access.ScanController;
import org.apache.derby.iapi.store.access.SpaceInfo;
import org.apache.derby.iapi.store.access.StaticCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.impl.jdbc.EmbedConnection;

/**
 * Test-only row-bearing B-tree mechanism for MVCC current-row maintenance.
 *
 * <p>The structure deliberately uses Derby's real persistent RawStore-backed B-tree so
 * insert/delete/scan/page-allocation work is paid by the mechanism. B2I requires every
 * leaf field to participate in the physical key and a RowLocation tail field, so this is
 * a conservative write/fanout proxy for a future Delos-native primary B+tree whose branch
 * separators would contain only search-key fields.</p>
 */
public final class MvccRowBearingBTreeMechanismTestSupport {
    private static final String BTREE_IMPLEMENTATION = "BTREE";
    private static final String PROPERTY_BASE_CONGLOMERATE_ID = "baseConglomerateId";
    private static final String PROPERTY_ROW_LOCATION_COLUMN = "rowLocationColumn";
    private static final String PROPERTY_ALLOW_DUPLICATES = "allowDuplicates";
    private static final String PROPERTY_KEY_FIELDS = "nKeyFields";
    private static final String PROPERTY_UNIQUE_COLUMNS = "nUniqueColumns";
    private static final String PROPERTY_PARENT_LINKS = "maintainParentLinks";

    private static final int KEY = 0;
    private static final int QUANTITY = 1;
    private static final int ROW_ID = 2;
    private static final int VERSION_ID = 3;
    private static final int CREATOR = 4;
    private static final int BEGIN = 5;
    private static final int VALID_TO = 6;
    private static final int FLAGS = 7;
    private static final int HINT_PAGE = 8;
    private static final int HINT_RECORD = 9;
    private static final int KIND = 10;
    private static final int ROW_LOCATION = 11;
    private static final int FIELD_COUNT = 12;

    private static final int KIND_ACTIVE = 0;
    private static final int KIND_RETIRED_KEY = 1;

    private MvccRowBearingBTreeMechanismTestSupport() {
    }

    public enum MaintenanceAlgorithm {
        REPLACE_CURRENT,
        APPEND_INTERVAL
    }

    public record Entry(
            int key,
            int quantity,
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            int flags,
            long hintPage,
            int hintRecord,
            MvccRowLocation rowLocation) {
        public Entry {
            if (rowId <= 0L || versionId <= 0L) {
                throw new IllegalArgumentException(
                        "row/version identity must be positive: row=" + rowId
                                + " version=" + versionId);
            }
            if (rowLocation == null || rowLocation.rowId() != rowId) {
                throw new IllegalArgumentException(
                        "row location does not identify row " + rowId);
            }
            rowLocation = (MvccRowLocation) rowLocation.cloneValue(false);
        }

        @Override
        public MvccRowLocation rowLocation() {
            return (MvccRowLocation) rowLocation.cloneValue(false);
        }

        public boolean tombstone() {
            return (flags & MvccRawStoreFormat.TOMBSTONE_FLAGS) != 0;
        }
    }

    public record Tree(long conglomerateId, MaintenanceAlgorithm algorithm) {
    }

    public record Measurement(
            long rows,
            long fingerprint,
            long localVisible,
            long historyFallbacks,
            long versionSlotFetches,
            long entriesVisited,
            long keyGroups) {
    }

    public record TreeSpace(
            long allocatedPages,
            long freePages,
            long unfilledPages,
            int pageSize) {
    }

    public record MutationCounts(long inserts, long deletes, long retiredAnchors) {
        public MutationCounts plus(MutationCounts other) {
            return new MutationCounts(
                    inserts + other.inserts,
                    deletes + other.deletes,
                    retiredAnchors + other.retiredAnchors);
        }
    }

    public static Session openSession(Connection connection, String tableName) throws Exception {
        return new Session(state(connection, tableName));
    }

    public static final class Session implements AutoCloseable {
        private final State state;

        private Session(State state) {
            this.state = state;
        }

        public long captureCommittedSequence() {
            return state.context().currentCommittedSequence();
        }

        public List<Entry> captureLiveEntries() throws Exception {
            return captureLiveEntries(Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        public List<Entry> captureLiveEntries(int start, int endExclusive) throws Exception {
            List<Entry> entries = new ArrayList<>();
            Transaction raw = state.raw();
            ContainerHandle directory = raw.openContainer(
                    state.table().metadataContainer(),
                    MvccRawStorePhysicalLocking.rowLevel(raw),
                    ContainerHandle.MODE_READONLY);
            if (directory == null) {
                throw new IllegalStateException("MVCC metadata container is absent");
            }
            FormatableBitSet all = allColumns(state.table());
            MvccRawStoreVersionRows.FetchProjection projection =
                    MvccRawStoreVersionRows.projection(state.table(), all);
            Page page = null;
            try (MvccRawStoreVersionReader reader = new MvccRawStoreVersionReader(
                    raw, state.table())) {
                page = directory.getFirstPage();
                while (page != null) {
                    int firstSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                            ? Page.FIRST_SLOT_NUMBER + 2
                            : Page.FIRST_SLOT_NUMBER;
                    for (int slot = firstSlot; slot < page.recordCount(); slot++) {
                        if (page.isDeletedAtSlot(slot)) {
                            continue;
                        }
                        MvccRawStoreTable.DirectoryRecord record =
                                MvccRawStoreTable.decodeDirectory(raw, page, slot);
                        if (record == null) {
                            continue;
                        }
                        MvccRawStoreTable.VersionRecord current = reader.find(
                                record.rowId(),
                                record.head().versionId(),
                                record.head().hint(),
                                projection);
                        if (current == null || current.tombstone()) {
                            continue;
                        }
                        int key = Math.toIntExact(StoreTypeUtil.getLong(current.values()[0]));
                        if (key < start || key >= endExclusive) {
                            continue;
                        }
                        entries.add(entry(record, current, key, quantity(current)));
                    }
                    long pageNumber = page.getPageNumber();
                    page.unlatch();
                    page = directory.getNextPage(pageNumber);
                }
            } finally {
                if (page != null) {
                    page.unlatch();
                }
                directory.close();
            }
            entries.sort(Comparator.comparingInt(Entry::key));
            return List.copyOf(entries);
        }

        public Map<Long, Entry> captureLiveEntriesByRowId(Set<Long> rowIds) throws Exception {
            Map<Long, Entry> result = new LinkedHashMap<>();
            if (rowIds.isEmpty()) {
                return Map.of();
            }
            for (Entry entry : captureLiveEntries()) {
                if (rowIds.contains(entry.rowId())) {
                    result.put(entry.rowId(), entry);
                }
            }
            if (result.size() != rowIds.size()) {
                throw new IllegalStateException(
                        "Expected " + rowIds.size() + " live rows, found " + result.size());
            }
            return Map.copyOf(result);
        }

        public Entry captureTombstone(Entry previous) throws Exception {
            Transaction raw = state.raw();
            MvccRawStoreTable.DirectoryRecord directory = MvccRawStoreRowDirectory.find(
                    raw, state.table(), previous.rowLocation());
            if (directory == null) {
                throw new IllegalStateException(
                        "MVCC directory row disappeared for logical row " + previous.rowId());
            }
            FormatableBitSet all = allColumns(state.table());
            MvccRawStoreVersionRows.FetchProjection projection =
                    MvccRawStoreVersionRows.projection(state.table(), all);
            try (MvccRawStoreVersionReader reader = new MvccRawStoreVersionReader(
                    raw, state.table())) {
                MvccRawStoreTable.VersionRecord current = reader.find(
                        directory.rowId(),
                        directory.head().versionId(),
                        directory.head().hint(),
                        projection);
                if (current == null || !current.tombstone()) {
                    throw new IllegalStateException(
                            "Expected tombstone head for logical row " + previous.rowId());
                }
                return entry(directory, current, previous.key(), 0);
            }
        }

        public Tree createTree(
                MaintenanceAlgorithm algorithm,
                List<Entry> initialEntries) throws Exception {
            Transaction raw = state.raw();
            StoreDataValue[] template = rowTemplate(raw);
            Properties properties = new Properties();
            properties.setProperty(
                    PROPERTY_BASE_CONGLOMERATE_ID,
                    Long.toString(state.table().accessConglomerateId()));
            properties.setProperty(PROPERTY_ROW_LOCATION_COLUMN, Integer.toString(ROW_LOCATION));
            properties.setProperty(PROPERTY_ALLOW_DUPLICATES, "false");
            properties.setProperty(PROPERTY_KEY_FIELDS, Integer.toString(FIELD_COUNT));
            properties.setProperty(PROPERTY_UNIQUE_COLUMNS, Integer.toString(FIELD_COUNT));
            properties.setProperty(PROPERTY_PARENT_LINKS, "true");
            int[] collationIds = new int[FIELD_COUNT];
            collationIds[KEY] = state.table().collationId(0);
            collationIds[QUANTITY] = state.table().collationId(1);
            long conglomerateId = state.transactionManager().createConglomerate(
                    BTREE_IMPLEMENTATION,
                    template,
                    null,
                    collationIds,
                    properties,
                    TransactionController.IS_DEFAULT);
            Tree tree = new Tree(conglomerateId, algorithm);
            try {
                for (Entry entry : initialEntries) {
                    insertPhysical(tree, activePhysical(entry));
                }
            } catch (Exception failure) {
                safeDrop(tree, failure);
                throw failure;
            } catch (Error failure) {
                safeDrop(tree, failure);
                throw failure;
            }
            return tree;
        }

        public MutationCounts insert(Tree tree, Entry entry) throws Exception {
            insertPhysical(tree, activePhysical(entry));
            return new MutationCounts(1L, 0L, 0L);
        }

        public MutationCounts updatePayload(Tree tree, Entry before, Entry after) throws Exception {
            requireSameRow(before, after);
            requireSameKey(before, after);
            if (tree.algorithm() == MaintenanceAlgorithm.APPEND_INTERVAL) {
                insertPhysical(tree, activePhysical(after));
                return new MutationCounts(1L, 0L, 0L);
            }
            deleteActive(tree, before.key(), before.rowId(), before.versionId());
            insertPhysical(tree, activePhysical(after));
            return new MutationCounts(1L, 1L, 0L);
        }

        public MutationCounts updateKey(
                Tree tree,
                Entry before,
                Entry after,
                long retirementSequence) throws Exception {
            requireSameRow(before, after);
            if (before.key() == after.key()) {
                throw new IllegalArgumentException("key-update mechanism received an unchanged key");
            }
            if (tree.algorithm() == MaintenanceAlgorithm.APPEND_INTERVAL) {
                insertPhysical(tree, retiredPhysical(before, retirementSequence));
                insertPhysical(tree, activePhysical(after));
                return new MutationCounts(2L, 0L, 1L);
            }
            deleteActive(tree, before.key(), before.rowId(), before.versionId());
            insertPhysical(tree, retiredPhysical(before, retirementSequence));
            insertPhysical(tree, activePhysical(after));
            return new MutationCounts(2L, 1L, 1L);
        }

        public MutationCounts delete(Tree tree, Entry before, Entry tombstone) throws Exception {
            requireSameRow(before, tombstone);
            requireSameKey(before, tombstone);
            if (!tombstone.tombstone()) {
                throw new IllegalArgumentException("delete mechanism requires a tombstone entry");
            }
            if (tree.algorithm() == MaintenanceAlgorithm.APPEND_INTERVAL) {
                insertPhysical(tree, activePhysical(tombstone));
                return new MutationCounts(1L, 0L, 0L);
            }
            deleteActive(tree, before.key(), before.rowId(), before.versionId());
            insertPhysical(tree, activePhysical(tombstone));
            return new MutationCounts(1L, 1L, 0L);
        }

        public Measurement measure(
                Tree tree,
                int start,
                int endExclusive,
                long snapshotSequence,
                boolean includeQuantity) throws Exception {
            return tree.algorithm() == MaintenanceAlgorithm.APPEND_INTERVAL
                    ? measureAppend(tree, start, endExclusive, snapshotSequence, includeQuantity)
                    : measureReplace(tree, start, endExclusive, snapshotSequence, includeQuantity);
        }

        public TreeSpace space(Tree tree) throws Exception {
            ConglomerateController controller = state.transactionManager().openConglomerate(
                    tree.conglomerateId(),
                    false,
                    0,
                    TransactionController.MODE_RECORD,
                    TransactionController.ISOLATION_READ_UNCOMMITTED);
            try {
                SpaceInfo info = controller.getSpaceInfo();
                return new TreeSpace(
                        info.getNumAllocatedPages(),
                        info.getNumFreePages(),
                        info.getNumUnfilledPages(),
                        info.getPageSize());
            } finally {
                controller.close();
            }
        }

        public void dropTree(Tree tree) throws Exception {
            state.transactionManager().dropConglomerate(tree.conglomerateId());
        }

        @Override
        public void close() {
            // The owning JDBC transaction controls RawStore lifecycle.
        }

        private Measurement measureReplace(
                Tree tree,
                int start,
                int endExclusive,
                long snapshotSequence,
                boolean includeQuantity) throws Exception {
            MvccRawStoreIndexedReadMetrics versionMetrics = new MvccRawStoreIndexedReadMetrics();
            long rows = 0L;
            long fingerprint = 0xcbf29ce484222325L;
            long localVisible = 0L;
            long historyFallbacks = 0L;
            long entriesVisited = 0L;
            long keyGroups = 0L;
            int lastKey = Integer.MIN_VALUE;
            boolean haveKey = false;
            ScanController scan = openRangeScan(tree, start, endExclusive);
            try (MvccRawStoreVersionReader versionReader = new MvccRawStoreVersionReader(
                    state.raw(), state.table(), versionMetrics)) {
                StoreDataValue[] physical = rowTemplate(state.raw());
                FormatableBitSet columns = columns(state.table(), includeQuantity);
                MvccRawStoreVersionRows.FetchProjection projection =
                        MvccRawStoreVersionRows.projection(state.table(), columns);
                while (scan.fetchNext(physical)) {
                    entriesVisited++;
                    int key = intAt(physical, KEY);
                    if (!haveKey || key != lastKey) {
                        keyGroups++;
                        lastKey = key;
                        haveKey = true;
                    }
                    Decoded decoded = decode(physical);
                    if (decoded.kind() == KIND_RETIRED_KEY) {
                        if (snapshotSequence < decoded.beginSequence()
                                || snapshotSequence >= decoded.validTo()) {
                            continue;
                        }
                        historyFallbacks++;
                        MvccRawStoreTable.VersionRecord visible = versionReader.find(
                                decoded.rowId(),
                                decoded.versionId(),
                                decoded.hint(),
                                projection);
                        if (visible == null || visible.tombstone()
                                || keyOf(visible) != decoded.key()) {
                            continue;
                        }
                        int quantity = includeQuantity ? quantity(visible) : 0;
                        fingerprint = fingerprint(
                                fingerprint, decoded.key(), quantity, includeQuantity);
                        rows++;
                        continue;
                    }
                    MvccRawStoreTable.DirectoryHeadSummary summary =
                            new MvccRawStoreTable.DirectoryHeadSummary(
                                    true,
                                    decoded.creatorTransactionId(),
                                    decoded.beginSequence(),
                                    decoded.flags());
                    if (summary.visibleTo(
                            state.context().transactionId(), snapshotSequence)) {
                        localVisible++;
                        if (summary.tombstone()) {
                            continue;
                        }
                        int quantity = includeQuantity ? decoded.quantity() : 0;
                        fingerprint = fingerprint(
                                fingerprint, decoded.key(), quantity, includeQuantity);
                        rows++;
                        continue;
                    }
                    historyFallbacks++;
                    MvccRawStoreTable.DirectoryHead head =
                            new MvccRawStoreTable.DirectoryHead(
                                    decoded.versionId(), decoded.hint(), summary);
                    MvccRawStoreTable.VersionRecord visible = versionReader.findVisible(
                            decoded.rowId(),
                            head,
                            state.context().transactionId(),
                            snapshotSequence,
                            projection);
                    if (visible == null || visible.tombstone()
                            || keyOf(visible) != decoded.key()) {
                        continue;
                    }
                    int quantity = includeQuantity ? quantity(visible) : 0;
                    fingerprint = fingerprint(
                            fingerprint, decoded.key(), quantity, includeQuantity);
                    rows++;
                }
            } finally {
                scan.close();
            }
            MvccRawStoreIndexedReadMetrics.Snapshot metrics = versionMetrics.snapshot();
            return new Measurement(
                    rows,
                    fingerprint,
                    localVisible,
                    historyFallbacks,
                    metrics.versionSlotFetches(),
                    entriesVisited,
                    keyGroups);
        }

        private Measurement measureAppend(
                Tree tree,
                int start,
                int endExclusive,
                long snapshotSequence,
                boolean includeQuantity) throws Exception {
            MvccRawStoreIndexedReadMetrics versionMetrics = new MvccRawStoreIndexedReadMetrics();
            long rows = 0L;
            long fingerprint = 0xcbf29ce484222325L;
            long localVisible = 0L;
            long historyFallbacks = 0L;
            long entriesVisited = 0L;
            long keyGroups = 0L;
            ScanController scan = openRangeScan(tree, start, endExclusive);
            try (MvccRawStoreVersionReader versionReader = new MvccRawStoreVersionReader(
                    state.raw(), state.table(), versionMetrics)) {
                StoreDataValue[] physical = rowTemplate(state.raw());
                FormatableBitSet columns = columns(state.table(), includeQuantity);
                MvccRawStoreVersionRows.FetchProjection projection =
                        MvccRawStoreVersionRows.projection(state.table(), columns);
                List<Decoded> group = new ArrayList<>();
                int groupKey = 0;
                boolean haveGroup = false;
                while (scan.fetchNext(physical)) {
                    entriesVisited++;
                    Decoded decoded = decode(physical);
                    if (!haveGroup) {
                        groupKey = decoded.key();
                        haveGroup = true;
                    } else if (decoded.key() != groupKey) {
                        GroupResult groupResult = resolveAppendGroup(
                                group,
                                snapshotSequence,
                                includeQuantity,
                                versionReader,
                                projection);
                        rows += groupResult.rows();
                        if (groupResult.emitted()) {
                            fingerprint = fingerprint(
                                    fingerprint,
                                    groupResult.key(),
                                    groupResult.quantity(),
                                    includeQuantity);
                        }
                        localVisible += groupResult.localVisible();
                        historyFallbacks += groupResult.historyFallbacks();
                        keyGroups++;
                        group.clear();
                        groupKey = decoded.key();
                    }
                    group.add(decoded);
                }
                if (haveGroup) {
                    GroupResult groupResult = resolveAppendGroup(
                            group,
                            snapshotSequence,
                            includeQuantity,
                            versionReader,
                            projection);
                    rows += groupResult.rows();
                    if (groupResult.emitted()) {
                        fingerprint = fingerprint(
                                fingerprint,
                                groupResult.key(),
                                groupResult.quantity(),
                                includeQuantity);
                    }
                    localVisible += groupResult.localVisible();
                    historyFallbacks += groupResult.historyFallbacks();
                    keyGroups++;
                }
            } finally {
                scan.close();
            }
            MvccRawStoreIndexedReadMetrics.Snapshot metrics = versionMetrics.snapshot();
            return new Measurement(
                    rows,
                    fingerprint,
                    localVisible,
                    historyFallbacks,
                    metrics.versionSlotFetches(),
                    entriesVisited,
                    keyGroups);
        }

        private GroupResult resolveAppendGroup(
                List<Decoded> group,
                long snapshotSequence,
                boolean includeQuantity,
                MvccRawStoreVersionReader versionReader,
                MvccRawStoreVersionRows.FetchProjection projection) throws Exception {
            Map<Long, List<Decoded>> byRow = new LinkedHashMap<>();
            for (Decoded decoded : group) {
                byRow.computeIfAbsent(decoded.rowId(), ignored -> new ArrayList<>()).add(decoded);
            }
            long rows = 0L;
            boolean emitted = false;
            int emittedKey = 0;
            int emittedQuantity = 0;
            long localVisible = 0L;
            long historyFallbacks = 0L;
            for (List<Decoded> versions : byRow.values()) {
                Decoded retired = latestRetired(versions);
                if (retired != null) {
                    if (snapshotSequence >= retired.validTo()) {
                        continue;
                    }
                    if (snapshotSequence >= retired.beginSequence()) {
                        historyFallbacks++;
                        MvccRawStoreTable.VersionRecord visible = versionReader.find(
                                retired.rowId(),
                                retired.versionId(),
                                retired.hint(),
                                projection);
                        if (visible != null && !visible.tombstone()
                                && keyOf(visible) == retired.key()) {
                            int quantity = includeQuantity ? quantity(visible) : 0;
                            if (emitted) {
                                throw duplicateVisibleKey(retired.key(), snapshotSequence);
                            }
                            emitted = true;
                            emittedKey = retired.key();
                            emittedQuantity = quantity;
                            rows++;
                        }
                        continue;
                    }
                }

                Decoded chosen = newestVisiblePhysical(versions, snapshotSequence);
                if (chosen != null) {
                    localVisible++;
                    if (!chosen.tombstone()) {
                        int quantity = includeQuantity ? chosen.quantity() : 0;
                        if (emitted) {
                            throw duplicateVisibleKey(chosen.key(), snapshotSequence);
                        }
                        emitted = true;
                        emittedKey = chosen.key();
                        emittedQuantity = quantity;
                        rows++;
                    }
                    continue;
                }

                Decoded newest = newestActive(versions);
                if (newest == null) {
                    continue;
                }
                historyFallbacks++;
                MvccRawStoreTable.DirectoryHeadSummary summary =
                        new MvccRawStoreTable.DirectoryHeadSummary(
                                true,
                                newest.creatorTransactionId(),
                                newest.beginSequence(),
                                newest.flags());
                MvccRawStoreTable.DirectoryHead head = new MvccRawStoreTable.DirectoryHead(
                        newest.versionId(), newest.hint(), summary);
                MvccRawStoreTable.VersionRecord visible = versionReader.findVisible(
                        newest.rowId(),
                        head,
                        state.context().transactionId(),
                        snapshotSequence,
                        projection);
                if (visible != null && !visible.tombstone()
                        && keyOf(visible) == newest.key()) {
                    int quantity = includeQuantity ? quantity(visible) : 0;
                    if (emitted) {
                        throw duplicateVisibleKey(newest.key(), snapshotSequence);
                    }
                    emitted = true;
                    emittedKey = newest.key();
                    emittedQuantity = quantity;
                    rows++;
                }
            }
            return new GroupResult(
                    rows,
                    emitted,
                    emittedKey,
                    emittedQuantity,
                    localVisible,
                    historyFallbacks);
        }

        private static IllegalStateException duplicateVisibleKey(
                int key, long snapshotSequence) {
            return new IllegalStateException(
                    "row-bearing append tree produced multiple visible rows for key "
                            + key + " at snapshot " + snapshotSequence);
        }

        private Decoded newestVisiblePhysical(List<Decoded> versions, long snapshotSequence) {
            Decoded chosen = null;
            for (Decoded candidate : versions) {
                if (candidate.kind() != KIND_ACTIVE) {
                    continue;
                }
                MvccRawStoreTable.DirectoryHeadSummary summary =
                        new MvccRawStoreTable.DirectoryHeadSummary(
                                true,
                                candidate.creatorTransactionId(),
                                candidate.beginSequence(),
                                candidate.flags());
                if (!summary.visibleTo(state.context().transactionId(), snapshotSequence)) {
                    continue;
                }
                if (chosen == null || candidate.beginSequence() > chosen.beginSequence()) {
                    chosen = candidate;
                }
            }
            return chosen;
        }

        private static Decoded newestActive(List<Decoded> versions) {
            Decoded newest = null;
            for (Decoded candidate : versions) {
                if (candidate.kind() != KIND_ACTIVE) {
                    continue;
                }
                if (newest == null || candidate.beginSequence() > newest.beginSequence()) {
                    newest = candidate;
                }
            }
            return newest;
        }

        private static Decoded latestRetired(List<Decoded> versions) {
            Decoded latest = null;
            for (Decoded candidate : versions) {
                if (candidate.kind() != KIND_RETIRED_KEY) {
                    continue;
                }
                if (latest == null || candidate.validTo() > latest.validTo()) {
                    latest = candidate;
                }
            }
            return latest;
        }

        private ScanController openRangeScan(Tree tree, int start, int endExclusive)
                throws Exception {
            StoreDataValue[] startKey = new StoreDataValue[] {
                    MvccRawStoreFormat.intValue(state.raw(), start)
            };
            StoreDataValue[] stopKey = new StoreDataValue[] {
                    MvccRawStoreFormat.intValue(state.raw(), endExclusive)
            };
            return state.transactionManager().openScan(
                    tree.conglomerateId(),
                    false,
                    0,
                    TransactionController.MODE_RECORD,
                    TransactionController.ISOLATION_READ_UNCOMMITTED,
                    null,
                    startKey,
                    ScanController.GE,
                    null,
                    stopKey,
                    ScanController.GE);
        }

        private void insertPhysical(Tree tree, StoreDataValue[] row) throws Exception {
            ConglomerateController controller = state.transactionManager().openConglomerate(
                    tree.conglomerateId(),
                    false,
                    TransactionController.OPENMODE_FORUPDATE
                            | TransactionController.OPENMODE_BASEROW_INSERT_LOCKED,
                    TransactionController.MODE_RECORD,
                    TransactionController.ISOLATION_REPEATABLE_READ);
            try {
                int status = controller.insert(row);
                if (status != 0) {
                    throw new IllegalStateException(
                            "row-bearing B-tree rejected a mechanism entry: " + status);
                }
            } finally {
                controller.close();
            }
        }

        private void deleteActive(Tree tree, int key, long rowId, long versionId)
                throws Exception {
            StoreDataValue[] startKey = new StoreDataValue[] {
                    MvccRawStoreFormat.intValue(state.raw(), key)
            };
            StoreDataValue[] stopKey = new StoreDataValue[] {
                    MvccRawStoreFormat.intValue(state.raw(), key)
            };
            ScanController scan = state.transactionManager().openScan(
                    tree.conglomerateId(),
                    false,
                    TransactionController.OPENMODE_FORUPDATE,
                    TransactionController.MODE_RECORD,
                    TransactionController.ISOLATION_REPEATABLE_READ,
                    null,
                    startKey,
                    ScanController.GE,
                    null,
                    stopKey,
                    ScanController.GT);
            try {
                StoreDataValue[] physical = rowTemplate(state.raw());
                while (scan.fetchNext(physical)) {
                    Decoded decoded = decode(physical);
                    if (decoded.kind() == KIND_ACTIVE
                            && decoded.rowId() == rowId
                            && decoded.versionId() == versionId) {
                        if (!scan.delete()) {
                            throw new IllegalStateException(
                                    "row-bearing B-tree active entry could not be deleted");
                        }
                        return;
                    }
                }
            } finally {
                scan.close();
            }
            throw new IllegalStateException(
                    "row-bearing B-tree active entry is absent: key=" + key
                            + " row=" + rowId + " version=" + versionId);
        }

        private StoreDataValue[] activePhysical(Entry entry) throws Exception {
            return physical(entry, MvccRawStoreFormat.CURRENT_END_SEQUENCE, KIND_ACTIVE);
        }

        private StoreDataValue[] retiredPhysical(Entry entry, long retirementSequence)
                throws Exception {
            if (retirementSequence <= entry.beginSequence()) {
                throw new IllegalArgumentException(
                        "retirement sequence must follow entry begin sequence");
            }
            return physical(entry, retirementSequence, KIND_RETIRED_KEY);
        }

        private StoreDataValue[] physical(Entry entry, long validTo, int kind) throws Exception {
            StoreDataValue[] row = rowTemplate(state.raw());
            StoreTypeUtil.setIntValue(row[KEY], entry.key());
            StoreTypeUtil.setIntValue(row[QUANTITY], entry.quantity());
            StoreTypeUtil.setLongValue(row[ROW_ID], entry.rowId());
            StoreTypeUtil.setLongValue(row[VERSION_ID], entry.versionId());
            StoreTypeUtil.setLongValue(row[CREATOR], entry.creatorTransactionId());
            StoreTypeUtil.setLongValue(row[BEGIN], entry.beginSequence());
            StoreTypeUtil.setLongValue(row[VALID_TO], validTo);
            StoreTypeUtil.setIntValue(row[FLAGS], entry.flags());
            StoreTypeUtil.setLongValue(row[HINT_PAGE], entry.hintPage());
            StoreTypeUtil.setIntValue(row[HINT_RECORD], entry.hintRecord());
            StoreTypeUtil.setIntValue(row[KIND], kind);
            row[ROW_LOCATION] = entry.rowLocation();
            return row;
        }

        private StoreDataValue[] rowTemplate(Transaction raw) throws Exception {
            StoreDataValue[] row = new StoreDataValue[FIELD_COUNT];
            row[KEY] = MvccRawStoreFormat.nullValue(
                    raw, state.table().formatId(0), state.table().collationId(0));
            row[QUANTITY] = MvccRawStoreFormat.nullValue(
                    raw, state.table().formatId(1), state.table().collationId(1));
            row[ROW_ID] = MvccRawStoreFormat.longValue(raw, 0L);
            row[VERSION_ID] = MvccRawStoreFormat.longValue(raw, 0L);
            row[CREATOR] = MvccRawStoreFormat.longValue(raw, 0L);
            row[BEGIN] = MvccRawStoreFormat.longValue(raw, 0L);
            row[VALID_TO] = MvccRawStoreFormat.longValue(raw, 0L);
            row[FLAGS] = MvccRawStoreFormat.intValue(raw, 0);
            row[HINT_PAGE] = MvccRawStoreFormat.longValue(raw, 0L);
            row[HINT_RECORD] = MvccRawStoreFormat.intValue(raw, 0);
            row[KIND] = MvccRawStoreFormat.intValue(raw, 0);
            row[ROW_LOCATION] = new MvccRowLocation();
            return row;
        }

        private Decoded decode(StoreDataValue[] row) throws Exception {
            return new Decoded(
                    intAt(row, KEY),
                    intAt(row, QUANTITY),
                    longAt(row, ROW_ID),
                    longAt(row, VERSION_ID),
                    longAt(row, CREATOR),
                    longAt(row, BEGIN),
                    longAt(row, VALID_TO),
                    intAt(row, FLAGS),
                    longAt(row, HINT_PAGE),
                    intAt(row, HINT_RECORD),
                    intAt(row, KIND));
        }

        private void safeDrop(Tree tree, Throwable failure) {
            try {
                state.transactionManager().dropConglomerate(tree.conglomerateId());
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static Entry entry(
            MvccRawStoreTable.DirectoryRecord directory,
            MvccRawStoreTable.VersionRecord current,
            int key,
            int quantity) {
        MvccRawStoreTable.RecordHint hint = directory.head().hint();
        return new Entry(
                key,
                quantity,
                directory.rowId(),
                current.versionId(),
                current.creatorTransactionId(),
                current.beginSequence(),
                current.flags(),
                hint.pageNumber(),
                hint.recordId(),
                MvccRawStoreRowDirectory.location(directory.rowId(), directory.handle()));
    }

    private static FormatableBitSet allColumns(MvccRawStoreTable.Descriptor table) {
        FormatableBitSet all = new FormatableBitSet(table.columnCount());
        for (int column = 0; column < table.columnCount(); column++) {
            all.set(column);
        }
        return all;
    }

    private static FormatableBitSet columns(
            MvccRawStoreTable.Descriptor table,
            boolean includeQuantity) {
        FormatableBitSet columns = new FormatableBitSet(table.columnCount());
        columns.set(0);
        if (includeQuantity) {
            columns.set(1);
        }
        return columns;
    }

    private static int keyOf(MvccRawStoreTable.VersionRecord version) throws Exception {
        return Math.toIntExact(StoreTypeUtil.getLong(version.values()[0]));
    }

    private static int quantity(MvccRawStoreTable.VersionRecord version) throws Exception {
        return Math.toIntExact(StoreTypeUtil.getLong(version.values()[1]));
    }

    private static long fingerprint(
            long fingerprint,
            int key,
            int quantity,
            boolean includeQuantity) {
        long value = (((long) key) << 32)
                ^ (includeQuantity ? quantity & 0xffffffffL : 0L);
        fingerprint ^= value;
        fingerprint *= 0x100000001b3L;
        return fingerprint;
    }

    private static void requireSameRow(Entry before, Entry after) {
        if (before.rowId() != after.rowId()) {
            throw new IllegalArgumentException(
                    "mutation changed logical row identity: " + before.rowId()
                            + " -> " + after.rowId());
        }
    }

    private static void requireSameKey(Entry before, Entry after) {
        if (before.key() != after.key()) {
            throw new IllegalArgumentException(
                    "mutation changed key unexpectedly: " + before.key()
                            + " -> " + after.key());
        }
    }

    private static int intAt(StoreDataValue[] row, int field) throws Exception {
        return Math.toIntExact(StoreTypeUtil.getLong(row[field]));
    }

    private static long longAt(StoreDataValue[] row, int field) throws Exception {
        return StoreTypeUtil.getLong(row[field]);
    }

    private static State state(Connection connection, String tableName) throws Exception {
        TransactionManager transactionManager = transactionManager(connection);
        long baseId = baseConglomerateId(connection, tableName);
        StaticCompiledOpenConglomInfo staticInfo =
                transactionManager.getStaticCompiledConglomInfo(baseId);
        if (!(staticInfo instanceof MvccConglomerate conglomerate)) {
            throw new IllegalStateException(
                    "Expected delos_mvcc conglomerate for " + tableName + ", found " + staticInfo);
        }
        MvccConglomerate attached = (MvccConglomerate)
                transactionManager.findExistingConglomerateFromKey(conglomerate.getId());
        MvccRawStoreRuntime runtime = (MvccRawStoreRuntime) field(attached, "runtime");
        MvccRawStoreTable.Descriptor table =
                (MvccRawStoreTable.Descriptor) field(attached, "table");
        if (runtime == null || table == null) {
            throw new IllegalStateException("delos_mvcc conglomerate did not attach to runtime");
        }
        Transaction raw = transactionManager.getRawStoreXact();
        return new State(
                transactionManager,
                raw,
                runtime,
                table,
                runtime.context(transactionManager, raw));
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static TransactionManager transactionManager(Connection connection) {
        if (!(connection instanceof EmbedConnection embedded)) {
            throw new IllegalArgumentException("Embedded Delos connection required");
        }
        LanguageConnectionContext lcc = embedded.getLanguageConnection();
        TransactionController controller = lcc.getTransactionExecute();
        if (!(controller instanceof TransactionManager transactionManager)) {
            throw new IllegalStateException("Derby TransactionManager required");
        }
        return transactionManager;
    }

    private static long baseConglomerateId(Connection connection, String tableName) throws Exception {
        String sql = "select c.conglomeratenumber "
                + "from sys.sysconglomerates c, sys.systables t, sys.sysschemas s "
                + "where c.tableid=t.tableid and t.schemaid=s.schemaid "
                + "and c.isindex=false and s.schemaname='APP' and t.tablename='"
                + tableName.toUpperCase(java.util.Locale.ROOT) + "'";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Base conglomerate is absent for " + tableName);
            }
            return resultSet.getLong(1);
        }
    }

    private record State(
            TransactionManager transactionManager,
            Transaction raw,
            MvccRawStoreRuntime runtime,
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreTransactionContext context) {
    }

    private record Decoded(
            int key,
            int quantity,
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            long validTo,
            int flags,
            long hintPage,
            int hintRecord,
            int kind) {
        MvccRawStoreTable.RecordHint hint() {
            return new MvccRawStoreTable.RecordHint(hintPage, hintRecord);
        }

        boolean tombstone() {
            return (flags & MvccRawStoreFormat.TOMBSTONE_FLAGS) != 0;
        }
    }

    private record GroupResult(
            long rows,
            boolean emitted,
            int key,
            int quantity,
            long localVisible,
            long historyFallbacks) {
    }
}
