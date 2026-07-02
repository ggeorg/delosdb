package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;

/**
 * Consistency checker for page-backed MVCC state.
 *
 * <p>The checker deliberately treats durable pages as the source of version
 * records and the row-directory sidecar as a rebuildable locator cache. A clean
 * check proves that every durable row has one stable row id, a closed previous
 * pointer chain, one row-directory head, and a head locator that points to the
 * newest version record. If this check fails during boot, recovery should fail
 * loudly instead of silently accepting a partially recovered MVCC table.</p>
 */
public final class MvccDurableConsistencyCheck {
    private MvccDurableConsistencyCheck() {
    }

    public static Result check(PageBackedMvccTableStore store, MvccRowDirectoryStore rowDirectory)
            throws IOException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(rowDirectory, "rowDirectory");
        Map<MvccRowId, MvccRowDirectoryStore.RowHeadRecord> durableHeads = rowDirectory.recoverHeads();
        List<String> pageRecordErrors = store.pageRecordConsistencyErrors();
        List<String> reusablePageErrors = store.reusablePageConsistencyErrors();
        List<String> freeSpaceMapErrors = store.freeSpaceMapConsistencyErrors();
        if (!pageRecordErrors.isEmpty()) {
            List<String> errors = new ArrayList<>(pageRecordErrors);
            errors.addAll(reusablePageErrors);
            errors.addAll(freeSpaceMapErrors);
            return new Result(0, 0, durableHeads.size(), errors);
        }

