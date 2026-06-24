package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;

final class MvccDurableIndexCandidateTest {
    @TempDir
    Path tempDir;

    @Test
    void durableIndexTupleEncodesRowAndVersionCandidate() {
        MvccIndexTuple tuple = MvccIndexTuple.forStringKey(
                "idx_name",
                "alpha",
                new MvccRowId(7L),
                new MvccVersionId(11L),
                new MvccVersionLocator(new DelosPageId(3L), 2));

        MvccIndexTuple decoded = MvccIndexTupleCodec.decode(MvccIndexTupleCodec.encode(tuple));

        assertEquals("IDX_NAME", decoded.indexName());
        assertEquals("alpha", decoded.indexKeyAsUtf8());
        assertEquals(new MvccRowId(7L), decoded.rowId());
        assertEquals(new MvccVersionId(11L), decoded.versionId());
        assertEquals(new MvccVersionLocator(new DelosPageId(3L), 2), decoded.versionLocator());
    }

    @Test
    void durableIndexStoreSurvivesReopenAndLookupReturnsCandidatesOnly() throws Exception {
        Path tablePath = tempDir.resolve("table.dmvccp");
        Path indexPath = tempDir.resolve("idx_name.dmvcci");
        MvccIndexTuple first;
        MvccIndexTuple second;

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tablePath);
                MvccDurableIndexStore index = MvccDurableIndexStore.open(indexPath)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
            first = tupleFromNewestVersion(table, "idx_name", "alpha", "account:1");
            index.append(first);

            table.updateCommitted("account:1", "beta", 2L, 2L);
            second = tupleFromNewestVersion(table, "idx_name", "beta", "account:1");
            index.append(second);
        }

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tablePath);
                MvccDurableIndexStore index = MvccDurableIndexStore.open(indexPath)) {
            List<MvccIndexTuple> alphaCandidates = index.lookup("IDX_NAME", "alpha".getBytes(StandardCharsets.UTF_8));
            List<MvccIndexTuple> betaCandidates = index.lookup("idx_name", "beta".getBytes(StandardCharsets.UTF_8));

            assertEquals(List.of(first), alphaCandidates);
            assertEquals(List.of(second), betaCandidates);
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertEquals("beta", table.read("account:1", new MvccCommitSequence(2L)).orElseThrow());
        }
    }

    @Test
    void lookupSeparatesIndexNamesAndKeys() throws Exception {
        Path indexPath = tempDir.resolve("indexes.dmvcci");
        try (MvccDurableIndexStore index = MvccDurableIndexStore.open(indexPath)) {
            index.append(tuple("idx_name", "alpha", 1L, 1L));
            index.append(tuple("idx_email", "alpha", 2L, 1L));
            index.append(tuple("idx_name", "beta", 3L, 1L));
        }

        try (MvccDurableIndexStore index = MvccDurableIndexStore.open(indexPath)) {
            List<MvccIndexTuple> matches = index.lookup("idx_name", "alpha".getBytes(StandardCharsets.UTF_8));
            assertEquals(1, matches.size());
            assertEquals(new MvccRowId(1L), matches.get(0).rowId());
        }
    }

    @Test
    void largeCandidateSetSpansPages() throws Exception {
        Path indexPath = tempDir.resolve("large.dmvcci");
        try (MvccDurableIndexStore index = MvccDurableIndexStore.open(indexPath)) {
            String largeKey = "x".repeat(512);
            for (int row = 1; row <= 80; row++) {
                index.append(tuple("idx_large", largeKey + row, row, row));
            }
            if (index.pageCount() < 2L) {
                throw new AssertionError("expected durable index candidates to span multiple pages");
            }
        }

        try (MvccDurableIndexStore index = MvccDurableIndexStore.open(indexPath)) {
            assertEquals(80, index.loadAll().size());
        }
    }

    @Test
    void codecRejectsBadMagicAndUnknownFlags() {
        MvccIndexTuple tuple = tuple("idx_name", "alpha", 1L, 1L);
        byte[] bytes = MvccIndexTupleCodec.encode(tuple);
        bytes[0] = 0x00;
        assertThrows(IllegalArgumentException.class, () -> MvccIndexTupleCodec.decode(bytes));

        assertThrows(IllegalArgumentException.class, () -> new MvccIndexTuple(
                "idx_name",
                "alpha".getBytes(StandardCharsets.UTF_8),
                new MvccRowId(1L),
                new MvccVersionId(1L),
                new MvccVersionLocator(new DelosPageId(0L), 0),
                0x40));
    }

    @Test
    void indexTupleDefensivelyCopiesKeyBytes() {
        byte[] key = "alpha".getBytes(StandardCharsets.UTF_8);
        MvccIndexTuple tuple = new MvccIndexTuple(
                "idx_name",
                key,
                new MvccRowId(1L),
                new MvccVersionId(1L),
                new MvccVersionLocator(new DelosPageId(0L), 0),
                0);
        key[0] = 'X';
        assertArrayEquals("alpha".getBytes(StandardCharsets.UTF_8), tuple.indexKey());
    }

    private static MvccIndexTuple tupleFromNewestVersion(
            PageBackedMvccTable table,
            String indexName,
            String indexKey,
            String rowKey) {
        return MvccIndexTuple.forStringKey(
                indexName,
                indexKey,
                table.rowIdForKey(rowKey).orElseThrow(),
                table.newestVersionIdForKey(rowKey).orElseThrow(),
                table.newestVersionLocatorForKey(rowKey).orElseThrow());
    }

    private static MvccIndexTuple tuple(String indexName, String indexKey, long rowId, long versionId) {
        return MvccIndexTuple.forStringKey(
                indexName,
                indexKey,
                new MvccRowId(rowId),
                new MvccVersionId(versionId),
                new MvccVersionLocator(new DelosPageId(rowId), (int) (versionId - 1L)));
    }
}
