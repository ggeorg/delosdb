/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreVacuum

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.shared.common.error.StandardException;

/** Transactional chain repair and physical reclamation for RawStore MVCC tables. */
final class MvccRawStoreVacuum {
    private MvccRawStoreVacuum() {
    }

    static Result vacuum(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long oldestVisibleThrough) throws StandardException {
        if (oldestVisibleThrough < 0L) {
            throw new IllegalArgumentException(
                    "RawStore MVCC vacuum horizon must be committed: " + oldestVisibleThrough);
        }

        List<DirectoryEntry> directories = readDirectories(transaction, table);
        List<VersionEntry> versions = readVersions(transaction, table);
        VacuumPlan plan = plan(directories, versions, oldestVisibleThrough);
        if (!plan.mutated()) {
            return new Result(
                    oldestVisibleThrough,
                    0,
                    0,
                    0,
                    0,
                    versions.size(),
                    directories.size());
        }

        rewriteRetainedLinks(transaction, table, plan.linkUpdates());
        rewriteRetainedHeads(transaction, table, plan.headUpdates());
        purgeVersions(transaction, table, plan.removedVersions());
        purgeDirectories(transaction, table, plan.removedDirectories());

        return new Result(
                oldestVisibleThrough,
                plan.linkUpdates().size(),
                plan.headUpdates().size(),
                plan.removedVersions().size(),
                plan.removedDirectories().size(),
                versions.size() - plan.removedVersions().size(),
                directories.size() - plan.removedDirectories().size());
    }

    private static VacuumPlan plan(
            List<DirectoryEntry> directories,
            List<VersionEntry> versions,
            long oldestVisibleThrough) {
        Map<Long, VersionEntry> versionsById = indexVersions(versions);
        validateDirectories(directories);

        List<LinkUpdate> linkUpdates = new ArrayList<>();
        List<HeadUpdate> headUpdates = new ArrayList<>();
        List<VersionEntry> removedVersions = new ArrayList<>();
        List<DirectoryEntry> removedDirectories = new ArrayList<>();
        Set<Long> reachableVersions = new HashSet<>();

        for (DirectoryEntry directory : directories) {
            List<VersionEntry> chain = chainFor(
                    directory,
                    versionsById,
                    reachableVersions);
            validateChainIntervals(chain);
            List<VersionEntry> retained = retainedForHorizon(chain, oldestVisibleThrough);
            Set<Long> retainedIds = new HashSet<>();
            for (VersionEntry version : retained) {
                retainedIds.add(version.versionId());
            }
            for (VersionEntry version : chain) {
                if (!retainedIds.contains(version.versionId())) {
                    removedVersions.add(version);
                }
            }

            if (retained.isEmpty()) {
                removedDirectories.add(directory);
                continue;
            }

            for (int index = 0; index < retained.size(); index++) {
                VersionEntry current = retained.get(index);
                VersionEntry predecessor = index + 1 < retained.size()
                        ? retained.get(index + 1)
                        : null;
                long predecessorId = predecessor == null
                        ? MvccRawStoreFormat.NO_PREVIOUS_VERSION
                        : predecessor.versionId();
                if (current.previousVersionId() != predecessorId
                        || !current.previousHintMatches(predecessor)) {
                    linkUpdates.add(new LinkUpdate(current, predecessor));
                }
            }

            VersionEntry retainedHead = retained.get(0);
            if (directory.headVersionId() != retainedHead.versionId()
                    || !directory.headHintMatches(retainedHead)) {
                headUpdates.add(new HeadUpdate(directory, retainedHead));
            }
        }

        if (reachableVersions.size() != versions.size()) {
            List<Long> orphanIds = versions.stream()
                    .map(VersionEntry::versionId)
                    .filter(versionId -> !reachableVersions.contains(versionId))
                    .sorted()
                    .toList();
            throw corruption("orphan version records", orphanIds.toString());
        }

        return new VacuumPlan(
                List.copyOf(linkUpdates),
                List.copyOf(headUpdates),
                List.copyOf(removedVersions),
                List.copyOf(removedDirectories));
    }

