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
import java.util.List;
import java.util.Properties;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.access.StaticCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreOrderable;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Test-only below-SQL mechanism bridge for alternative MVCC current-row topologies.
 *
 * <p>The prototype containers model the ordered leaf stream after a key seek. They are
 * ordinary RawStore page containers, not Java-side row arrays. Historical fallback always
 * uses the production {@link MvccRawStoreVersionReader} and therefore retains the real
 * version-chain encoding and visibility rules.</p>
 */
public final class MvccCurrentRowTopologyTestSupport {
    private static final int OVERFLOW_THRESHOLD = 100;

    private static final int A_KEY = 0;
    private static final int A_ROW_ID = 1;
    private static final int A_VERSION_ID = 2;
    private static final int A_CREATOR = 3;
    private static final int A_BEGIN = 4;
    private static final int A_FLAGS = 5;
    private static final int A_HINT_PAGE = 6;
    private static final int A_HINT_RECORD = 7;
    private static final int A_FIELDS = 8;

    private static final int R_KEY = 0;
    private static final int R_QUANTITY = 1;
    private static final int R_ROW_ID = 2;
    private static final int R_VERSION_ID = 3;
    private static final int R_CREATOR = 4;
    private static final int R_BEGIN = 5;
    private static final int R_FLAGS = 6;
    private static final int R_HINT_PAGE = 7;
    private static final int R_HINT_RECORD = 8;
    private static final int R_FIELDS = 9;

    private MvccCurrentRowTopologyTestSupport() {
    }

    public enum Algorithm {
        EXISTING,
        CURRENT_ROW_ANCHOR,
        ROW_BEARING_INTERVAL
    }

    public record Prototype(long anchorContainerId, long rowBearingContainerId, int rows) {
    }

    public record Measurement(
            long rows,
            long fingerprint,
            long localVisible,
            long historyFallbacks,
            long versionSlotFetches,
            long directoryPageAcquisitions,
            long candidateCount,
            long anchorHits) {
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

        public Prototype buildPrototype(int start, int endExclusive) throws Exception {
            List<CurrentRow> rows = currentRows(state, start, endExclusive);
            if (rows.size() != endExclusive - start) {
                throw new IllegalStateException(
                        "MVCC topology prototype expected " + (endExclusive - start)
                                + " rows but found " + rows.size());
            }
            rows.sort(Comparator.comparingInt(CurrentRow::key));
            Transaction raw = state.raw();
            ContainerKey anchor = createContainer(raw);
            ContainerKey rowBearing = createContainer(raw);
            try {
                for (CurrentRow row : rows) {
                    insert(raw, anchor, anchorRow(raw, row));
                    insert(raw, rowBearing, rowBearingRow(raw, row));
                }
            } catch (Exception failure) {
                safeDrop(raw, anchor, failure);
                safeDrop(raw, rowBearing, failure);
                throw failure;
            } catch (Error failure) {
                safeDrop(raw, anchor, failure);
                safeDrop(raw, rowBearing, failure);
                throw failure;
            }
            return new Prototype(anchor.getContainerId(), rowBearing.getContainerId(), rows.size());
        }

        public Measurement measure(
                Prototype prototype,
                Algorithm algorithm,
                int start,
                int endExclusive,
                long snapshotSequence,
                boolean includeQuantity) throws Exception {
            return switch (algorithm) {
                case EXISTING -> measureExisting(
                        state, start, endExclusive, snapshotSequence, includeQuantity);
                case CURRENT_ROW_ANCHOR -> measurePrototype(
                        state,
                        new ContainerKey(
                                ContainerHandle.TEMPORARY_SEGMENT,
                                prototype.anchorContainerId()),
                        false, snapshotSequence, includeQuantity);
                case ROW_BEARING_INTERVAL -> measurePrototype(
                        state,
                        new ContainerKey(
                                ContainerHandle.TEMPORARY_SEGMENT,
                                prototype.rowBearingContainerId()),
                        true, snapshotSequence, includeQuantity);
            };
        }

        public void drop(Prototype prototype) throws Exception {
            state.raw().dropContainer(new ContainerKey(
                    ContainerHandle.TEMPORARY_SEGMENT, prototype.anchorContainerId()));
            state.raw().dropContainer(new ContainerKey(
                    ContainerHandle.TEMPORARY_SEGMENT, prototype.rowBearingContainerId()));
        }

        @Override
        public void close() {
            // The owning JDBC transaction owns RawStore lifecycle.
        }
    }

