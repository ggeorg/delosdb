/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.DerbyMvccCandidateIndex

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.ggeorg.delosdb.storage.mvcc.store.MvccCandidateIndex;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore;

import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreOrderable;
import org.apache.derby.shared.common.error.StandardException;

/** Derby qualifier/value adapter over the storage-mvcc candidate index. */
final class DerbyMvccCandidateIndex {
    private final MvccCandidateIndex index = new MvccCandidateIndex();

    synchronized void rebuildFromVisibleRows(List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows) {
        index.rebuildFromVisibleRows(toCandidateRows(rows));
    }

    synchronized void recordVisibleRows(List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows) {
        index.recordVisibleRows(toCandidateRows(rows));
    }

    synchronized void clear() {
        index.clear();
    }

    synchronized Optional<List<Long>> candidatesFor(Qualifier[][] qualifiers) {
        Optional<ColumnValueKey> key = equalityCandidateKey(qualifiers);
        return key.map(columnValueKey -> index.candidatesFor(columnValueKey.column(), columnValueKey.value()))
                .orElseGet(Optional::empty);
    }

    synchronized int indexedKeyCountForTesting() {
        return index.indexedKeyCountForTesting();
    }

    private static List<MvccCandidateIndex.CandidateRow> toCandidateRows(
            List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows) {
        if (rows == null) {
            return List.of();
        }
        List<MvccCandidateIndex.CandidateRow> candidates = new ArrayList<>(rows.size());
        for (PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]> row : rows) {
            candidates.add(new MvccCandidateIndex.CandidateRow(row.rowId(), valueKeys(row.values())));
        }
        return List.copyOf(candidates);
    }

    private static List<String> valueKeys(StoreDataValue[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(values.length);
        for (StoreDataValue value : values) {
            keys.add(value == null ? null : valueKey(value));
        }
        return List.copyOf(keys);
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
    }
}