    private static Map<Long, VersionEntry> indexVersions(List<VersionEntry> versions) {
        Map<Long, VersionEntry> versionsById = new HashMap<>();
        for (VersionEntry version : versions) {
            if (version.rowId() <= 0L || version.versionId() <= 0L
                    || version.creatorTransactionId() <= 0L) {
                throw corruption("non-positive row/version/transaction identity", version.toString());
            }
            if ((version.flags() != MvccRawStoreFormat.LIVE_FLAGS
                            && version.flags() != MvccRawStoreFormat.TOMBSTONE_FLAGS)
                    || version.beginSequence() < MvccRawStoreFormat.UNCOMMITTED_SEQUENCE
                    || version.endSequence() <= MvccRawStoreFormat.UNCOMMITTED_SEQUENCE) {
                throw corruption("invalid visibility interval or flags", version.toString());
            }
            if (version.previousVersionId() < 0L
                    || (version.previousVersionId() != MvccRawStoreFormat.NO_PREVIOUS_VERSION
                        && version.previousVersionId() >= version.versionId())) {
                throw corruption("invalid predecessor ordering", version.toString());
            }
            VersionEntry duplicate = versionsById.put(version.versionId(), version);
            if (duplicate != null) {
                throw corruption(
                        "duplicate MvccVersionId " + version.versionId(),
                        duplicate + " / " + version);
            }
        }
        return versionsById;
    }

    private static void validateDirectories(List<DirectoryEntry> directories) {
        Map<Long, DirectoryEntry> directoriesByRow = new LinkedHashMap<>();
        for (DirectoryEntry directory : directories) {
            if (directory.rowId() <= 0L || directory.headVersionId() <= 0L) {
                throw corruption("invalid directory identity", directory.toString());
            }
            DirectoryEntry duplicate = directoriesByRow.put(directory.rowId(), directory);
            if (duplicate != null) {
                throw corruption(
                        "duplicate MvccRowId directory " + directory.rowId(),
                        duplicate + " / " + directory);
            }
        }
    }

    private static List<VersionEntry> chainFor(
            DirectoryEntry directory,
            Map<Long, VersionEntry> versionsById,
            Set<Long> reachableVersions) {
        List<VersionEntry> chain = new ArrayList<>();
        Set<Long> rowVisited = new HashSet<>();
        long versionId = directory.headVersionId();
        while (versionId != MvccRawStoreFormat.NO_PREVIOUS_VERSION) {
            if (!rowVisited.add(versionId)) {
                throw corruption(
                        "cycle in logical version chain for row " + directory.rowId(),
                        Long.toString(versionId));
            }
            VersionEntry version = versionsById.get(versionId);
            if (version == null) {
                throw corruption(
                        "missing predecessor version for row " + directory.rowId(),
                        Long.toString(versionId));
            }
            if (version.rowId() != directory.rowId()) {
                throw corruption(
                        "cross-row predecessor for row " + directory.rowId(),
                        version.toString());
            }
            if (!reachableVersions.add(versionId)) {
                throw corruption(
                        "version belongs to multiple directory chains",
                        Long.toString(versionId));
            }
            chain.add(version);
            versionId = version.previousVersionId();
        }
        return List.copyOf(chain);
    }

