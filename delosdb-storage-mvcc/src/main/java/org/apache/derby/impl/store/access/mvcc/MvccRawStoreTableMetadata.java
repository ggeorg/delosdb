/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreTableMetadata

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.impl.store.access.mvcc.MvccRawStoreTable.Descriptor;
import org.apache.derby.impl.store.access.mvcc.MvccRawStoreTable.UniqueConstraint;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/**
 * Persists and validates the schema-bearing control row for a RawStore MVCC
 * table. Row directories, versions, visibility, and mutation remain in
 * {@link MvccRawStoreTable}.
 */
final class MvccRawStoreTableMetadata {
    private MvccRawStoreTableMetadata() {
    }

    static Descriptor read(Transaction rawTransaction, ContainerKey metadataKey) throws StandardException {
        ContainerHandle container = rawTransaction.openContainer(
                metadataKey,
                MvccRawStorePhysicalLocking.rowLevel(rawTransaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            return null;
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            if (page == null || page.recordCount() < 2) {
                return null;
            }
            Object[] prefix = controlTemplate(rawTransaction, 0, false, -1);
            page.fetchFromSlot(null, Page.FIRST_SLOT_NUMBER, prefix, null, false);
            if (MvccRawStoreFormat.longAt(prefix, MvccRawStoreFormat.CONTROL_MAGIC)
                    != MvccRawStoreFormat.MAGIC
                    || MvccRawStoreFormat.intAt(prefix, MvccRawStoreFormat.CONTROL_KIND_FIELD)
                    != MvccRawStoreFormat.CONTROL_KIND
                    || MvccRawStoreFormat.intAt(prefix, MvccRawStoreFormat.CONTROL_FORMAT_VERSION)
                    != MvccRawStoreFormat.FORMAT_VERSION) {
                return null;
            }
            int columnCount = MvccRawStoreFormat.intAt(prefix, MvccRawStoreFormat.CONTROL_COLUMN_COUNT);
            int controlFieldCount = page.fetchNumFieldsAtSlot(Page.FIRST_SLOT_NUMBER);
            int orderedIndexField = MvccRawStoreFormat.controlOrderedIndexContainerField(columnCount);
            boolean hasOrderedIndexField = controlFieldCount > orderedIndexField;
            int uniqueCountField = MvccRawStoreFormat.controlUniqueConstraintCountField(columnCount);
            boolean hasUniqueMetadata = controlFieldCount > uniqueCountField;
            Object[] row = controlTemplate(
                    rawTransaction,
                    columnCount,
                    hasOrderedIndexField,
                    hasUniqueMetadata ? controlFieldCount - uniqueCountField - 1 : -1);
            page.fetchFromSlot(null, Page.FIRST_SLOT_NUMBER, row, null, false);
            int[] formatIds = new int[columnCount];
            int[] collationIds = new int[columnCount];
            for (int index = 0; index < columnCount; index++) {
                formatIds[index] = MvccRawStoreFormat.intAt(
                        row,
                        MvccRawStoreFormat.CONTROL_FIXED_FIELDS + index);
                collationIds[index] = MvccRawStoreFormat.intAt(
                        row,
                        MvccRawStoreFormat.CONTROL_FIXED_FIELDS + columnCount + index);
            }
            ContainerKey orderedIndexContainer = null;
            if (hasOrderedIndexField) {
                long orderedIndexId = MvccRawStoreFormat.longAt(row, orderedIndexField);
                if (orderedIndexId > 0L) {
                    orderedIndexContainer = new ContainerKey(metadataKey.getSegmentId(), orderedIndexId);
                }
            }
            List<UniqueConstraint> uniqueConstraints = hasUniqueMetadata
                    ? decodeUniqueConstraints(row, columnCount)
                    : List.of();
            return new Descriptor(
                    new ContainerKey(
                            metadataKey.getSegmentId(),
                            MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.CONTROL_METADATA_CONTAINER)),
                    new ContainerKey(
                            metadataKey.getSegmentId(),
                            MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.CONTROL_VERSION_CONTAINER)),
                    orderedIndexContainer,
                    formatIds,
                    collationIds,
                    MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.CONTROL_TEMPORARY) != 0,
                    uniqueConstraints);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    static void validateUniqueConstraintDefinition(
            Descriptor table,
            int[] baseColumnPositions,
            boolean deferrable) throws StandardException {
        if (deferrable) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "deferrable unique constraints for RawStore-backed delos_mvcc");
        }
        validateUniqueColumns(table, baseColumnPositions);
        MvccRawStoreOrderedIndex.validateConstraintColumns(table, baseColumnPositions);
    }

    static void addUniqueConstraint(
            Transaction transaction,
            Descriptor table,
            int[] baseColumnPositions,
            boolean duplicateNullsAllowed,
            boolean deferrable,
            MvccRawStoreTransactionContext context) throws StandardException {
        validateUniqueConstraintDefinition(table, baseColumnPositions, deferrable);
        context.beforeWrite();
        MvccRawStoreTable.ensureOrderedIndex(context.transactionManager(), table);

        List<UniqueConstraint> existing = refreshUniqueConstraints(transaction, table, true);
        UniqueConstraint candidate = new UniqueConstraint(
                existing.size() + 1,
                baseColumnPositions,
                duplicateNullsAllowed);
        MvccRawStoreOrderedIndex.assertConstraintCanBeAdded(
                transaction,
                table,
                candidate,
                context);

        List<UniqueConstraint> updated = new ArrayList<>(existing);
        updated.add(candidate);
        rewriteControlRow(transaction, table, updated);
        table.observeUniqueConstraints(updated);
        context.refreshOrderedIndexReplacement(table);
    }

    static void dropUniqueConstraint(
            Transaction transaction,
            Descriptor table,
            int[] baseColumnPositions,
            boolean duplicateNullsAllowed,
            MvccRawStoreTransactionContext context) throws StandardException {
        validateUniqueColumns(table, baseColumnPositions);
        context.beforeWrite();

        List<UniqueConstraint> existing = refreshUniqueConstraints(transaction, table, true);
        // Compatibility: a table created before native unique metadata existed may
        // still have an inherited Derby unique index/constraint. Its SQL DROP must
        // not be blocked merely because there is no access-method definition to remove.
        if (existing.isEmpty()) {
            return;
        }
        List<UniqueConstraint> updated = new ArrayList<>(existing.size());
        boolean removed = false;
        for (UniqueConstraint constraint : existing) {
            if (!removed && constraint.matches(baseColumnPositions, duplicateNullsAllowed)) {
                removed = true;
                continue;
            }
            updated.add(new UniqueConstraint(
                    updated.size() + 1,
                    constraint.columns(),
                    constraint.duplicateNullsAllowed()));
        }
        if (!removed) {
            throw new IllegalStateException(
                    "RawStore MVCC unique metadata is absent for requested key");
        }
        rewriteControlRow(transaction, table, updated);
        table.observeUniqueConstraints(updated);
        context.refreshOrderedIndexReplacement(table);
    }

    static List<UniqueConstraint> refreshUniqueConstraints(
            Transaction transaction,
            Descriptor table,
            boolean forUpdate) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                forUpdate ? ContainerHandle.MODE_FORUPDATE : ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC metadata container is absent: " + table.metadataContainer());
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            int countField = MvccRawStoreFormat.controlUniqueConstraintCountField(
                    table.columnCount());
            if (page == null || page.fetchNumFieldsAtSlot(Page.FIRST_SLOT_NUMBER) <= countField) {
                table.observeUniqueConstraints(List.of());
                return List.of();
            }
            int fieldCount = page.fetchNumFieldsAtSlot(Page.FIRST_SLOT_NUMBER);
            Object[] row = controlTemplate(
                    transaction,
                    table.columnCount(),
                    true,
                    fieldCount - countField - 1);
            page.fetchFromSlot(null, Page.FIRST_SLOT_NUMBER, row, null, false);
            List<UniqueConstraint> constraints = decodeUniqueConstraints(row, table.columnCount());
            table.observeUniqueConstraints(constraints);
            return constraints;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static void validateUniqueColumns(
            Descriptor table,
            int[] baseColumnPositions) {
        if (baseColumnPositions == null || baseColumnPositions.length == 0) {
            throw new IllegalArgumentException("RawStore MVCC unique key must contain a column");
        }
        Set<Integer> seen = new HashSet<>();
        for (int column : baseColumnPositions) {
            if (column < 0 || column >= table.columnCount()) {
                throw new IllegalArgumentException(
                        "RawStore MVCC unique column outside table row: " + column);
            }
            if (!seen.add(column)) {
                throw new IllegalArgumentException(
                        "RawStore MVCC unique key repeats column: " + column);
            }
        }
    }

    static ContainerKey discoverOrderedIndexContainer(
            Transaction transaction,
            Descriptor table,
            boolean forUpdate) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                forUpdate ? ContainerHandle.MODE_FORUPDATE : ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC metadata container is absent: " + table.metadataContainer());
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            int field = MvccRawStoreFormat.controlOrderedIndexContainerField(table.columnCount());
            if (page == null || page.fetchNumFieldsAtSlot(Page.FIRST_SLOT_NUMBER) <= field) {
                table.observeOrderedIndexContainer(null);
                return null;
            }
            StoreDataValue value = MvccRawStoreFormat.longValue(transaction, 0L);
            page.fetchFieldFromSlot(Page.FIRST_SLOT_NUMBER, field, value);
            long containerId = StoreTypeUtil.getLong(value);
            ContainerKey key = containerId <= 0L
                    ? null
                    : new ContainerKey(table.metadataContainer().getSegmentId(), containerId);
            table.observeOrderedIndexContainer(key);
            return key;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    static void rewriteControlRow(Transaction transaction, Descriptor table)
            throws StandardException {
        rewriteControlRow(transaction, table, table.uniqueConstraints());
    }

    private static void rewriteControlRow(
            Transaction transaction,
            Descriptor table,
            List<UniqueConstraint> uniqueConstraints) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC metadata container is absent: " + table.metadataContainer());
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            page.updateAtSlot(
                    Page.FIRST_SLOT_NUMBER,
                    controlRow(transaction, table, uniqueConstraints),
                    null);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    static ContainerKey publishOrderedIndexContainer(
            Transaction transaction,
            Descriptor table,
            ContainerKey replacement) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC metadata container is absent: " + table.metadataContainer());
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            int field = MvccRawStoreFormat.controlOrderedIndexContainerField(table.columnCount());
            int fieldCount = page.fetchNumFieldsAtSlot(Page.FIRST_SLOT_NUMBER);
            long currentId = 0L;
            if (fieldCount > field) {
                StoreDataValue current = MvccRawStoreFormat.longValue(transaction, 0L);
                page.fetchFieldFromSlot(Page.FIRST_SLOT_NUMBER, field, current);
                currentId = StoreTypeUtil.getLong(current);
            }
            // A compatibility table may still have the shorter pre-index
            // control-row shape. Rewrite the complete current format rather
            // than updating a field which does not physically exist yet.
            page.updateAtSlot(
                    Page.FIRST_SLOT_NUMBER,
                    controlRow(transaction, table, table.uniqueConstraints(), replacement),
                    null);
            return currentId <= 0L
                    ? null
                    : new ContainerKey(table.metadataContainer().getSegmentId(), currentId);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    static Object[] controlRow(Transaction transaction, Descriptor descriptor) throws StandardException {
        return controlRow(transaction, descriptor, descriptor.uniqueConstraints());
    }

    private static Object[] controlRow(
            Transaction transaction,
            Descriptor descriptor,
            List<UniqueConstraint> uniqueConstraints) throws StandardException {
        return controlRow(
                transaction,
                descriptor,
                uniqueConstraints,
                descriptor.orderedIndexContainer());
    }

    private static Object[] controlRow(
            Transaction transaction,
            Descriptor descriptor,
            List<UniqueConstraint> uniqueConstraints,
            ContainerKey orderedIndexContainer) throws StandardException {
        int uniqueMetadataFields = uniqueMetadataFieldCount(uniqueConstraints);
        Object[] row = controlTemplate(
                transaction,
                descriptor.columnCount(),
                true,
                uniqueMetadataFields);
        row[MvccRawStoreFormat.CONTROL_MAGIC] = MvccRawStoreFormat.longValue(transaction, MvccRawStoreFormat.MAGIC);
        row[MvccRawStoreFormat.CONTROL_KIND_FIELD] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.CONTROL_KIND);
        row[MvccRawStoreFormat.CONTROL_FORMAT_VERSION] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.FORMAT_VERSION);
        row[MvccRawStoreFormat.CONTROL_METADATA_CONTAINER] = MvccRawStoreFormat.longValue(
                transaction,
                descriptor.metadataContainer().getContainerId());
        row[MvccRawStoreFormat.CONTROL_VERSION_CONTAINER] = MvccRawStoreFormat.longValue(
                transaction,
                descriptor.versionContainer().getContainerId());
        row[MvccRawStoreFormat.CONTROL_COLUMN_COUNT] = MvccRawStoreFormat.intValue(
                transaction,
                descriptor.columnCount());
        row[MvccRawStoreFormat.CONTROL_TEMPORARY] = MvccRawStoreFormat.intValue(
                transaction,
                descriptor.temporary() ? 1 : 0);
        for (int index = 0; index < descriptor.columnCount(); index++) {
            row[MvccRawStoreFormat.CONTROL_FIXED_FIELDS + index] = MvccRawStoreFormat.intValue(
                    transaction,
                    descriptor.formatIds()[index]);
            row[MvccRawStoreFormat.CONTROL_FIXED_FIELDS + descriptor.columnCount() + index] =
                    MvccRawStoreFormat.intValue(transaction, descriptor.collationIds()[index]);
        }
        row[MvccRawStoreFormat.controlOrderedIndexContainerField(descriptor.columnCount())] =
                MvccRawStoreFormat.longValue(
                        transaction,
                        orderedIndexContainer == null ? 0L : orderedIndexContainer.getContainerId());
        int field = MvccRawStoreFormat.controlUniqueConstraintCountField(descriptor.columnCount());
        row[field++] = MvccRawStoreFormat.intValue(
                transaction,
                uniqueConstraints.size());
        for (UniqueConstraint constraint : uniqueConstraints) {
            row[field++] = MvccRawStoreFormat.intValue(
                    transaction,
                    constraint.duplicateNullsAllowed() ? 1 : 0);
            int[] columns = constraint.columns();
            row[field++] = MvccRawStoreFormat.intValue(transaction, columns.length);
            for (int column : columns) {
                row[field++] = MvccRawStoreFormat.intValue(transaction, column);
            }
        }
        return row;
    }

    private static Object[] controlTemplate(
            Transaction transaction,
            int columnCount,
            boolean includeOrderedIndex,
            int uniqueMetadataFields) throws StandardException {
        Object[] row = new Object[includeOrderedIndex
                ? (uniqueMetadataFields >= 0
                        ? MvccRawStoreFormat.controlFieldCount(columnCount, uniqueMetadataFields)
                        : MvccRawStoreFormat.controlUniqueConstraintCountField(columnCount))
                : MvccRawStoreFormat.controlOrderedIndexContainerField(columnCount)];
        row[MvccRawStoreFormat.CONTROL_MAGIC] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.CONTROL_KIND_FIELD] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.CONTROL_FORMAT_VERSION] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.CONTROL_METADATA_CONTAINER] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.CONTROL_VERSION_CONTAINER] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.CONTROL_COLUMN_COUNT] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.CONTROL_TEMPORARY] = MvccRawStoreFormat.intValue(transaction, 0);
        int orderedIndexField = MvccRawStoreFormat.controlOrderedIndexContainerField(columnCount);
        for (int index = MvccRawStoreFormat.CONTROL_FIXED_FIELDS;
                index < Math.min(row.length, orderedIndexField);
                index++) {
            row[index] = MvccRawStoreFormat.intValue(transaction, 0);
        }
        if (includeOrderedIndex) {
            row[orderedIndexField] = MvccRawStoreFormat.longValue(transaction, 0L);
            if (uniqueMetadataFields >= 0) {
                int uniqueCountField = MvccRawStoreFormat.controlUniqueConstraintCountField(columnCount);
                for (int index = uniqueCountField; index < row.length; index++) {
                    row[index] = MvccRawStoreFormat.intValue(transaction, 0);
                }
            }
        }
        return row;
    }

    static List<UniqueConstraint> parseUniqueConstraints(
            String encoded,
            int columnCount) throws StandardException {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<UniqueConstraint> result = new ArrayList<>();
        String[] definitions = encoded.split(";", -1);
        for (int ordinal = 0; ordinal < definitions.length; ordinal++) {
            String[] parts = definitions[ordinal].split(":", -1);
            if (parts.length != 3 || parts[0].length() != 1) {
                throw new IllegalArgumentException(
                        "Invalid access-method unique metadata: " + definitions[ordinal]);
            }
            boolean duplicateNullsAllowed = switch (parts[0].charAt(0)) {
                case 'S' -> false;
                case 'N' -> true;
                default -> throw new IllegalArgumentException(
                        "Invalid access-method unique mode: " + parts[0]);
            };
            if ("1".equals(parts[1])) {
                throw StandardException.newException(
                        SQLState.NOT_IMPLEMENTED,
                        "deferrable unique constraints for RawStore-backed delos_mvcc");
            }
            if (!"0".equals(parts[1])) {
                throw new IllegalArgumentException(
                        "Invalid access-method deferred flag: " + parts[1]);
            }
            String[] encodedColumns = parts[2].split(",", -1);
            int[] columns = new int[encodedColumns.length];
            for (int index = 0; index < encodedColumns.length; index++) {
                int column = Integer.parseInt(encodedColumns[index]);
                if (column < 0 || column >= columnCount) {
                    throw new IllegalArgumentException(
                            "Unique column outside table row: " + column);
                }
                columns[index] = column;
            }
            result.add(new UniqueConstraint(
                    ordinal + 1,
                    columns,
                    duplicateNullsAllowed));
        }
        return List.copyOf(result);
    }

    private static int uniqueMetadataFieldCount(List<UniqueConstraint> constraints) {
        int count = 0;
        for (UniqueConstraint constraint : constraints) {
            count = Math.addExact(count, 2 + constraint.columns().length);
        }
        return count;
    }

    private static List<UniqueConstraint> decodeUniqueConstraints(
            Object[] row,
            int columnCount) throws StandardException {
        int field = MvccRawStoreFormat.controlUniqueConstraintCountField(columnCount);
        int count = MvccRawStoreFormat.intAt(row, field++);
        if (count < 0) {
            throw new IllegalStateException("Negative RawStore MVCC unique-constraint count");
        }
        List<UniqueConstraint> result = new ArrayList<>(count);
        for (int ordinal = 0; ordinal < count; ordinal++) {
            if (field + 2 > row.length) {
                throw new IllegalStateException("Truncated RawStore MVCC unique metadata");
            }
            boolean duplicateNullsAllowed = MvccRawStoreFormat.intAt(row, field++) != 0;
            int keyColumns = MvccRawStoreFormat.intAt(row, field++);
            if (keyColumns <= 0 || field + keyColumns > row.length) {
                throw new IllegalStateException("Invalid RawStore MVCC unique key width");
            }
            int[] columns = new int[keyColumns];
            for (int index = 0; index < keyColumns; index++) {
                int column = MvccRawStoreFormat.intAt(row, field++);
                if (column < 0 || column >= columnCount) {
                    throw new IllegalStateException(
                            "RawStore MVCC unique column outside table row: " + column);
                }
                columns[index] = column;
            }
            result.add(new UniqueConstraint(
                    ordinal + 1,
                    columns,
                    duplicateNullsAllowed));
        }
        if (field != row.length) {
            throw new IllegalStateException("Unexpected trailing RawStore MVCC unique metadata");
        }
        return List.copyOf(result);
    }
}
