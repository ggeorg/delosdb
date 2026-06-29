package io.github.ggeorg.delosdb.storage.mvcc.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PageVolumeMvccStateStoreDropTest {
    @TempDir
    Path tempDir;

    @Test
    void dropRemovesAllPageVolumeMvccDurableFiles() throws Exception {
        PageVolumeMvccStateStore<List<String>> store = PageVolumeMvccStateStore.open(
                tempDir,
                "conglomerate-7-9",
                new StringListCodec());
        store.persistVisibleRows(List.of(
                new PageVolumeMvccStateStore.PersistedRow<>(1L, List.of("alpha")),
                new PageVolumeMvccStateStore.PersistedRow<>(2L, List.of("bravo"))));
        store.close();

        Path pageFile = PageVolumeMvccPaths.pageFile(tempDir, "conglomerate-7-9");
        Path rowDirectoryFile = io.github.ggeorg.delosdb.storage.mvcc.durable.PageBackedMvccTable.rowDirectoryPath(pageFile);
        Path mutationLogFile = PageVolumeMvccPaths.pageMutationLogFileFor(pageFile);
        Path outcomeLogFile = PageVolumeMvccPaths.transactionOutcomeLogFileFor(pageFile);
        Path walFile = PageVolumeMvccPaths.writeAheadLogFile(tempDir, "conglomerate-7-9");
        Path checkpointFile = PageVolumeMvccPaths.checkpointFile(tempDir, "conglomerate-7-9");

        assertTrue(Files.exists(pageFile));
        assertTrue(Files.exists(rowDirectoryFile));
        assertTrue(Files.exists(mutationLogFile));
        assertTrue(Files.exists(outcomeLogFile));
        assertTrue(Files.exists(walFile));
        assertTrue(Files.exists(checkpointFile));

        PageVolumeMvccStateStore.open(tempDir, "conglomerate-7-9", new StringListCodec()).drop();

        assertFalse(Files.exists(pageFile));
        assertFalse(Files.exists(rowDirectoryFile));
        assertFalse(Files.exists(mutationLogFile));
        assertFalse(Files.exists(outcomeLogFile));
        assertFalse(Files.exists(walFile));
        assertFalse(Files.exists(checkpointFile));
    }

    private static final class StringListCodec implements PageVolumeMvccStateStore.RowCodec<List<String>> {
        @Override
        public byte[] encode(List<String> values) {
            return String.join("\u001f", values).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public List<String> decode(byte[] encoded) throws IOException {
            String decoded = new String(encoded, StandardCharsets.UTF_8);
            if (decoded.isEmpty()) {
                return List.of();
            }
            return List.of(decoded.split("\u001f", -1));
        }
    }
}