    private static void validateChainIntervals(List<VersionEntry> chain) {
        if (chain.isEmpty()) {
            throw corruption("empty directory chain", "no head version");
        }
        if (chain.get(0).endSequence() != MvccRawStoreFormat.CURRENT_END_SEQUENCE) {
            throw corruption("head version is not current", chain.get(0).toString());
        }

        boolean committedSeen = false;
        for (int index = 0; index < chain.size(); index++) {
            VersionEntry version = chain.get(index);
            boolean uncommitted =
                    version.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE;
            if (uncommitted) {
                if (committedSeen
                        || version.endSequence() != MvccRawStoreFormat.CURRENT_END_SEQUENCE) {
                    throw corruption("invalid uncommitted chain interval", version.toString());
                }
            } else {
                committedSeen = true;
                if (version.endSequence() < version.beginSequence()) {
                    throw corruption("invalid committed visibility interval", version.toString());
                }
            }
            if (version.tombstone() && index != 0) {
                throw corruption("tombstone is not the chain head", version.toString());
            }
            if (index == 0) {
                continue;
            }

            VersionEntry newer = chain.get(index - 1);
            if (newer.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE) {
                if (version.endSequence() != MvccRawStoreFormat.CURRENT_END_SEQUENCE) {
                    throw corruption("pending predecessor is not current", version.toString());
                }
            } else if (version.endSequence() != newer.beginSequence()) {
                throw corruption("non-contiguous committed visibility intervals",
                        newer + " / " + version);
            }
            if (newer.beginSequence() != MvccRawStoreFormat.UNCOMMITTED_SEQUENCE
                    && version.beginSequence() != MvccRawStoreFormat.UNCOMMITTED_SEQUENCE
                    && version.beginSequence() > newer.beginSequence()) {
                throw corruption("commit sequence increases toward the chain tail",
                        newer + " / " + version);
            }
        }
    }

    private static List<VersionEntry> retainedForHorizon(
            List<VersionEntry> chain,
            long oldestVisibleThrough) {
        List<VersionEntry> retained = new ArrayList<>();
        VersionEntry visibleAtHorizon = null;
        for (VersionEntry version : chain) {
            if (version.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE
                    || version.beginSequence() > oldestVisibleThrough) {
                retained.add(version);
                continue;
            }
            if (visibleAtHorizon == null
                    && version.beginSequence() <= oldestVisibleThrough
                    && oldestVisibleThrough < version.endSequence()) {
                visibleAtHorizon = version;
            }
        }
        if (visibleAtHorizon != null && !visibleAtHorizon.tombstone()) {
            retained.add(visibleAtHorizon);
        }
        return List.copyOf(retained);
    }

