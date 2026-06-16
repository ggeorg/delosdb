package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;

/** Applies committed page-mutation log records into a page-backed MVCC table store. */
public final class MvccPageRecoveryRunner {
    private final MvccPageMutationLog log;
    private final PageBackedMvccTableStore store;

    public MvccPageRecoveryRunner(MvccPageMutationLog log, PageBackedMvccTableStore store) {
        this.log = Objects.requireNonNull(log, "log");
        this.store = Objects.requireNonNull(store, "store");
    }

    public RecoveryResult recover() throws IOException {
        Set<MvccVersionId> existingVersionIds = new HashSet<>();
        for (PageBackedMvccTableStore.StoredVersionRecord stored : store.loadAll()) {
            existingVersionIds.add(stored.record().header().versionId());
        }

        int applied = 0;
        int skipped = 0;
        for (MvccVersionRecord record : log.recoverCommittedRecords()) {
            if (existingVersionIds.add(record.header().versionId())) {
                store.append(record);
                applied++;
            } else {
                skipped++;
            }
        }
        return new RecoveryResult(applied, skipped);
    }

    public record RecoveryResult(int appliedRecords, int skippedExistingRecords) {
        public RecoveryResult {
            if (appliedRecords < 0 || skippedExistingRecords < 0) {
                throw new IllegalArgumentException("recovery counts must not be negative");
            }
        }
    }
}
