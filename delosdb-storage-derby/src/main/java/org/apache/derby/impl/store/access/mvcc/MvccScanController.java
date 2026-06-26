/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccScanController

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derby.impl.store.access.mvcc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.ggeorg.delosdb.storage.mvcc.MvccRow;
import io.github.ggeorg.delosdb.storage.mvcc.MvccScan;
import io.github.ggeorg.delosdb.storage.mvcc.MvccSnapshot;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransaction;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.BackingStoreHashtable;
import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.access.RowUtil;
import org.apache.derby.iapi.store.access.ScanInfo;
import org.apache.derby.iapi.store.access.conglomerate.ScanManager;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreOrderable;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.store.types.StoreValueOperations;
import org.apache.derby.shared.common.error.StandardException;

/**
 * MODULE6D inherited ScanManager preflight for Delos MVCC.
 *
 * <p>The scan opens a statement snapshot against the MVCC kernel and returns
 * visible rows through Derby's inherited ScanController shape. MODULE6F
 * allows physical MVCC full-table SQL SELECT to reach this controller through
 * the inherited TableScanResultSet path.</p>
 */
public final class MvccScanController implements ScanManager {
    private static final AtomicInteger OPEN_COUNT = new AtomicInteger();
    private static final AtomicInteger QUALIFIER_REJECT_COUNT = new AtomicInteger();

    private final MvccConglomerate conglomerate;
    private final MvccConglomerateState state;
    private final TransactionManager transactionManager;
    private final boolean hold;
    private final MvccTransaction reader;
    private final MvccSnapshot snapshot;
    private MvccScan<Long, StoreDataValue[]> scan;
    private final FormatableBitSet scanColumnList;
    private Qualifier[][] qualifiers;
    private MvccRow<Long, StoreDataValue[]> current;
    private boolean closed;
    private long estimatedRowCount;

    MvccScanController(
            MvccConglomerate conglomerate,
            TransactionManager transactionManager,
            boolean hold,
            FormatableBitSet scanColumnList,
            Qualifier[][] qualifiers) {
        OPEN_COUNT.incrementAndGet();
        this.conglomerate = conglomerate;
        this.state = conglomerate.state();
        this.transactionManager = transactionManager;
        this.hold = hold;
        this.scanColumnList = scanColumnList;
        this.qualifiers = qualifiers;
        this.reader = state.transactions().begin();
        this.snapshot = state.transactions().snapshot(reader);
        this.scan = state.table().openScan(snapshot, state.transactions());
    }


    public static void resetOpenCountForTesting() {
        OPEN_COUNT.set(0);
    }

    public static int openCountForTesting() {
        return OPEN_COUNT.get();
    }

    public static void resetQualifierRejectCountForTesting() {
        QUALIFIER_REJECT_COUNT.set(0);
    }

    public static int qualifierRejectCountForTesting() {
        return QUALIFIER_REJECT_COUNT.get();
    }

    public MvccConglomerate conglomerate() {
        return conglomerate;
    }

    @Override
    public void close() {
        if (!closed) {
            scan.close();
            state.transactions().abort(reader);
            closed = true;
            transactionManager.closeMe(this);
        }
    }

    @Override
    public boolean closeForEndTransaction(boolean closeHeldScan) {
        if (!hold || closeHeldScan) {
            close();
            return true;
        }
        return false;
    }

    @Override
    public void fetchSet(long maxRowCount, int[] keyColumnNumbers, BackingStoreHashtable hashTable) {
        ensureOpen();
    }

    @Override
    public ScanInfo getScanInfo() {
        ensureOpen();
        return null;
    }

    @Override
    public boolean isKeyed() {
        return false;
    }

    @Override
    public boolean isTableLocked() {
        return false;
    }

    @Override
    public StoreRowLocation newRowLocationTemplate() {
        ensureOpen();
        return new MvccRowLocation();
    }

    @Override
    public void reopenScan(
            StoreDataValue[] startKeyValue,
            int startSearchOperator,
            Qualifier[][] qualifier,
            StoreDataValue[] stopKeyValue,
            int stopSearchOperator) {
        ensureOpen();
        scan.close();
        current = null;
        scan = state.table().openScan(snapshot, state.transactions());
        this.qualifiers = qualifier;
    }

    @Override
    public void reopenScanByRowLocation(StoreRowLocation startRowLocation, Qualifier[][] qualifier) {
        ensureOpen();
        MvccRowLocation.from(startRowLocation);
        scan.close();
        current = null;
        scan = state.table().openScan(snapshot, state.transactions());
        this.qualifiers = qualifier;
    }

    @Override
    public long getEstimatedRowCount() {
        return estimatedRowCount;
    }

    @Override
    public void setEstimatedRowCount(long count) {
        estimatedRowCount = count;
    }

