package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

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
    void rewriteAtomicallyRefreshesTheLookupSnapshot() throws Exception {
        try (MvccOrderedIndexPageStore store = MvccOrderedIndexPageStore.open(tempDir.resolve("ordered-index.pages"))) {
            store.rewrite(List.of(new MvccOrderedIndexPageStore.Entry(0, "DOK1|I|1", 1L)));
            assertEquals(List.of(1L), store.rowIdsFor(0, "DOK1|I|1"));

            long validatedLoads = store.snapshotLoadCountForTesting();
            store.rewrite(List.of(new MvccOrderedIndexPageStore.Entry(0, "DOK1|I|2", 2L)));

            assertEquals(List.of(), store.rowIdsFor(0, "DOK1|I|1"));
            assertEquals(List.of(2L), store.rowIdsFor(0, "DOK1|I|2"));
            assertEquals(validatedLoads, store.snapshotLoadCountForTesting(),
                    "successful rewrite must publish its new snapshot without a disk reread");
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
}
