package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;

/** In-memory row directory rebuilt from durable page-backed version records. */
public final class MvccRowDirectory {
    private final Map<String, RowState> rowsByKey = new LinkedHashMap<>();
    private long nextRowId = 1L;
    private long nextVersionId = 1L;

    public static MvccRowDirectory fromStoredRecords(Collection<PageBackedMvccTableStore.StoredVersionRecord> storedRecords) {
        Objects.requireNonNull(storedRecords, "storedRecords");
        MvccRowDirectory directory = new MvccRowDirectory();
        for (PageBackedMvccTableStore.StoredVersionRecord stored : storedRecords) {
            directory.addStored(stored);
        }
        directory.sortAllNewestFirst();
        return directory;
    }

    public synchronized MvccRowId nextRowId() {
        return new MvccRowId(nextRowId++);
    }

    public synchronized MvccVersionId nextVersionId() {
        return new MvccVersionId(nextVersionId++);
    }

    public synchronized Optional<MvccRowId> rowIdForKey(String key) {
        RowState row = rowsByKey.get(MvccRowPayload.requireKey(key));
        return row == null ? Optional.empty() : Optional.of(row.rowId());
    }

    public synchronized Optional<MvccVersionId> newestVersionIdForKey(String key) {
        RowState row = rowsByKey.get(MvccRowPayload.requireKey(key));
        return row == null ? Optional.empty() : row.newestVersionId();
    }

    public synchronized Optional<MvccVersionLocator> newestVersionLocatorForKey(String key) {
        RowState row = rowsByKey.get(MvccRowPayload.requireKey(key));
        return row == null ? Optional.empty() : row.newestVersionLocator();
    }

    public synchronized Optional<MvccRowPayload> read(String key, MvccCommitSequence snapshotSequence) {
        RowState row = rowsByKey.get(MvccRowPayload.requireKey(key));
        return row == null ? Optional.empty() : row.visiblePayload(snapshotSequence);
    }

    public synchronized Optional<MvccRowPayload> readByRowId(MvccRowId rowId, MvccCommitSequence snapshotSequence) {
        Objects.requireNonNull(rowId, "rowId");
        Objects.requireNonNull(snapshotSequence, "snapshotSequence");
        for (RowState row : rowsByKey.values()) {
            if (row.rowId().equals(rowId)) {
                return row.visiblePayload(snapshotSequence);
            }
        }
        return Optional.empty();
    }

    public synchronized boolean containsVersion(MvccRowId rowId, MvccVersionId versionId) {
        Objects.requireNonNull(rowId, "rowId");
        Objects.requireNonNull(versionId, "versionId");
        for (RowState row : rowsByKey.values()) {
            if (row.rowId().equals(rowId) && row.containsVersion(versionId)) {
                return true;
            }
        }
        return false;
    }

    public synchronized VacuumSelection selectVacuum(MvccCommitSequence oldestVisibleThrough) {
        Objects.requireNonNull(oldestVisibleThrough, "oldestVisibleThrough");
        List<StoredVersion> retained = new ArrayList<>();
        int removedVersions = 0;
        int removedLogicalRows = 0;
        for (RowState row : rowsByKey.values()) {
            List<StoredVersion> rowRetained = row.retainedForVacuum(oldestVisibleThrough);
            retained.addAll(rowRetained);
            removedVersions += row.versionCount() - rowRetained.size();
            if (row.versionCount() > 0 && rowRetained.isEmpty()) {
                removedLogicalRows++;
            }
        }
        retained.sort(Comparator.comparingLong(
                version -> version.record().header().versionId().value()));
        List<MvccVersionRecord> retainedRecords = new ArrayList<>();
        for (StoredVersion version : retained) {
            retainedRecords.add(version.record());
        }
        return new VacuumSelection(retainedRecords, removedVersions, removedLogicalRows);
    }

    public synchronized int physicalVersionCount(String key) {
        RowState row = rowsByKey.get(MvccRowPayload.requireKey(key));
        return row == null ? 0 : row.versionCount();
    }

    public synchronized List<MvccRowPayload> visiblePayloads(MvccCommitSequence snapshotSequence) {
        Objects.requireNonNull(snapshotSequence, "snapshotSequence");
        List<MvccRowPayload> payloads = new ArrayList<>();
        for (RowState row : rowsByKey.values()) {
            row.visiblePayload(snapshotSequence).ifPresent(payloads::add);
        }
        return List.copyOf(payloads);
    }

    public synchronized int physicalVersionCount() {
        int total = 0;
        for (RowState row : rowsByKey.values()) {
            total += row.versionCount();
        }
        return total;
    }

    public synchronized int logicalRowCount() {
        return rowsByKey.size();
    }

    synchronized void addNewCommitted(String key, MvccRowId rowId, StoredVersion version) {
        RowState row = rowsByKey.computeIfAbsent(MvccRowPayload.requireKey(key), ignored -> new RowState(rowId));
        if (!row.rowId().equals(rowId)) {
            throw new IllegalStateException("row key " + key + " maps to " + row.rowId() + ", not " + rowId);
        }
        row.addNewest(version);
        advanceIds(version.record());
    }

