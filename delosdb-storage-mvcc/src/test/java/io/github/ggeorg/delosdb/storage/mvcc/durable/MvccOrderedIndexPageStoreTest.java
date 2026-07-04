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
