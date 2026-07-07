package io.github.ggeorg.delosdb.storage.mvcc.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PageVolumeMvccCheckpointLifecycleTest {
    @TempDir
    Path tempDir;

    @Test
    void checkpointRewritePublishesLifecycleAndClearsPendingMarker() throws Exception {
        PageVolumeMvccStateStore<List<String>> store = PageVolumeMvccStateStore.open(
                tempDir,
                "conglomerate-11-13",
                new StringListCodec());
        store.persistChangedRows(List.of(
                PageVolumeMvccStateStore.PersistedChange.upsert(1L, List.of("alpha")),
                PageVolumeMvccStateStore.PersistedChange.upsert(2L, List.of("bravo"))));
        store.close();

        Path checkpoint = PageVolumeMvccPaths.checkpointFile(tempDir, "conglomerate-11-13");
        Path pending = PageVolumeMvccPaths.checkpointPendingFile(tempDir, "conglomerate-11-13");
        Path lifecycle = PageVolumeMvccPaths.checkpointLifecycleFile(tempDir, "conglomerate-11-13");

        assertTrue(Files.exists(checkpoint));
        assertTrue(Files.readString(checkpoint).contains("version=2"));
        assertFalse(Files.exists(pending));
        assertTrue(Files.exists(lifecycle));
        assertTrue(Files.readString(lifecycle).contains("state=COMPLETED"));
    }

    @Test
    void interruptedCheckpointLifecycleFallsBackAndNextCheckpointRepairsMarker() throws Exception {
        PageVolumeMvccStateStore<List<String>> store = PageVolumeMvccStateStore.open(
                tempDir,
                "conglomerate-17-19",
                new StringListCodec());
        store.persistChangedRows(List.of(
                PageVolumeMvccStateStore.PersistedChange.upsert(1L, List.of("alpha"))));
        store.close();

        Path pending = PageVolumeMvccPaths.checkpointPendingFile(tempDir, "conglomerate-17-19");
        Files.writeString(pending,
                "magic=DELOS_INHERITED_MVCC_CHECKPOINT_LIFECYCLE\n"
                        + "version=1\n"
                        + "storageId=conglomerate-17-19\n"
                        + "generation=999\n"
                        + "state=PREPARED\n",
                StandardCharsets.UTF_8);

        PageVolumeMvccStateStore<List<String>> reopened = PageVolumeMvccStateStore.open(
                tempDir,
                "conglomerate-17-19",
                new StringListCodec());
        assertEquals("INCOMPLETE", reopened.checkpointStatus());
        reopened.persistChangedRows(List.of(
                PageVolumeMvccStateStore.PersistedChange.upsert(2L, List.of("bravo"))));
        reopened.close();

        assertFalse(Files.exists(pending));
        Path lifecycle = PageVolumeMvccPaths.checkpointLifecycleFile(tempDir, "conglomerate-17-19");
        assertTrue(Files.readString(lifecycle).contains("state=COMPLETED"));
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
