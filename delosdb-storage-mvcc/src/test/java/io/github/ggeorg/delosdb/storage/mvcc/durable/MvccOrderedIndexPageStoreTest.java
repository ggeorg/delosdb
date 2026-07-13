package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactories;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactory;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Focused contract tests for durable MVCC ordered-index key envelopes. */
final class MvccOrderedIndexPageStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void legacyRawKeysThatLookTypedRequireFallbackForTypedLookups() throws Exception {
        try (MvccOrderedIndexPageStore store = MvccOrderedIndexPageStore.open(tempDir.resolve("ordered-index.pages"))) {
            store.rewrite(List.of(
                    new MvccOrderedIndexPageStore.Entry(0, "I|10", 1L),
                    new MvccOrderedIndexPageStore.Entry(0, "I|2", 2L)));

            assertThrows(IllegalStateException.class,
                    () -> store.rowIdsFor(0, "DOK1|S|I|2"),
                    "legacy raw keys that resemble the old typed envelope must force safe fallback");
            assertThrows(IllegalStateException.class,
                    () -> store.rowIdsInRangeFor(0, "DOK1|S|I|10", true, "DOK1|S|I|2", true),
                    "typed range lookup must not reinterpret legacy raw keys as typed keys");
        }
    }

    @Test
    void versionedTextEnvelopePreservesPrefixShapedPayloads() throws Exception {
        try (MvccOrderedIndexPageStore store = MvccOrderedIndexPageStore.open(tempDir.resolve("ordered-index.pages"))) {
            store.rewrite(List.of(
                    new MvccOrderedIndexPageStore.Entry(0, "DOK1|S|I|10", 1L),
                    new MvccOrderedIndexPageStore.Entry(0, "DOK1|S|I|2", 2L)));

            assertEquals(List.of(2L), store.rowIdsFor(0, "DOK1|S|I|2"));
            assertEquals(List.of(1L, 2L),
                    store.rowIdsInRangeFor(0, "DOK1|S|I|10", true, "DOK1|S|I|2", true));
        }
    }


    @Test
    void typedNullEnvelopeSortsBeforeTypedValuesAndSupportsLookup() throws Exception {
        try (MvccOrderedIndexPageStore store = MvccOrderedIndexPageStore.open(tempDir.resolve("ordered-index.pages"))) {
            store.rewrite(List.of(
                    new MvccOrderedIndexPageStore.Entry(1, "DOK1|I|5", 3L),
                    new MvccOrderedIndexPageStore.Entry(1, "DOK1|N|", 1L),
                    new MvccOrderedIndexPageStore.Entry(1, "DOK1|S|alpha", 2L)));

            assertEquals(List.of(1L), store.rowIdsFor(1, "DOK1|N|"));
            assertEquals(List.of(1L, 3L),
                    store.rowIdsInRangeFor(1, "DOK1|N|", true, "DOK1|I|5", true));
            assertEquals(List.of(3L),
                    store.rowIdsInRangeFor(1, "DOK1|N|", false, "DOK1|I|5", true));
        }
    }


    @Test
    void equalityLookupKeepsExactEnvelopeSemanticsWithinComparatorEqualKeys() throws Exception {
        try (MvccOrderedIndexPageStore store = MvccOrderedIndexPageStore.open(tempDir.resolve("ordered-index.pages"))) {
            store.rewrite(List.of(
                    new MvccOrderedIndexPageStore.Entry(0, "DOK1|D|1.0", 1L),
                    new MvccOrderedIndexPageStore.Entry(0, "DOK1|D|1.00", 2L)));

            assertEquals(List.of(1L), store.rowIdsFor(0, "DOK1|D|1.0"));
            assertEquals(List.of(2L), store.rowIdsFor(0, "DOK1|D|1.00"));
            assertEquals(2, store.distinctKeyCount(),
                    "distinct durable envelopes remain distinct even when their range comparator is equal");
        }
    }


    @Test
    void durableComparatorStaysInParityWithTheStorageApiKeyContract() throws Exception {
        List<MvccOrderedIndexPageStore.Entry> entries = List.of(
                new MvccOrderedIndexPageStore.Entry(0, "DOK1|S|zeta", 7L),
                new MvccOrderedIndexPageStore.Entry(0, "DOK1|N|", 1L),
                new MvccOrderedIndexPageStore.Entry(0, "DOK1|F|-Infinity", 5L),
                new MvccOrderedIndexPageStore.Entry(0, "DOK1|D|1.00", 4L),
                new MvccOrderedIndexPageStore.Entry(0, "DOK1|I|-2", 2L),
                new MvccOrderedIndexPageStore.Entry(0, "DOK1|I|10", 3L),
                new MvccOrderedIndexPageStore.Entry(0, "DOK1|T|2026-07-13", 6L));
        List<Long> expected = entries.stream()
                .sorted((left, right) -> {
                    int comparison = DelosStorageOrderedIndexKey.compare(left.key(), right.key());
                    return comparison != 0 ? comparison : Long.compare(left.rowId(), right.rowId());
                })
                .map(MvccOrderedIndexPageStore.Entry::rowId)
                .toList();

        try (MvccOrderedIndexPageStore store = MvccOrderedIndexPageStore.open(
                tempDir.resolve("ordered-index.pages"))) {
            store.rewrite(entries);
            assertEquals(expected, store.rowIdsInRangeFor(0, null, true, null, true),
                    "the durable comparator copy must not drift from the storage-api key contract");
        }
    }

    @Test
    void repeatedLookupsReuseTheValidatedInMemorySnapshot() throws Exception {
        Path path = tempDir.resolve("ordered-index.pages");
        try (MvccOrderedIndexPageStore store = MvccOrderedIndexPageStore.open(path)) {
            store.rewrite(List.of(
                    new MvccOrderedIndexPageStore.Entry(0, "DOK1|I|1", 1L),
                    new MvccOrderedIndexPageStore.Entry(0, "DOK1|I|2", 2L),
                    new MvccOrderedIndexPageStore.Entry(0, "DOK1|I|2", 3L),
                    new MvccOrderedIndexPageStore.Entry(1, "DOK1|S|alpha", 4L)));

            long validatedLoads = store.snapshotLoadCountForTesting();
            assertEquals(List.of(2L, 3L), store.rowIdsFor(0, "DOK1|I|2"));
            assertEquals(List.of(1L, 2L, 3L),
                    store.rowIdsInRangeFor(0, "DOK1|I|1", true, "DOK1|I|2", true));
            assertEquals(List.of(4L), store.rowIdsFor(1, "DOK1|S|alpha"));
            assertEquals(4L, store.entryCount());
            assertEquals(3, store.distinctKeyCount());
            assertEquals(validatedLoads, store.snapshotLoadCountForTesting(),
                    "normal lookups and diagnostics must not reread every durable index page");
        }

        try (MvccOrderedIndexPageStore reopened = MvccOrderedIndexPageStore.open(path)) {
            assertEquals(1L, reopened.snapshotLoadCountForTesting(),
                    "reopen must validate and install the durable snapshot exactly once");
            assertEquals(List.of(2L, 3L), reopened.rowIdsFor(0, "DOK1|I|2"));
            assertEquals(1L, reopened.snapshotLoadCountForTesting(),
                    "lookup after reopen must use the installed immutable snapshot");
        }
    }

    @Test
    void rewritePublishesAndRefreshesTheLookupSnapshot() throws Exception {
        Path path = tempDir.resolve("ordered-index.pages");
        try (MvccOrderedIndexPageStore store = MvccOrderedIndexPageStore.open(path)) {
            store.rewrite(List.of(new MvccOrderedIndexPageStore.Entry(0, "DOK1|I|1", 1L)));
            assertEquals(List.of(1L), store.rowIdsFor(0, "DOK1|I|1"));

            long validatedLoads = store.snapshotLoadCountForTesting();
            store.rewrite(List.of(new MvccOrderedIndexPageStore.Entry(0, "DOK1|I|2", 2L)));

            assertEquals(List.of(), store.rowIdsFor(0, "DOK1|I|1"));
            assertEquals(List.of(2L), store.rowIdsFor(0, "DOK1|I|2"));
            assertEquals(validatedLoads, store.snapshotLoadCountForTesting(),
                    "successful rewrite must publish its new snapshot without a disk reread");
            assertNoReplacementFiles(path);
        }
    }


    @Test
    void failedReplacementOpenRemovesTheUniqueTemporarySidecar() throws Exception {
        Path path = tempDir.resolve("ordered-index.pages");
        try (MvccOrderedIndexPageStore initial = MvccOrderedIndexPageStore.open(path)) {
            initial.rewrite(List.of(new MvccOrderedIndexPageStore.Entry(0, "DOK1|I|1", 1L)));
        }

        AtomicInteger opens = new AtomicInteger();
        DelosPageVolumeFactory delegate = DelosPageVolumeFactories.fileChannel();
        DelosPageVolumeFactory failingReplacementFactory = openedPath -> {
            if (opens.incrementAndGet() == 2) {
                throw new IOException("injected replacement open failure");
            }
            return delegate.open(openedPath);
        };

        try (MvccOrderedIndexPageStore store = MvccOrderedIndexPageStore.open(
                path, failingReplacementFactory)) {
            assertThrows(IOException.class,
                    () -> store.rewrite(List.of(
                            new MvccOrderedIndexPageStore.Entry(0, "DOK1|I|2", 2L))));
            assertEquals(List.of(1L), store.rowIdsFor(0, "DOK1|I|1"));
            assertNoReplacementFiles(path);
        }

        try (MvccOrderedIndexPageStore reopened = MvccOrderedIndexPageStore.open(path)) {
            assertEquals(List.of(1L), reopened.rowIdsFor(0, "DOK1|I|1"));
            assertEquals(List.of(), reopened.rowIdsFor(0, "DOK1|I|2"));
        }
    }

    @Test
    void failedReplacementForcePreservesTheLiveSidecarAndSnapshot() throws Exception {
        Path path = tempDir.resolve("ordered-index.pages");
        try (MvccOrderedIndexPageStore initial = MvccOrderedIndexPageStore.open(path)) {
            initial.rewrite(List.of(new MvccOrderedIndexPageStore.Entry(0, "DOK1|I|1", 1L)));
        }

        AtomicInteger opens = new AtomicInteger();
        DelosPageVolumeFactory delegate = DelosPageVolumeFactories.fileChannel();
        DelosPageVolumeFactory failingReplacementFactory = openedPath -> {
            DelosPageVolume volume = delegate.open(openedPath);
            return opens.incrementAndGet() == 2 ? new ForceFailingVolume(volume) : volume;
        };

        try (MvccOrderedIndexPageStore store = MvccOrderedIndexPageStore.open(path, failingReplacementFactory)) {
            assertThrows(IOException.class,
                    () -> store.rewrite(List.of(
                            new MvccOrderedIndexPageStore.Entry(0, "DOK1|I|2", 2L))));
            assertEquals(List.of(1L), store.rowIdsFor(0, "DOK1|I|1"),
                    "failed replacement must leave the installed live snapshot unchanged");
            assertNoReplacementFiles(path);
        }

        try (MvccOrderedIndexPageStore reopened = MvccOrderedIndexPageStore.open(path)) {
            assertEquals(List.of(1L), reopened.rowIdsFor(0, "DOK1|I|1"),
                    "failed replacement must preserve the durable live sidecar");
            assertEquals(List.of(), reopened.rowIdsFor(0, "DOK1|I|2"));
        }
    }

    @Test
    void successfulRewriteSurvivesTransientPostMoveReopenFailure() throws Exception {
        Path path = tempDir.resolve("ordered-index.pages");
        AtomicInteger opens = new AtomicInteger();
        DelosPageVolumeFactory delegate = DelosPageVolumeFactories.fileChannel();
        DelosPageVolumeFactory transientReopenFailureFactory = openedPath -> {
            if (opens.incrementAndGet() == 3) {
                throw new IOException("injected post-move reopen failure");
            }
            return delegate.open(openedPath);
        };

        try (MvccOrderedIndexPageStore store = MvccOrderedIndexPageStore.open(
                path, transientReopenFailureFactory)) {
            store.rewrite(List.of(
                    new MvccOrderedIndexPageStore.Entry(0, "DOK1|I|2", 2L)));

            assertEquals(List.of(2L), store.rowIdsFor(0, "DOK1|I|2"),
                    "the installed immutable snapshot remains authoritative after replacement publication");
            assertEquals(1L, store.pageCount());
            assertEquals(1L, store.read().pageCount(),
                    "the next explicit durable validation must lazily reopen the live sidecar");
            assertEquals(List.of(2L), store.rowIdsFor(0, "DOK1|I|2"));
            assertNoReplacementFiles(path);
        }

        try (MvccOrderedIndexPageStore reopened = MvccOrderedIndexPageStore.open(path)) {
            assertEquals(List.of(2L), reopened.rowIdsFor(0, "DOK1|I|2"),
                    "post-move handle failure must not make a durable successful rewrite ambiguous");
        }
    }

    @Test
    void oversizedSurrogateKeysRemainEqualitySafeButForceRangeFallback() throws Exception {
        String oversized = "x".repeat(DelosPage.PAGE_SIZE * 2);
        try (MvccOrderedIndexPageStore store = MvccOrderedIndexPageStore.open(tempDir.resolve("ordered-index.pages"))) {
            store.rewrite(List.of(
                    new MvccOrderedIndexPageStore.Entry(0, "DOK1|S|alpha", 1L),
                    new MvccOrderedIndexPageStore.Entry(0, "DOK1|S|" + oversized, 2L)));

            assertEquals(List.of(1L), store.rowIdsFor(0, "DOK1|S|alpha"),
                    "an oversized key must not classify normal typed keys in the same column as legacy");
            assertEquals(List.of(2L), store.rowIdsFor(0, "DOK1|S|" + oversized),
                    "SHA-256 surrogate normalization remains valid for exact equality");
            assertThrows(IllegalStateException.class,
                    () -> store.rowIdsInRangeFor(0, "DOK1|S|a", true, "DOK1|S|z", true),
                    "hashed oversized surrogates cannot preserve SQL range ordering");
        }
    }

    @Test
    void oversizedRangeBoundsForceFallbackEvenWhenTheIndexContainsOnlyNormalKeys() throws Exception {
        String oversized = "z".repeat(DelosPage.PAGE_SIZE * 2);
        try (MvccOrderedIndexPageStore store = MvccOrderedIndexPageStore.open(tempDir.resolve("ordered-index.pages"))) {
            store.rewrite(List.of(
                    new MvccOrderedIndexPageStore.Entry(0, "DOK1|S|alpha", 1L),
                    new MvccOrderedIndexPageStore.Entry(0, "DOK1|S|omega", 2L)));

            assertThrows(IllegalStateException.class,
                    () -> store.rowIdsInRangeFor(0, "DOK1|S|a", true, "DOK1|S|" + oversized, true),
                    "a hashed query bound cannot preserve SQL range ordering");
            assertThrows(IllegalStateException.class,
                    () -> store.rowIdsInRangeFor(
                            0, "DOK1|S|" + oversized, true, "DOK1|S|" + oversized.substring(1), true),
                    "unsafe oversized bounds must fall back before reversed-bound comparison");
        }
    }

    @Test
    void malformedVersionedEnvelopeIsRejectedBeforeRewrite() throws Exception {
        try (MvccOrderedIndexPageStore store = MvccOrderedIndexPageStore.open(tempDir.resolve("ordered-index.pages"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.rewrite(List.of(
                            new MvccOrderedIndexPageStore.Entry(0, "DOK1|X|bad", 1L),
                            new MvccOrderedIndexPageStore.Entry(0, "DOK1|S|good", 2L))),
                    "unknown typed envelope kinds should fail loudly instead of being sorted as legacy text");
        }
    }


    private static void assertNoReplacementFiles(Path livePath) throws IOException {
        try (var files = Files.list(livePath.toAbsolutePath().getParent())) {
            assertFalse(files.anyMatch(candidate -> candidate.getFileName().toString()
                            .startsWith(livePath.getFileName() + ".rewrite-")),
                    "ordered-index rewrite must not leave stale temporary sidecars");
        }
    }

    private static final class ForceFailingVolume implements DelosPageVolume {
        private final DelosPageVolume delegate;

        private ForceFailingVolume(DelosPageVolume delegate) {
            this.delegate = delegate;
        }

        @Override
        public DelosPage readPage(DelosPageId id) throws IOException {
            return delegate.readPage(id);
        }

        @Override
        public void writePage(DelosPage page) throws IOException {
            delegate.writePage(page);
        }

        @Override
        public DelosPage allocatePage(int pageType) throws IOException {
            return delegate.allocatePage(pageType);
        }

        @Override
        public long pageCount() throws IOException {
            return delegate.pageCount();
        }

        @Override
        public void force() throws IOException {
            throw new IOException("forced replacement failure");
        }

        @Override
        public SyncPolicy syncPolicy() {
            return delegate.syncPolicy();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