    public static long captureCommittedSequence(Connection connection, String tableName)
            throws Exception {
        State state = state(connection, tableName);
        return state.context().currentCommittedSequence();
    }

    private static Measurement measureExisting(
            State state,
            int start,
            int endExclusive,
            long snapshotSequence,
            boolean includeQuantity) throws Exception {
        Qualifier[][] qualifiers = rangeQualifiers(state.raw(), start, endExclusive);
        List<MvccRawStoreOrderedIndex.Candidate> candidates =
                MvccRawStoreTable.orderedIndexCandidatesForAt(
                                state.table(), qualifiers, state.context(), null)
                        .orElseThrow(() -> new IllegalStateException(
                                "MVCC production ordered-index path was unavailable"));
        FormatableBitSet columns = columns(state.table(), includeQuantity);
        MvccRawStoreVersionRows.FetchProjection projection =
                MvccRawStoreVersionRows.projection(state.table(), columns);

        long rows = 0L;
        long fingerprint = 0xcbf29ce484222325L;
        MvccRawStoreIndexedReadMetrics.Snapshot metrics;
        try (MvccRawStoreIndexedReader reader = new MvccRawStoreIndexedReader(
                state.raw(),
                state.table(),
                snapshotSequence,
                projection,
                state.context())) {
            boolean covering = !includeQuantity;
            for (MvccRawStoreIndexedReader.Result result : reader.read(candidates, covering)) {
                MvccRawStoreTable.VisibleRow row = result.row();
                if (row == null) {
                    continue;
                }
                int key = Math.toIntExact(StoreTypeUtil.getLong(row.values()[0]));
                int quantity = includeQuantity
                        ? Math.toIntExact(StoreTypeUtil.getLong(row.values()[1]))
                        : 0;
                fingerprint = fingerprint(fingerprint, key, quantity, includeQuantity);
                rows++;
            }
            metrics = reader.metrics();
        }
        long localVisible = metrics.currentRowAnchorHits()
                + metrics.directoryHeadSummaryHits();
        return new Measurement(
                rows,
                fingerprint,
                localVisible,
                metrics.fallbackCandidates(),
                metrics.versionSlotFetches(),
                metrics.directoryPageAcquisitions(),
                metrics.candidatesVisited(),
                metrics.currentRowAnchorHits());
    }

