/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccDeletedKeyProof

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.ArrayList;
import java.util.List;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

/**
 * A unique key this transaction deleted, kept so that re-inserting the same
 * key in the same transaction can reuse the delete's uniqueness check.
 */
final class MvccDeletedKeyProof {
    private final MvccRawStoreTable.Descriptor table;
    private final MvccRawStoreTable.PendingVersion tombstone;
    private final List<MvccRawStoreTable.UniqueConstraint> constraints;
    private final List<StoreDataValue[]> uniqueKeys;
    private MvccRawStoreTable.PendingVersion replacement;

    MvccDeletedKeyProof(
            MvccRawStoreTable.Descriptor table,
            StoreDataValue[] deletedValues,
            MvccRawStoreTable.PendingVersion tombstone,
            List<MvccRawStoreTable.UniqueConstraint> constraints) throws StandardException {
        this.table = table;
        this.tombstone = tombstone;
        this.constraints = List.copyOf(constraints);
        List<StoreDataValue[]> keys = new ArrayList<>(constraints.size());
        for (MvccRawStoreTable.UniqueConstraint constraint : constraints) {
            int[] columns = constraint.columns();
            StoreDataValue[] key = new StoreDataValue[columns.length];
            for (int index = 0; index < columns.length; index++) {
                key[index] = StoreValueCopySupport.cloneValue(
                        deletedValues[columns[index]],
                        true);
            }
            keys.add(key);
        }
        this.uniqueKeys = List.copyOf(keys);
    }

    boolean belongsTo(MvccRawStoreTable.Descriptor candidate) {
        return table.metadataContainer().equals(candidate.metadataContainer())
                && table.versionContainer().equals(candidate.versionContainer());
    }

    MvccRawStoreTable.PendingVersion tombstone() {
        return tombstone;
    }

    boolean available() {
        return replacement == null;
    }

    void consume(MvccRawStoreTable.PendingVersion replacement) {
        if (this.replacement != null) {
            throw new IllegalStateException(
                    "RawStore MVCC deleted-key proof was already consumed");
        }
        this.replacement = replacement;
    }

    void releaseReplacement() {
        replacement = null;
    }

    MvccRawStoreTable.PendingVersion replacement() {
        return replacement;
    }

    boolean matchesCurrentConstraints(
            List<MvccRawStoreTable.UniqueConstraint> current) {
        if (constraints.size() != current.size()) {
            return false;
        }
        for (int index = 0; index < constraints.size(); index++) {
            MvccRawStoreTable.UniqueConstraint expected = constraints.get(index);
            MvccRawStoreTable.UniqueConstraint actual = current.get(index);
            if (expected.ordinal() != actual.ordinal()
                    || !expected.matches(
                            actual.columns(),
                            actual.duplicateNullsAllowed())) {
                return false;
            }
        }
        return true;
    }

    boolean matchesUniqueKeys(StoreDataValue[] values) throws StandardException {
        if (values == null || values.length != table.columnCount()) {
            return false;
        }
        for (int constraintIndex = 0;
                constraintIndex < constraints.size();
                constraintIndex++) {
            MvccRawStoreTable.UniqueConstraint constraint =
                    constraints.get(constraintIndex);
            int[] columns = constraint.columns();
            StoreDataValue[] deletedKey = uniqueKeys.get(constraintIndex);
            if (constraint.duplicateNullsAllowed()
                    && (containsNull(deletedKey)
                        || MvccRawStoreOrderedIndex.containsNull(values, columns))) {
                return false;
            }
            for (int index = 0; index < columns.length; index++) {
                if (StoreTypeUtil.compare(
                        deletedKey[index],
                        values[columns[index]],
                        true) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean containsNull(StoreDataValue[] values)
            throws StandardException {
        for (StoreDataValue value : values) {
            if (StoreTypeUtil.isNull(value)) {
                return true;
            }
        }
        return false;
    }
}
