package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;

/** S6 smoke for path-free DelosPageVolume rewrite lifecycle ownership. */
public final class PageBackedMvccRewriteLifecycleSmoke {
    private PageBackedMvccRewriteLifecycleSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("delosdb-s6-rewrite-lifecycle-");
        Path file = directory.resolve("versions.mvccp");
        MvccVersionRecord first = record(1L, 10L, "alpha");
        MvccVersionRecord second = record(2L, 20L, "beta");

        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(file)) {
            MvccVersionLocator locator = store.append(first);
            require(locator.pageId().value() == 0L, "first append should land on page 0");
            require(locator.slotId() == 0, "first append should land in slot 0");
            List<PageBackedMvccTableStore.StoredVersionRecord> rewritten = store.rewrite(List.of(second));
            require(rewritten.size() == 1, "rewrite should retain exactly one record");
            require(second.equals(rewritten.get(0).record()), "rewrite should return retained replacement record");
            require(store.loadAll().size() == 1, "store should reload from replacement file after rewrite");
        }

        try (PageBackedMvccTableStore reopened = PageBackedMvccTableStore.open(file)) {
            List<PageBackedMvccTableStore.StoredVersionRecord> loaded = reopened.loadAll();
            require(loaded.size() == 1, "reopened store should contain exactly one record");
            require(second.equals(loaded.get(0).record()), "reopened store should contain rewritten record");
        }

        System.out.println("storage-phase-s6-rewrite-lifecycle-boundary: PASS");
    }

    private static MvccVersionRecord record(long rowId, long versionId, String payload) {
        return new MvccVersionRecord(
                new MvccTupleHeader(
                        new MvccRowId(rowId),
                        new MvccVersionId(versionId),
                        MvccVersionId.NONE,
                        new MvccTransactionId(versionId),
                        MvccTransactionId.NONE,
                        new MvccCommitSequence(versionId),
                        0),
                payload.getBytes(StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
