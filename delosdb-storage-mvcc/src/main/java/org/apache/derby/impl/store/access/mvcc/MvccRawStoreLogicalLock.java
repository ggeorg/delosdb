/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreLogicalLock

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.derby.iapi.services.locks.ShExLockable;
import org.apache.derby.iapi.services.locks.VirtualLockTable;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

/** Immutable database lock-manager identity for MVCC semantic locks. */
final class MvccRawStoreLogicalLock extends ShExLockable
        implements Comparable<MvccRawStoreLogicalLock> {
    enum Kind {
        TABLE_SCHEMA,
        ROW,
        UNIQUE_KEY
    }

    private final Kind kind;
    private final long tableId;
    private final long rowId;
    private final int constraintOrdinal;
    private final List<StoreDataValue> keyValues;

    private MvccRawStoreLogicalLock(
            Kind kind,
            long tableId,
            long rowId,
            int constraintOrdinal,
            List<StoreDataValue> keyValues) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.tableId = tableId;
        this.rowId = rowId;
        this.constraintOrdinal = constraintOrdinal;
        this.keyValues = List.copyOf(keyValues);
    }

    static MvccRawStoreLogicalLock table(MvccRawStoreTable.Descriptor table) {
        return new MvccRawStoreLogicalLock(
                Kind.TABLE_SCHEMA,
                table.metadataContainer().getContainerId(),
                0L,
                -1,
                List.of());
    }

    static MvccRawStoreLogicalLock row(MvccRawStoreTable.Descriptor table, long rowId) {
        if (rowId <= 0L) {
            throw new IllegalArgumentException("rowId must be positive");
        }
        return new MvccRawStoreLogicalLock(
                Kind.ROW,
                table.metadataContainer().getContainerId(),
                rowId,
                -1,
                List.of());
    }

    static MvccRawStoreLogicalLock uniqueKey(
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreTable.UniqueConstraint constraint,
            StoreDataValue[] row) throws StandardException {
        int[] columns = constraint.columns();
        List<StoreDataValue> values = new ArrayList<>(columns.length);
        for (int column : columns) {
            values.add(StoreValueCopySupport.cloneValue(row[column]));
        }
        return new MvccRawStoreLogicalLock(
                Kind.UNIQUE_KEY,
                table.metadataContainer().getContainerId(),
                0L,
                constraint.ordinal(),
                values);
    }

    @Override
    public boolean lockAttributes(int flag, Map<String, Object> attributes) {
        if ((flag & (VirtualLockTable.TABLE_AND_ROWLOCK | VirtualLockTable.SHEXLOCK)) == 0) {
            return false;
        }
        attributes.put(VirtualLockTable.CONTAINERID, tableId);
        attributes.put(VirtualLockTable.LOCKNAME, toString());
        attributes.put(VirtualLockTable.LOCKTYPE, "DELOS_MVCC");
        return true;
    }

    @Override
    public int compareTo(MvccRawStoreLogicalLock other) {
        int comparison = kind.compareTo(other.kind);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Long.compare(tableId, other.tableId);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Long.compare(rowId, other.rowId);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(constraintOrdinal, other.constraintOrdinal);
        if (comparison != 0) {
            return comparison;
        }
        int common = Math.min(keyValues.size(), other.keyValues.size());
        for (int index = 0; index < common; index++) {
            try {
                comparison = StoreTypeUtil.compare(
                        keyValues.get(index),
                        other.keyValues.get(index),
                        true);
            } catch (StandardException failure) {
                throw new LogicalLockComparisonFailure(failure);
            }
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(keyValues.size(), other.keyValues.size());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MvccRawStoreLogicalLock candidate)
                || kind != candidate.kind
                || tableId != candidate.tableId
                || rowId != candidate.rowId
                || constraintOrdinal != candidate.constraintOrdinal
                || keyValues.size() != candidate.keyValues.size()) {
            return false;
        }
        for (int index = 0; index < keyValues.size(); index++) {
            try {
                if (StoreTypeUtil.compare(
                        keyValues.get(index),
                        candidate.keyValues.get(index),
                        true) != 0) {
                    return false;
                }
            } catch (StandardException failure) {
                throw new LogicalLockComparisonFailure(failure);
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        // SQL comparison, not Java object equality, defines key identity. Keep
        // equal SQL keys in the same bucket without relying on implementation
        // value hash codes. The table/constraint prefix avoids one global bucket.
        return Objects.hash(kind, tableId, rowId, constraintOrdinal);
    }

    @Override
    public String toString() {
        return switch (kind) {
            case TABLE_SCHEMA -> "DELOS_MVCC_SCHEMA[" + tableId + "]";
            case ROW -> "DELOS_MVCC_ROW[" + tableId + ":" + rowId + "]";
            case UNIQUE_KEY -> "DELOS_MVCC_KEY[" + tableId + ":" + constraintOrdinal + "]";
        };
    }

    private static final class LogicalLockComparisonFailure extends RuntimeException {
        LogicalLockComparisonFailure(StandardException cause) {
            super(cause);
        }
    }
}