    @Override
    public boolean delete() {
        ensureOpen();
        return false;
    }

    @Override
    public void didNotQualify() {
        ensureOpen();
    }

    @Override
    public boolean doesCurrentPositionQualify() {
        ensureOpen();
        return current != null;
    }

    @Override
    public boolean isHeldAfterCommit() {
        return hold;
    }

    @Override
    public void fetch(StoreDataValue[] destRow) throws StandardException {
        ensureOpen();
        if (current == null) {
            throw new IllegalStateException("MVCC scan is not positioned on a row");
        }
        copyCurrentRow(destRow, null);
    }

    @Override
    public void fetchWithoutQualify(StoreDataValue[] destRow) throws StandardException {
        fetch(destRow);
    }

    @Override
    public boolean fetchNext(StoreDataValue[] destRow) throws StandardException {
        ensureOpen();
        if (!advanceToNextQualifiedRow()) {
            return false;
        }
        copyCurrentRow(destRow, null);
        return true;
    }

    @Override
    public int fetchNextGroup(StoreDataValue[][] rowArray, StoreRowLocation[] rowlocArray) throws StandardException {
        ensureOpen();
        if (rowArray == null || rowArray.length == 0) {
            return 0;
        }
        int count = 0;
        while (count < rowArray.length && advanceToNextQualifiedRow()) {
            if (rowArray[count] == null) {
                rowArray[count] = newGroupFetchRowTemplate(rowArray);
            }
            MvccConglomerateController.copyRow(current.value(), rowArray[count], null);
            if (rowlocArray != null) {
                if (rowlocArray[count] == null) {
                    rowlocArray[count] = new MvccRowLocation();
                }
                MvccRowLocation.from(rowlocArray[count]).set(current.key(), 0L, -1);
            }
            count++;
        }
        if (count == 0) {
            current = null;
        }
        return count;
    }

    @Override
    public int fetchNextGroup(
            StoreDataValue[][] rowArray,
            StoreRowLocation[] oldrowlocArray,
            StoreRowLocation[] newrowlocArray) throws StandardException {
        return fetchNextGroup(rowArray, oldrowlocArray);
    }

    private StoreDataValue[] newGroupFetchRowTemplate(StoreDataValue[][] rowArray) throws StandardException {
        if (rowArray.length == 0 || rowArray[0] == null) {
            throw new IllegalStateException("MVCC bulk scan requires a non-null first row template");
        }
        return RowUtil.newRowFromTemplatePreservingArrayType(rowArray[0]);
    }

    @Override
    public void fetchLocation(StoreRowLocation destRowLocation) {
        ensureOpen();
        MvccRowLocation destination = MvccRowLocation.from(destRowLocation);
        if (current == null) {
            destination.restoreToNull();
        } else {
            destination.set(current.key(), 0L, -1);
        }
    }

    @Override
    public boolean isCurrentPositionDeleted() {
        ensureOpen();
        return false;
    }

    @Override
    public boolean next() throws StandardException {
        ensureOpen();
        return advanceToNextQualifiedRow();
    }

    @Override
    public boolean positionAtRowLocation(StoreRowLocation rowLocation) {
        ensureOpen();
        MvccRowLocation location = MvccRowLocation.from(rowLocation);
        Optional<StoreDataValue[]> visible = state.table().read(location.rowId(), snapshot, state.transactions());
        if (visible.isEmpty()) {
            current = null;
            return false;
        }
        current = new MvccRow<>(location.rowId(), visible.get());
        return true;
    }

    private boolean advanceToNextQualifiedRow() throws StandardException {
        while (scan.next()) {
            MvccRow<Long, StoreDataValue[]> candidate = scan.row();
            if (rowQualifies(candidate.value())) {
                current = candidate;
                return true;
            }
            QUALIFIER_REJECT_COUNT.incrementAndGet();
        }
        current = null;
        return false;
    }

    private boolean rowQualifies(StoreDataValue[] row) throws StandardException {
        if (qualifiers == null || qualifiers.length == 0) {
            return true;
        }
        for (int group = 0; group < qualifiers.length; group++) {
            Qualifier[] groupQualifiers = qualifiers[group];
            if (groupQualifiers == null || groupQualifiers.length == 0) {
                continue;
            }
            boolean groupResult = group == 0;
            if (group == 0) {
                for (Qualifier qualifier : groupQualifiers) {
                    if (qualifier != null && !qualifierMatches(row, qualifier)) {
                        groupResult = false;
                        break;
                    }
                }
            } else {
                groupResult = false;
                for (Qualifier qualifier : groupQualifiers) {
                    if (qualifier != null && qualifierMatches(row, qualifier)) {
                        groupResult = true;
                        break;
                    }
                }
            }
            if (!groupResult) {
                return false;
            }
        }
        return true;
    }