    private void addStored(PageBackedMvccTableStore.StoredVersionRecord stored) {
        MvccVersionRecord record = stored.record();
        MvccRowPayload payload = MvccRowPayloadCodec.decode(record.payload());
        RowState row = rowsByKey.computeIfAbsent(payload.key(), ignored -> new RowState(record.header().rowId()));
        if (!row.rowId().equals(record.header().rowId())) {
            throw new IllegalStateException("durable key " + payload.key() + " has multiple row ids: "
                    + row.rowId() + " and " + record.header().rowId());
        }
        row.addUnsorted(new StoredVersion(stored.locator(), record, payload));
        advanceIds(record);
    }

    private void sortAllNewestFirst() {
        for (RowState row : rowsByKey.values()) {
            row.sortNewestFirst();
        }
    }

    private void advanceIds(MvccVersionRecord record) {
        nextRowId = Math.max(nextRowId, record.header().rowId().value() + 1L);
        nextVersionId = Math.max(nextVersionId, record.header().versionId().value() + 1L);
    }

    public record VacuumSelection(
            List<MvccVersionRecord> retainedRecords,
            int removedVersions,
            int removedLogicalRows) {
        public VacuumSelection {
            retainedRecords = List.copyOf(Objects.requireNonNull(retainedRecords, "retainedRecords"));
            if (removedVersions < 0 || removedLogicalRows < 0) {
                throw new IllegalArgumentException("vacuum counts must not be negative");
            }
        }
    }

    public record StoredVersion(MvccVersionLocator locator, MvccVersionRecord record, MvccRowPayload payload) {
        public StoredVersion {
            locator = Objects.requireNonNull(locator, "locator");
            record = Objects.requireNonNull(record, "record");
            payload = Objects.requireNonNull(payload, "payload");
        }

        boolean isCommittedVisibleTo(MvccCommitSequence snapshotSequence) {
            MvccCommitSequence commitSequence = record.header().commitSequence();
            return !commitSequence.equals(MvccCommitSequence.NONE)
                    && commitSequence.isAtOrBefore(snapshotSequence);
        }
    }

    private static final class RowState {
        private final MvccRowId rowId;
        private final List<StoredVersion> newestFirst = new ArrayList<>();

        private RowState(MvccRowId rowId) {
            this.rowId = Objects.requireNonNull(rowId, "rowId");
        }

        private MvccRowId rowId() {
            return rowId;
        }

        private void addNewest(StoredVersion version) {
            newestFirst.add(0, Objects.requireNonNull(version, "version"));
        }

        private void addUnsorted(StoredVersion version) {
            newestFirst.add(Objects.requireNonNull(version, "version"));
        }

        private void sortNewestFirst() {
            newestFirst.sort(Comparator.comparingLong(
                    (StoredVersion version) -> version.record().header().versionId().value()).reversed());
        }

        private Optional<MvccVersionId> newestVersionId() {
            return newestFirst.isEmpty()
                    ? Optional.empty()
                    : Optional.of(newestFirst.get(0).record().header().versionId());
        }

        private Optional<MvccVersionLocator> newestVersionLocator() {
            return newestFirst.isEmpty()
                    ? Optional.empty()
                    : Optional.of(newestFirst.get(0).locator());
        }

        private Optional<MvccRowPayload> visiblePayload(MvccCommitSequence snapshotSequence) {
            Objects.requireNonNull(snapshotSequence, "snapshotSequence");
            for (StoredVersion version : newestFirst) {
                if (version.isCommittedVisibleTo(snapshotSequence)) {
                    return version.record().header().isTombstone()
                            ? Optional.empty()
                            : Optional.of(version.payload());
                }
            }
            return Optional.empty();
        }

        private boolean containsVersion(MvccVersionId versionId) {
            for (StoredVersion version : newestFirst) {
                if (version.record().header().versionId().equals(versionId)) {
                    return true;
                }
            }
            return false;
        }

        private List<StoredVersion> retainedForVacuum(MvccCommitSequence oldestVisibleThrough) {
            List<StoredVersion> retained = new ArrayList<>();
            StoredVersion newestVisibleAtHorizon = null;
            for (StoredVersion version : newestFirst) {
                MvccCommitSequence commitSequence = version.record().header().commitSequence();
                if (commitSequence.equals(MvccCommitSequence.NONE)
                        || !commitSequence.isAtOrBefore(oldestVisibleThrough)) {
                    retained.add(version);
                    continue;
                }
                if (newestVisibleAtHorizon == null) {
                    newestVisibleAtHorizon = version;
                }
            }
            if (newestVisibleAtHorizon != null
                    && !newestVisibleAtHorizon.record().header().isTombstone()) {
                retained.add(newestVisibleAtHorizon);
            }
            retained.sort(Comparator.comparingLong(
                    (StoredVersion version) -> version.record().header().versionId().value()).reversed());
            return retained;
        }

        private int versionCount() {
            return newestFirst.size();
        }
    }
}