    private static List<DirectoryEntry> readDirectories(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC metadata container is absent: " + table.metadataContainer());
        }
        List<DirectoryEntry> result = new ArrayList<>();
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 2
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    int fieldCount = page.fetchNumFieldsAtSlot(slot);
                    if (fieldCount != MvccRawStoreFormat.DIRECTORY_BASE_FIELD_COUNT
                            && fieldCount != MvccRawStoreFormat.DIRECTORY_HINT_FIELD_COUNT
                            && fieldCount != MvccRawStoreFormat.DIRECTORY_HEAD_SUMMARY_FIELD_COUNT) {
                        throw corruption(
                                "unsupported directory field count",
                                Integer.toString(fieldCount));
                    }
                    if (intField(transaction, page, slot,
                            MvccRawStoreFormat.DIRECTORY_KIND_FIELD)
                            != MvccRawStoreFormat.DIRECTORY_KIND) {
                        continue;
                    }
                    if (intField(transaction, page, slot,
                            MvccRawStoreFormat.DIRECTORY_FORMAT_VERSION)
                            != MvccRawStoreFormat.FORMAT_VERSION) {
                        throw corruption("unsupported directory format version",
                                Long.toString(page.getPageNumber()) + ':' + slot);
                    }
                    boolean hasHint = fieldCount >= MvccRawStoreFormat.DIRECTORY_HINT_FIELD_COUNT;
                    long hintPage = hasHint
                            ? longField(transaction, page, slot,
                                    MvccRawStoreFormat.DIRECTORY_HEAD_HINT_PAGE)
                            : 0L;
                    int hintRecord = hasHint
                            ? intField(transaction, page, slot,
                                    MvccRawStoreFormat.DIRECTORY_HEAD_HINT_RECORD)
                            : 0;
                    result.add(new DirectoryEntry(
                            longField(transaction, page, slot,
                                    MvccRawStoreFormat.DIRECTORY_ROW_ID),
                            longField(transaction, page, slot,
                                    MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID),
                            hintPage,
                            hintRecord,
                            hasHint,
                            fieldCount == MvccRawStoreFormat.DIRECTORY_HEAD_SUMMARY_FIELD_COUNT,
                            page.getRecordHandleAtSlot(slot)));
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            return List.copyOf(result);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static List<VersionEntry> readVersions(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.versionContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC version container is absent: " + table.versionContainer());
        }
        List<VersionEntry> result = new ArrayList<>();
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
                    int fieldCount = page.fetchNumFieldsAtSlot(slot);
                    int baseFieldCount = MvccRawStoreFormat.versionBaseFieldCount(table.columnCount());
                    int hintFieldCount = MvccRawStoreFormat.versionHintFieldCount(table.columnCount());
                    if (fieldCount != baseFieldCount && fieldCount != hintFieldCount) {
                        throw corruption(
                                "unsupported version field count",
                                Integer.toString(fieldCount));
                    }
                    if (intField(transaction, page, slot,
                            MvccRawStoreFormat.VERSION_KIND_FIELD)
                            != MvccRawStoreFormat.VERSION_KIND) {
                        continue;
                    }
                    if (intField(transaction, page, slot,
                            MvccRawStoreFormat.VERSION_FORMAT_VERSION)
                            != MvccRawStoreFormat.FORMAT_VERSION) {
                        throw corruption("unsupported version format version",
                                Long.toString(page.getPageNumber()) + ':' + slot);
                    }
                    boolean hasHint = fieldCount == hintFieldCount;
                    result.add(new VersionEntry(
                            longField(transaction, page, slot,
                                    MvccRawStoreFormat.VERSION_ROW_ID),
                            longField(transaction, page, slot,
                                    MvccRawStoreFormat.VERSION_ID),
                            longField(transaction, page, slot,
                                    MvccRawStoreFormat.VERSION_CREATOR_TRANSACTION_ID),
                            longField(transaction, page, slot,
                                    MvccRawStoreFormat.VERSION_BEGIN_SEQUENCE),
                            longField(transaction, page, slot,
                                    MvccRawStoreFormat.VERSION_END_SEQUENCE),
                            longField(transaction, page, slot,
                                    MvccRawStoreFormat.VERSION_PREVIOUS_VERSION_ID),
                            intField(transaction, page, slot,
                                    MvccRawStoreFormat.VERSION_FLAGS),
                            hasHint
                                    ? longField(transaction, page, slot,
                                            MvccRawStoreFormat.versionHintPageField(
                                                    table.columnCount()))
                                    : 0L,
                            hasHint
                                    ? intField(transaction, page, slot,
                                            MvccRawStoreFormat.versionHintRecordField(
                                                    table.columnCount()))
                                    : 0,
                            hasHint,
                            page.getRecordHandleAtSlot(slot)));
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            return List.copyOf(result);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static void rewriteRetainedLinks(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            List<LinkUpdate> updates) throws StandardException {
        if (updates.isEmpty()) {
            return;
        }
        ContainerHandle container = transaction.openContainer(
                table.versionContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC version container is absent: " + table.versionContainer());
        }
        try {
            for (LinkUpdate update : updates) {
                Page page = null;
                try {
                    page = container.getPage(update.version().handle().getPageNumber());
                    int slot = requireVersionSlot(transaction, page, update.version());
                    VersionEntry predecessor = update.predecessor();
                    page.updateFieldAtSlot(
                            slot,
                            MvccRawStoreFormat.VERSION_PREVIOUS_VERSION_ID,
                            MvccRawStoreFormat.longValue(
                                    transaction,
                                    predecessor == null
                                            ? MvccRawStoreFormat.NO_PREVIOUS_VERSION
                                            : predecessor.versionId()),
                            null);
                    if (update.version().hasHint()) {
                        page.updateFieldAtSlot(
                                slot,
                                MvccRawStoreFormat.versionHintPageField(table.columnCount()),
                                MvccRawStoreFormat.longValue(
                                        transaction,
                                        predecessor == null
                                            ? 0L
                                            : predecessor.handle().getPageNumber()),
                                null);
                        page.updateFieldAtSlot(
                                slot,
                                MvccRawStoreFormat.versionHintRecordField(table.columnCount()),
                                MvccRawStoreFormat.intValue(
                                        transaction,
                                        predecessor == null
                                            ? 0
                                            : predecessor.handle().getId()),
                                null);
                    }
                } finally {
                    if (page != null) {
                        page.unlatch();
                    }
                }
            }
        } finally {
            container.close();
        }
    }