    private boolean qualifierMatches(StoreDataValue[] row, Qualifier qualifier) throws StandardException {
        int columnId = qualifier.getColumnId();
        if (row == null || columnId < 0 || columnId >= row.length) {
            return false;
        }
        StoreDataValue columnValue = row[columnId];
        StoreDataValue orderable = qualifier.getOrderable();
        boolean result = compare(columnValue, qualifier.getOperator(), orderable,
                qualifier.getOrderedNulls(), qualifier.getUnknownRV());
        return qualifier.negateCompareResult() ? !result : result;
    }

    private boolean compare(
            StoreDataValue left,
            int operator,
            StoreDataValue right,
            boolean orderedNulls,
            boolean unknownRV) throws StandardException {
        if (left instanceof StoreValueOperations storeValue) {
            return storeValue.compare(operator, right, orderedNulls, unknownRV);
        }

        Boolean reflected = compareReflectively(left, operator, right, orderedNulls, unknownRV);
        if (reflected != null) {
            return reflected;
        }

        return compareObjects(left, operator, right, orderedNulls, unknownRV);
    }

    private Boolean compareReflectively(
            StoreDataValue left,
            int operator,
            StoreDataValue right,
            boolean orderedNulls,
            boolean unknownRV) throws StandardException {
        if (left == null) {
            return orderedNulls ? right == null : unknownRV;
        }
        for (Method method : left.getClass().getMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (!"compare".equals(method.getName())
                    || method.getReturnType() != boolean.class
                    || parameterTypes.length != 4
                    || parameterTypes[0] != int.class
                    || parameterTypes[2] != boolean.class
                    || parameterTypes[3] != boolean.class
                    || right == null
                    || !parameterTypes[1].isInstance(right)) {
                continue;
            }
            try {
                return (Boolean) method.invoke(left, operator, right, orderedNulls, unknownRV);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot access Derby value comparison method", e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof StandardException standardException) {
                    throw standardException;
                }
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Derby value comparison failed", cause);
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean compareObjects(
            StoreDataValue left,
            int operator,
            StoreDataValue right,
            boolean orderedNulls,
            boolean unknownRV) throws StandardException {
        Object leftObject = objectValue(left);
        Object rightObject = objectValue(right);
        if (leftObject == null || rightObject == null) {
            if (!orderedNulls) {
                return unknownRV;
            }
            int nullComparison = leftObject == rightObject ? 0 : leftObject == null ? -1 : 1;
            return compareResult(operator, nullComparison);
        }
        if (!(leftObject instanceof Comparable comparable)) {
            return leftObject.equals(rightObject) && operator == StoreOrderable.ORDER_OP_EQUALS;
        }
        return compareResult(operator, comparable.compareTo(rightObject));
    }

    private Object objectValue(StoreDataValue value) throws StandardException {
        if (value == null) {
            return null;
        }
        if (value instanceof StoreValueOperations storeValue) {
            return storeValue.getObject();
        }
        try {
            Method method = value.getClass().getMethod("getObject");
            return method.invoke(value);
        } catch (NoSuchMethodException e) {
            return value;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access Derby value object method", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof StandardException standardException) {
                throw standardException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Derby value object lookup failed", cause);
        }
    }

    private boolean compareResult(int operator, int comparison) throws StandardException {
        return switch (operator) {
            case StoreOrderable.ORDER_OP_EQUALS -> comparison == 0;
            case StoreOrderable.ORDER_OP_LESSTHAN -> comparison < 0;
            case StoreOrderable.ORDER_OP_LESSOREQUALS -> comparison <= 0;
            case StoreOrderable.ORDER_OP_GREATERTHAN -> comparison > 0;
            case StoreOrderable.ORDER_OP_GREATEROREQUALS -> comparison >= 0;
            default -> throw StandardException.newException(
                    org.apache.derby.shared.common.reference.SQLState.STORE_FEATURE_NOT_IMPLEMENTED);
        };
    }

    private void copyCurrentRow(StoreDataValue[] destRow, FormatableBitSet validColumns) throws StandardException {
        MvccConglomerateController.copyRow(current.value(), destRow, validColumns);
        copyCurrentRowLocation(destRow);
    }

    private void copyCurrentRowLocation(StoreDataValue[] destRow) {
        if (destRow == null || current == null || destRow.length <= current.value().length) {
            return;
        }
        StoreDataValue rowLocationColumn = destRow[current.value().length];
        if (rowLocationColumn instanceof StoreRowLocation rowLocation) {
            MvccRowLocation.from(rowLocation).set(current.key(), 0L, -1);
        }
    }

    @Override
    public boolean replace(StoreDataValue[] row, FormatableBitSet validColumns) {
        ensureOpen();
        return false;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("MVCC scan controller is closed");
        }
    }
}
