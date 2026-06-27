/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.InheritedMvccCandidateIndex

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreOrderable;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Rebuildable logical candidate index for the inherited MVCC provider.
 *
 * <p>MODULE16 deliberately keeps this conservative: it indexes Derby-visible
 * logical row ids by column/value and never treats the index result as truth.
 * {@link MvccScanController} must re-read every candidate through MVCC
 * visibility and then re-apply Derby {@link org.apache.derby.iapi.store.access.RowUtil}
 * qualification. Stale entries are allowed.</p>
 */
final class InheritedMvccCandidateIndex {
    private final Map<ColumnValueKey, LinkedHashSet<Long>> rowIdsByColumnValue = new LinkedHashMap<>();
    private boolean initialized;

    synchronized void rebuildFromVisibleRows(List<InheritedMvccPageVolumeStateStore.PersistedRow> rows) {
        rowIdsByColumnValue.clear();
        initialized = true;
        recordVisibleRows(rows);
    }

    synchronized void recordVisibleRows(List<InheritedMvccPageVolumeStateStore.PersistedRow> rows) {
        initialized = true;
        if (rows == null) {
            return;
        }
        for (InheritedMvccPageVolumeStateStore.PersistedRow row : rows) {
            indexRow(row.rowId(), row.values());
        }
    }

    synchronized void clear() {
        rowIdsByColumnValue.clear();
        initialized = false;
    }

    synchronized Optional<List<Long>> candidatesFor(Qualifier[][] qualifiers) {
        if (!initialized) {
            return Optional.empty();
        }
        Optional<ColumnValueKey> key = equalityCandidateKey(qualifiers);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        LinkedHashSet<Long> rowIds = rowIdsByColumnValue.get(key.get());
        if (rowIds == null) {
            return Optional.of(List.of());
        }
        return Optional.of(List.copyOf(rowIds));
    }

    synchronized int indexedKeyCountForTesting() {
        return rowIdsByColumnValue.size();
    }

    private void indexRow(long rowId, StoreDataValue[] values) {
        if (rowId <= 0L || values == null) {
            return;
        }
        for (int column = 0; column < values.length; column++) {
            StoreDataValue value = values[column];
            if (value == null) {
                continue;
            }
            ColumnValueKey key = new ColumnValueKey(column, valueKey(value));
            rowIdsByColumnValue.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(rowId);
        }
    }

    private static Optional<ColumnValueKey> equalityCandidateKey(Qualifier[][] qualifiers) {
        if (qualifiers == null) {
            return Optional.empty();
        }
        for (Qualifier[] andTerm : qualifiers) {
            if (andTerm == null || andTerm.length != 1 || andTerm[0] == null) {
                continue;
            }
            Qualifier qualifier = andTerm[0];
            if (qualifier.getColumnId() < 0
                    || qualifier.getOperator() != StoreOrderable.ORDER_OP_EQUALS
                    || qualifier.negateCompareResult()) {
                continue;
            }
            try {
                StoreDataValue orderable = qualifier.getOrderable();
                if (orderable == null) {
                    return Optional.empty();
                }
                return Optional.of(new ColumnValueKey(qualifier.getColumnId(), valueKey(orderable)));
            } catch (StandardException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static String valueKey(StoreDataValue value) {
        try {
            Method getString = value.getClass().getMethod("getString");
            Object result = getString.invoke(value);
            return result == null ? "<null>" : result.toString();
        } catch (NoSuchMethodException e) {
            return value.toString();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access store value key operation on "
                    + value.getClass().getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            return value.toString();
        }
    }

    private record ColumnValueKey(int column, String value) {
        private ColumnValueKey {
            if (column < 0) {
                throw new IllegalArgumentException("candidate index column must be non-negative: " + column);
            }
            value = java.util.Objects.requireNonNull(value, "value");
        }
    }
}