    private static void rewriteRetainedHeads(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            List<HeadUpdate> updates) throws StandardException {
        if (updates.isEmpty()) {
            return;
        }
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC metadata container is absent: " + table.metadataContainer());
        }
        try {
            for (HeadUpdate update : updates) {
                Page page = null;
                try {
                    page = container.getPage(update.directory().handle().getPageNumber());
                    int slot = requireDirectorySlot(transaction, page, update.directory());
                    page.updateFieldAtSlot(
                            slot,
                            MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID,
                            MvccRawStoreFormat.longValue(
                                    transaction,
                                    update.head().versionId()),
                            null);
                    if (update.directory().hasHint()) {
                        page.updateFieldAtSlot(
                                slot,
                                MvccRawStoreFormat.DIRECTORY_HEAD_HINT_PAGE,
                                MvccRawStoreFormat.longValue(
                                        transaction,
                                        update.head().handle().getPageNumber()),
                                null);
                        page.updateFieldAtSlot(
                                slot,
                                MvccRawStoreFormat.DIRECTORY_HEAD_HINT_RECORD,
                                MvccRawStoreFormat.intValue(
                                        transaction,
                                        update.head().handle().getId()),
                                null);
                    }
                    if (update.directory().hasSummary()) {
                        page.updateFieldAtSlot(
                                slot,
                                MvccRawStoreFormat.DIRECTORY_HEAD_CREATOR_TRANSACTION_ID,
                                MvccRawStoreFormat.longValue(
                                        transaction,
                                        update.head().creatorTransactionId()),
                                null);
                        page.updateFieldAtSlot(
                                slot,
                                MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE,
                                MvccRawStoreFormat.longValue(
                                        transaction,
                                        update.head().beginSequence()),
                                null);
                        page.updateFieldAtSlot(
                                slot,
                                MvccRawStoreFormat.DIRECTORY_HEAD_FLAGS,
                                MvccRawStoreFormat.intValue(
                                        transaction,
                                        update.head().flags()),
                                null);
                    }
                } finally {
                    if (page != null) {
                        page.unlatch();
                    }
                }
            }
        } finally {
            container.close();
        }
    }

    private static void purgeVersions(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            List<VersionEntry> removed) throws StandardException {
        purgeByPage(
                transaction,
                table.versionContainer(),
                removed.stream()
                        .map(version -> new PurgeTarget(
                                version.handle(),
                                version.rowId(),
                                version.versionId(),
                                MvccRawStoreFormat.VERSION_KIND_FIELD,
                                MvccRawStoreFormat.VERSION_FORMAT_VERSION,
                                MvccRawStoreFormat.VERSION_ROW_ID,
                                MvccRawStoreFormat.VERSION_ID,
                                MvccRawStoreFormat.VERSION_KIND))
                        .toList());
    }

    private static void purgeDirectories(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            List<DirectoryEntry> removed) throws StandardException {
        purgeByPage(
                transaction,
                table.metadataContainer(),
                removed.stream()
                        .map(directory -> new PurgeTarget(
                                directory.handle(),
                                directory.rowId(),
                                directory.headVersionId(),
                                MvccRawStoreFormat.DIRECTORY_KIND_FIELD,
                                MvccRawStoreFormat.DIRECTORY_FORMAT_VERSION,
                                MvccRawStoreFormat.DIRECTORY_ROW_ID,
                                MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID,
                                MvccRawStoreFormat.DIRECTORY_KIND))
                        .toList());
    }

    private static void purgeByPage(
            Transaction transaction,
            org.apache.derby.iapi.store.raw.ContainerKey key,
            List<PurgeTarget> targets) throws StandardException {
        if (targets.isEmpty()) {
            return;
        }
        ContainerHandle container = transaction.openContainer(
                key,
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw new IllegalStateException("RawStore MVCC purge container is absent: " + key);
        }
        Map<Long, List<PurgeTarget>> byPage = new LinkedHashMap<>();
        targets.stream()
                .sorted(Comparator.comparingLong(target -> target.handle().getPageNumber()))
                .forEach(target -> byPage.computeIfAbsent(
                        target.handle().getPageNumber(),
                        ignored -> new ArrayList<>()).add(target));
        try {
            for (Map.Entry<Long, List<PurgeTarget>> pageTargets : byPage.entrySet()) {
                Page page = null;
                try {
                    page = container.getPage(pageTargets.getKey());
                    if (page == null) {
                        throw corruption("purge page disappeared", pageTargets.getKey().toString());
                    }
                    List<SlotTarget> slots = new ArrayList<>();
                    for (PurgeTarget target : pageTargets.getValue()) {
                        int slot = page.getSlotNumber(target.handle());
                        if (slot < Page.FIRST_SLOT_NUMBER || page.isDeletedAtSlot(slot)) {
                            throw corruption("purge target disappeared", target.toString());
                        }
                        if (intField(transaction, page, slot, target.kindField()) != target.kind()
                                || intField(transaction, page, slot, target.formatVersionField())
                                        != MvccRawStoreFormat.FORMAT_VERSION
                                || longField(transaction, page, slot, target.rowIdField())
                                        != target.rowId()
                                || longField(transaction, page, slot, target.identityField())
                                        != target.identity()) {
                            throw corruption("purge target identity changed", target.toString());
                        }
                        slots.add(new SlotTarget(slot, target));
                    }
                    slots.sort(Comparator.comparingInt(SlotTarget::slot).reversed());
                    for (SlotTarget slot : slots) {
                        page.purgeAtSlot(slot.slot(), 1, true);
                    }
                } finally {
                    if (page != null) {
                        page.unlatch();
                    }
                }
            }
        } finally {
            container.close();
        }
    }

    private static int requireVersionSlot(
            Transaction transaction,
            Page page,
            VersionEntry version) throws StandardException {
        if (page == null) {
            throw corruption("version page disappeared", version.toString());
        }
        int slot = page.getSlotNumber(version.handle());
        if (slot < Page.FIRST_SLOT_NUMBER
                || page.isDeletedAtSlot(slot)
                || intField(transaction, page, slot,
                        MvccRawStoreFormat.VERSION_KIND_FIELD)
                        != MvccRawStoreFormat.VERSION_KIND
                || intField(transaction, page, slot,
                        MvccRawStoreFormat.VERSION_FORMAT_VERSION)
                        != MvccRawStoreFormat.FORMAT_VERSION
                || longField(transaction, page, slot,
                        MvccRawStoreFormat.VERSION_ROW_ID) != version.rowId()
                || longField(transaction, page, slot,
                        MvccRawStoreFormat.VERSION_ID) != version.versionId()) {
            throw corruption("version identity changed during vacuum", version.toString());
        }
        return slot;
    }

    private static int requireDirectorySlot(
            Transaction transaction,
            Page page,
            DirectoryEntry directory) throws StandardException {
        if (page == null) {
            throw corruption("directory page disappeared", directory.toString());
        }
        int slot = page.getSlotNumber(directory.handle());
        if (slot < Page.FIRST_SLOT_NUMBER
                || page.isDeletedAtSlot(slot)
                || intField(transaction, page, slot,
                        MvccRawStoreFormat.DIRECTORY_KIND_FIELD)
                        != MvccRawStoreFormat.DIRECTORY_KIND
                || intField(transaction, page, slot,
                        MvccRawStoreFormat.DIRECTORY_FORMAT_VERSION)
                        != MvccRawStoreFormat.FORMAT_VERSION
                || longField(transaction, page, slot,
                        MvccRawStoreFormat.DIRECTORY_ROW_ID) != directory.rowId()) {
            throw corruption("directory identity changed during vacuum", directory.toString());
        }
        return slot;
    }

    private static long longField(
            Transaction transaction,
            Page page,
            int slot,
            int field) throws StandardException {
        StoreDataValue value = MvccRawStoreFormat.longValue(transaction, 0L);
        page.fetchFieldFromSlot(slot, field, value);
        return StoreTypeUtil.getLong(value);
    }

    private static int intField(
            Transaction transaction,
            Page page,
            int slot,
            int field) throws StandardException {
        StoreDataValue value = MvccRawStoreFormat.intValue(transaction, 0);
        page.fetchFieldFromSlot(slot, field, value);
        return Math.toIntExact(StoreTypeUtil.getLong(value));
    }

    private static IllegalStateException corruption(String reason, String detail) {
        return new IllegalStateException(
                "RawStore MVCC vacuum rejected corrupt logical state: "
                        + reason + " [" + detail + ']');
    }

    record Result(
            long oldestVisibleThrough,
            int relinkedVersions,
            int refreshedDirectories,
            int removedVersions,
            int removedLogicalRows,
            int remainingVersions,
            int remainingLogicalRows) {
        boolean mutated() {
            return relinkedVersions > 0
                    || refreshedDirectories > 0
                    || removedVersions > 0
                    || removedLogicalRows > 0;
        }

        boolean requiresOrderedIndexReplacement() {
            return removedVersions > 0 || removedLogicalRows > 0;
        }
    }

    private record VacuumPlan(
            List<LinkUpdate> linkUpdates,
            List<HeadUpdate> headUpdates,
            List<VersionEntry> removedVersions,
            List<DirectoryEntry> removedDirectories) {
        boolean mutated() {
            return !linkUpdates.isEmpty()
                    || !headUpdates.isEmpty()
                    || !removedVersions.isEmpty()
                    || !removedDirectories.isEmpty();
        }
    }

    private record LinkUpdate(VersionEntry version, VersionEntry predecessor) {
    }

    private record HeadUpdate(DirectoryEntry directory, VersionEntry head) {
    }

    private record DirectoryEntry(
            long rowId,
            long headVersionId,
            long headHintPage,
            int headHintRecord,
            boolean hasHint,
            boolean hasSummary,
            RecordHandle handle) {
        boolean headHintMatches(VersionEntry head) {
            return !hasHint
                    || (headHintPage == head.handle().getPageNumber()
                        && headHintRecord == head.handle().getId());
        }
    }

    private record VersionEntry(
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            long endSequence,
            long previousVersionId,
            int flags,
            long previousHintPage,
            int previousHintRecord,
            boolean hasHint,
            RecordHandle handle) {
        boolean tombstone() {
            return (flags & MvccRawStoreFormat.TOMBSTONE_FLAGS) != 0;
        }

        boolean previousHintMatches(VersionEntry predecessor) {
            if (!hasHint) {
                return true;
            }
            if (predecessor == null) {
                return previousHintPage == 0L && previousHintRecord == 0;
            }
            return previousHintPage == predecessor.handle().getPageNumber()
                    && previousHintRecord == predecessor.handle().getId();
        }
    }

    private record PurgeTarget(
            RecordHandle handle,
            long rowId,
            long identity,
            int kindField,
            int formatVersionField,
            int rowIdField,
            int identityField,
            int kind) {
    }

    private record SlotTarget(int slot, PurgeTarget target) {
    }
}