    private static Measurement measurePrototype(
            State state,
            ContainerKey key,
            boolean rowBearing,
            long snapshotSequence,
            boolean includeQuantity) throws Exception {
        Transaction raw = state.raw();
        ContainerHandle container = raw.openContainer(
                key,
                MvccRawStorePhysicalLocking.rowLevel(raw),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException("MVCC topology prototype container is absent: " + key);
        }

        FormatableBitSet columns = columns(state.table(), includeQuantity);
        MvccRawStoreVersionRows.FetchProjection projection =
                MvccRawStoreVersionRows.projection(state.table(), columns);
        MvccRawStoreIndexedReadMetrics versionMetrics = new MvccRawStoreIndexedReadMetrics();
        long rows = 0L;
        long localVisible = 0L;
        long historyFallbacks = 0L;
        long fingerprint = 0xcbf29ce484222325L;
        Page page = null;
        try (MvccRawStoreVersionReader versionReader = new MvccRawStoreVersionReader(
                raw, state.table(), versionMetrics)) {
            page = container.getFirstPage();
            Object[] physical = rowBearing
                    ? rowBearingTemplate(raw)
                    : anchorTemplate(raw);
            while (page != null) {
                for (int slot = Page.FIRST_SLOT_NUMBER; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    page.fetchFromSlot(null, slot, physical, null, false);
                    int keyValue = intAt(physical, rowBearing ? R_KEY : A_KEY);
                    long rowId = longAt(physical, rowBearing ? R_ROW_ID : A_ROW_ID);
                    long versionId = longAt(physical, rowBearing ? R_VERSION_ID : A_VERSION_ID);
                    long creator = longAt(physical, rowBearing ? R_CREATOR : A_CREATOR);
                    long begin = longAt(physical, rowBearing ? R_BEGIN : A_BEGIN);
                    int flags = intAt(physical, rowBearing ? R_FLAGS : A_FLAGS);
                    long hintPage = longAt(physical, rowBearing ? R_HINT_PAGE : A_HINT_PAGE);
                    int hintRecord = intAt(physical, rowBearing ? R_HINT_RECORD : A_HINT_RECORD);
                    MvccRawStoreTable.RecordHint hint =
                            new MvccRawStoreTable.RecordHint(hintPage, hintRecord);
                    MvccRawStoreTable.DirectoryHeadSummary summary =
                            new MvccRawStoreTable.DirectoryHeadSummary(true, creator, begin, flags);
                    MvccRawStoreTable.DirectoryHead head =
                            new MvccRawStoreTable.DirectoryHead(versionId, hint, summary);

                    MvccRawStoreTable.VersionRecord visible = null;
                    boolean locallyVisible = summary.visibleTo(
                            state.context().transactionId(), snapshotSequence);
                    if (locallyVisible) {
                        localVisible++;
                        if (summary.tombstone()) {
                            continue;
                        }
                        if (!includeQuantity || rowBearing) {
                            int quantity = includeQuantity ? intAt(physical, R_QUANTITY) : 0;
                            fingerprint = fingerprint(
                                    fingerprint, keyValue, quantity, includeQuantity);
                            rows++;
                            continue;
                        }
                        visible = versionReader.find(
                                rowId, versionId, hint, projection);
                    } else {
                        historyFallbacks++;
                        visible = versionReader.findVisible(
                                rowId,
                                head,
                                state.context().transactionId(),
                                snapshotSequence,
                                projection);
                    }
                    if (visible == null || visible.tombstone()) {
                        continue;
                    }
                    int quantity = includeQuantity
                            ? Math.toIntExact(StoreTypeUtil.getLong(visible.values()[1]))
                            : 0;
                    fingerprint = fingerprint(
                            fingerprint, keyValue, quantity, includeQuantity);
                    rows++;
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
        MvccRawStoreIndexedReadMetrics.Snapshot metrics = versionMetrics.snapshot();
        return new Measurement(
                rows,
                fingerprint,
                localVisible,
                historyFallbacks,
                metrics.versionSlotFetches(),
                0L,
                rows + historyFallbacks,
                0L);
    }

    private static List<CurrentRow> currentRows(State state, int start, int endExclusive)
            throws Exception {
        Transaction raw = state.raw();
        ContainerHandle directory = raw.openContainer(
                state.table().metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(raw),
                ContainerHandle.MODE_READONLY);
        if (directory == null) {
            throw new IllegalStateException("MVCC metadata container is absent");
        }
        FormatableBitSet all = new FormatableBitSet(state.table().columnCount());
        for (int column = 0; column < state.table().columnCount(); column++) {
            all.set(column);
        }
        MvccRawStoreVersionRows.FetchProjection projection =
                MvccRawStoreVersionRows.projection(state.table(), all);
        List<CurrentRow> rows = new ArrayList<>();
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
                    int quantity = Math.toIntExact(StoreTypeUtil.getLong(current.values()[1]));
                    rows.add(new CurrentRow(
                            key,
                            quantity,
                            current.rowId(),
                            current.versionId(),
                            current.creatorTransactionId(),
                            current.beginSequence(),
                            current.flags(),
                            record.head().hint()));
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
        return rows;
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
                throw new IllegalStateException("No base conglomerate for " + tableName);
            }
            return resultSet.getLong(1);
        }
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

    private static Qualifier[][] rangeQualifiers(Transaction raw, int start, int endExclusive)
            throws StandardException {
        return new Qualifier[][] {{
                new FixedQualifier(
                        0,
                        MvccRawStoreFormat.intValue(raw, start),
                        StoreOrderable.ORDER_OP_GREATEROREQUALS),
                new FixedQualifier(
                        0,
                        MvccRawStoreFormat.intValue(raw, endExclusive),
                        StoreOrderable.ORDER_OP_LESSTHAN)
        }};
    }

    private static ContainerKey createContainer(Transaction raw)
            throws StandardException {
        long id = raw.addContainer(
                ContainerHandle.TEMPORARY_SEGMENT,
                0L,
                ContainerHandle.MODE_DEFAULT,
                new Properties(),
                TransactionController.IS_TEMPORARY | TransactionController.IS_KEPT);
        if (id < 0L) {
            throw new IllegalStateException("Could not allocate MVCC topology prototype container");
        }
        return new ContainerKey(ContainerHandle.TEMPORARY_SEGMENT, id);
    }

    private static void insert(Transaction raw, ContainerKey key, Object[] row)
            throws StandardException {
        ContainerHandle container = raw.openContainer(
                key,
                MvccRawStorePhysicalLocking.rowLevel(raw),
                ContainerHandle.MODE_FORUPDATE | ContainerHandle.MODE_TEMP_IS_KEPT);
        if (container == null) {
            throw new IllegalStateException("Prototype container disappeared: " + key);
        }
        Page page = null;
        try {
            page = container.getPageForInsert(0);
            RecordHandle handle = insertOn(page, row);
            if (handle != null) {
                return;
            }
            if (page != null) {
                page.unlatch();
                page = null;
            }
            page = container.getPageForInsert(ContainerHandle.GET_PAGE_UNFILLED);
            handle = insertOn(page, row);
            if (handle != null) {
                return;
            }
            if (page != null) {
                page.unlatch();
                page = null;
            }
            page = container.addPage();
            handle = page.insertAtSlot(
                    Page.FIRST_SLOT_NUMBER,
                    row,
                    null,
                    null,
                    (byte) 0,
                    OVERFLOW_THRESHOLD);
            if (handle == null) {
                throw new IllegalStateException("Prototype row did not fit on empty page");
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static RecordHandle insertOn(Page page, Object[] row) throws StandardException {
        return page == null
                ? null
                : page.insertAtSlot(
                        page.recordCount(), row, null, null, (byte) 0, OVERFLOW_THRESHOLD);
    }

    private static Object[] anchorRow(Transaction raw, CurrentRow row) throws StandardException {
        return new Object[] {
                MvccRawStoreFormat.intValue(raw, row.key()),
                MvccRawStoreFormat.longValue(raw, row.rowId()),
                MvccRawStoreFormat.longValue(raw, row.versionId()),
                MvccRawStoreFormat.longValue(raw, row.creatorTransactionId()),
                MvccRawStoreFormat.longValue(raw, row.beginSequence()),
                MvccRawStoreFormat.intValue(raw, row.flags()),
                MvccRawStoreFormat.longValue(raw, row.hint().pageNumber()),
                MvccRawStoreFormat.intValue(raw, row.hint().recordId())
        };
    }

    private static Object[] rowBearingRow(Transaction raw, CurrentRow row) throws StandardException {
        return new Object[] {
                MvccRawStoreFormat.intValue(raw, row.key()),
                MvccRawStoreFormat.intValue(raw, row.quantity()),
                MvccRawStoreFormat.longValue(raw, row.rowId()),
                MvccRawStoreFormat.longValue(raw, row.versionId()),
                MvccRawStoreFormat.longValue(raw, row.creatorTransactionId()),
                MvccRawStoreFormat.longValue(raw, row.beginSequence()),
                MvccRawStoreFormat.intValue(raw, row.flags()),
                MvccRawStoreFormat.longValue(raw, row.hint().pageNumber()),
                MvccRawStoreFormat.intValue(raw, row.hint().recordId())
        };
    }

    private static Object[] anchorTemplate(Transaction raw) throws StandardException {
        Object[] row = new Object[A_FIELDS];
        row[A_KEY] = MvccRawStoreFormat.intValue(raw, 0);
        row[A_ROW_ID] = MvccRawStoreFormat.longValue(raw, 0L);
        row[A_VERSION_ID] = MvccRawStoreFormat.longValue(raw, 0L);
        row[A_CREATOR] = MvccRawStoreFormat.longValue(raw, 0L);
        row[A_BEGIN] = MvccRawStoreFormat.longValue(raw, 0L);
        row[A_FLAGS] = MvccRawStoreFormat.intValue(raw, 0);
        row[A_HINT_PAGE] = MvccRawStoreFormat.longValue(raw, 0L);
        row[A_HINT_RECORD] = MvccRawStoreFormat.intValue(raw, 0);
        return row;
    }

    private static Object[] rowBearingTemplate(Transaction raw) throws StandardException {
        Object[] row = new Object[R_FIELDS];
        row[R_KEY] = MvccRawStoreFormat.intValue(raw, 0);
        row[R_QUANTITY] = MvccRawStoreFormat.intValue(raw, 0);
        row[R_ROW_ID] = MvccRawStoreFormat.longValue(raw, 0L);
        row[R_VERSION_ID] = MvccRawStoreFormat.longValue(raw, 0L);
        row[R_CREATOR] = MvccRawStoreFormat.longValue(raw, 0L);
        row[R_BEGIN] = MvccRawStoreFormat.longValue(raw, 0L);
        row[R_FLAGS] = MvccRawStoreFormat.intValue(raw, 0);
        row[R_HINT_PAGE] = MvccRawStoreFormat.longValue(raw, 0L);
        row[R_HINT_RECORD] = MvccRawStoreFormat.intValue(raw, 0);
        return row;
    }

    private static int intAt(Object[] row, int field) throws StandardException {
        return Math.toIntExact(StoreTypeUtil.getLong((StoreDataValue) row[field]));
    }

    private static long longAt(Object[] row, int field) throws StandardException {
        return StoreTypeUtil.getLong((StoreDataValue) row[field]);
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

    private static void safeDrop(Transaction raw, ContainerKey key, Throwable primary) {
        try {
            raw.dropContainer(key);
        } catch (Throwable cleanup) {
            primary.addSuppressed(cleanup);
        }
    }

    private record CurrentRow(
            int key,
            int quantity,
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            int flags,
            MvccRawStoreTable.RecordHint hint) {
    }

    private record State(
            TransactionManager transactionManager,
            Transaction raw,
            MvccRawStoreRuntime runtime,
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreTransactionContext context) {
    }

    private record FixedQualifier(
            int columnId,
            StoreDataValue orderable,
            int operator) implements Qualifier {
        @Override
        public int getColumnId() {
            return columnId;
        }

        @Override
        public StoreDataValue getOrderable() {
            return orderable;
        }

        @Override
        public int getOperator() {
            return operator;
        }

        @Override
        public boolean negateCompareResult() {
            return false;
        }

        @Override
        public boolean getOrderedNulls() {
            return true;
        }

        @Override
        public boolean getUnknownRV() {
            return false;
        }

        @Override
        public void clearOrderableCache() {
        }

        @Override
        public void reinitialize() {
        }
    }
}