        Result result = check(store.loadAll(), durableHeads);
        if (reusablePageErrors.isEmpty() && freeSpaceMapErrors.isEmpty()) {
            return result;
        }
        List<String> errors = new ArrayList<>(result.errors());
        errors.addAll(reusablePageErrors);
        errors.addAll(freeSpaceMapErrors);
        return new Result(result.physicalVersions(), result.logicalRows(), result.durableHeads(), errors);
    }

    public static Result check(
            Collection<PageBackedMvccTableStore.StoredVersionRecord> storedRecords,
            Map<MvccRowId, MvccRowDirectoryStore.RowHeadRecord> durableHeads) {
        Objects.requireNonNull(storedRecords, "storedRecords");
        Objects.requireNonNull(durableHeads, "durableHeads");

        List<String> errors = new ArrayList<>();
        Map<String, RowAccumulator> rowsByKey = new LinkedHashMap<>();
        Map<MvccRowId, String> keysByRowId = new LinkedHashMap<>();
        Set<MvccVersionId> seenVersionIds = new LinkedHashSet<>();
        int versionCount = 0;

        for (PageBackedMvccTableStore.StoredVersionRecord stored : storedRecords) {
            versionCount++;
            if (stored == null) {
                errors.add("stored version record is null");
                continue;
            }
            MvccVersionRecord record = stored.record();
            if (record == null) {
                errors.add("stored record at " + stored.locator() + " has null MVCC version");
                continue;
            }
            MvccTupleHeader header = record.header();
            if (header.rowId().isNone()) {
                errors.add("version " + header.versionId() + " uses row:none");
            }
            if (header.versionId().isNone()) {
                errors.add("row " + header.rowId() + " uses version:none as a physical version");
            } else if (!seenVersionIds.add(header.versionId())) {
                errors.add("duplicate table-wide version id " + header.versionId());
            }

            MvccRowPayload payload;
            try {
                payload = MvccRowPayloadCodec.decode(record.payload());
            } catch (RuntimeException e) {
                errors.add("version " + header.versionId() + " has invalid payload: " + e.getMessage());
                continue;
            }

            String previousKey = keysByRowId.putIfAbsent(header.rowId(), payload.key());
            if (previousKey != null && !previousKey.equals(payload.key())) {
                errors.add("row " + header.rowId() + " maps to multiple keys: "
                        + previousKey + " and " + payload.key());
            }

            RowAccumulator row = rowsByKey.computeIfAbsent(
                    payload.key(), ignored -> new RowAccumulator(payload.key(), header.rowId()));
            if (!row.rowId.equals(header.rowId())) {
                errors.add("key " + payload.key() + " maps to multiple row ids: "
                        + row.rowId + " and " + header.rowId());
            }
            row.add(new PageVersion(stored, record, payload));
        }

        for (RowAccumulator row : rowsByKey.values()) {
            row.validate(errors);
            validateHead(row, durableHeads.get(row.rowId), errors);
        }

        for (Map.Entry<MvccRowId, MvccRowDirectoryStore.RowHeadRecord> entry : durableHeads.entrySet()) {
            String key = keysByRowId.get(entry.getKey());
            if (key == null) {
                errors.add("row-directory head exists for missing page row " + entry.getKey());
            } else if (!Objects.equals(key, entry.getValue().key())) {
                errors.add("row-directory head for " + entry.getKey() + " uses key "
                        + entry.getValue().key() + " but page records use " + key);
            }
        }

        return new Result(versionCount, rowsByKey.size(), durableHeads.size(), List.copyOf(errors));
    }

    private static void validateHead(
            RowAccumulator row,
            MvccRowDirectoryStore.RowHeadRecord head,
            List<String> errors) {
        PageVersion newest = row.newest();
        if (newest == null) {
            return;
        }
        if (head == null) {
            errors.add("row-directory head is missing for " + row.rowId + " key " + row.key);
            return;
        }
        MvccTupleHeader newestHeader = newest.record.header();
        if (!head.key().equals(row.key)) {
            errors.add("row-directory head for " + row.rowId + " has key " + head.key()
                    + " but page records use " + row.key);
        }
        if (!head.headVersionId().equals(newestHeader.versionId())) {
            errors.add("row-directory head for " + row.rowId + " points to "
                    + head.headVersionId() + " but newest page version is " + newestHeader.versionId());
        }
        if (!head.previousVersionId().equals(newestHeader.previousVersionId())) {
            errors.add("row-directory head for " + row.rowId + " stores previous "
                    + head.previousVersionId() + " but newest page version stores "
                    + newestHeader.previousVersionId());
        }
        if (!head.headLocator().equals(newest.stored.locator())) {
            errors.add("row-directory head for " + row.rowId + " points to locator "
                    + head.headLocator() + " but newest page locator is " + newest.stored.locator());
        }
        if (head.tombstone() != newestHeader.isTombstone()) {
            errors.add("row-directory head for " + row.rowId + " tombstone=" + head.tombstone()
                    + " but newest page version tombstone=" + newestHeader.isTombstone());
        }
    }

    private static final class RowAccumulator {
        private final String key;
        private final MvccRowId rowId;
        private final List<PageVersion> versions = new ArrayList<>();

        private RowAccumulator(String key, MvccRowId rowId) {
            this.key = Objects.requireNonNull(key, "key");
            this.rowId = Objects.requireNonNull(rowId, "rowId");
        }

        private void add(PageVersion version) {
            versions.add(Objects.requireNonNull(version, "version"));
        }

        private PageVersion newest() {
            return versions.stream()
                    .max(Comparator.comparing(version -> version.record.header().versionId()))
                    .orElse(null);
        }

        private void validate(List<String> errors) {
            Map<MvccVersionId, PageVersion> byVersion = new LinkedHashMap<>();
            for (PageVersion version : versions) {
                MvccVersionId versionId = version.record.header().versionId();
                PageVersion previous = byVersion.putIfAbsent(versionId, version);
                if (previous != null) {
                    errors.add("row " + rowId + " has duplicate version id " + versionId);
                }
                if (!version.payload.key().equals(key)) {
                    errors.add("row " + rowId + " has payload key " + version.payload.key()
                            + " inside key bucket " + key);
                }
            }

            int roots = 0;
            for (PageVersion version : versions) {
                MvccVersionId previousVersionId = version.record.header().previousVersionId();
                if (previousVersionId.isNone()) {
                    roots++;
                } else if (!byVersion.containsKey(previousVersionId)) {
                    errors.add("row " + rowId + " version " + version.record.header().versionId()
                            + " points to missing previous version " + previousVersionId);
                }
            }
            if (!versions.isEmpty() && roots != 1) {
                errors.add("row " + rowId + " must have exactly one version-chain root but has " + roots);
            }

            PageVersion newest = newest();
            if (newest != null) {
                validateClosedChainFrom(newest, byVersion, errors);
            }
        }

        private void validateClosedChainFrom(
                PageVersion newest,
                Map<MvccVersionId, PageVersion> byVersion,
                List<String> errors) {
            Set<MvccVersionId> visited = new LinkedHashSet<>();
            PageVersion current = newest;
            while (current != null) {
                MvccVersionId currentId = current.record.header().versionId();
                if (!visited.add(currentId)) {
                    errors.add("row " + rowId + " version chain contains a cycle at " + currentId);
                    return;
                }
                MvccVersionId previous = current.record.header().previousVersionId();
                current = previous.isNone() ? null : byVersion.get(previous);
            }
            if (visited.size() != versions.size()) {
                errors.add("row " + rowId + " newest chain reaches " + visited.size()
                        + " of " + versions.size() + " durable versions");
            }
        }
    }

    private record PageVersion(
            PageBackedMvccTableStore.StoredVersionRecord stored,
            MvccVersionRecord record,
            MvccRowPayload payload) {
        private PageVersion {
            stored = Objects.requireNonNull(stored, "stored");
            record = Objects.requireNonNull(record, "record");
            payload = Objects.requireNonNull(payload, "payload");
        }
    }

    public record Result(int physicalVersions, int logicalRows, int durableHeads, List<String> errors) {
        public Result {
            errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
            if (physicalVersions < 0 || logicalRows < 0 || durableHeads < 0) {
                throw new IllegalArgumentException("MVCC consistency counts must not be negative");
            }
        }

        public boolean valid() {
            return errors.isEmpty();
        }

        public void assertValid() {
            if (!valid()) {
                throw new IllegalStateException("Invalid durable MVCC state: " + String.join("; ", errors));
            }
        }
    }
}
