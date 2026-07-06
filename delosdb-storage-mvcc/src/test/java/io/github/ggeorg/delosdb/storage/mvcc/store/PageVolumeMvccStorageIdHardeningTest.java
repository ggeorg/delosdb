package io.github.ggeorg.delosdb.storage.mvcc.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PageVolumeMvccStorageIdHardeningTest {
    @TempDir
    Path tempDir;

    @Test
    void pathHelpersRejectSingleBackslashAndControlCharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> PageVolumeMvccPaths.pageFile(tempDir, "conglomerate\\7"));
        assertThrows(IllegalArgumentException.class,
                () -> PageVolumeMvccPaths.writeAheadLogFile(tempDir, "conglomerate\t7"));
        assertThrows(IllegalArgumentException.class,
                () -> PageVolumeMvccPaths.checkpointFile(tempDir, "conglomerate\n7"));
        assertThrows(IllegalArgumentException.class,
                () -> PageVolumeMvccPaths.subsystemRecoveryRecordsFile(tempDir, "conglomerate\r7"));
    }

    @Test
    void nullOrBlankStorageIdsDisableOptionalStoresBeforePathResolution() {
        PageVolumeMvccStateStore<List<String>> stateStore = PageVolumeMvccStateStore.open(
                tempDir, null, new StringListCodec());
        assertFalse(stateStore.enabled());
        assertNull(stateStore.pageFile());

        PageVolumeMvccWriteAheadLog writeAheadLog = PageVolumeMvccWriteAheadLog.open(tempDir, "");
        assertFalse(writeAheadLog.enabled());
        assertNull(writeAheadLog.path());

        PageVolumeMvccCheckpointStore checkpointStore = PageVolumeMvccCheckpointStore.open(tempDir, "   ");
        assertFalse(checkpointStore.enabled());
        assertNull(checkpointStore.path());

        MvccSubsystemRecoveryRecordStore recoveryRecordStore = MvccSubsystemRecoveryRecordStore.open(
                tempDir, null);
        assertFalse(recoveryRecordStore.enabled());
        assertNull(recoveryRecordStore.path());
    }

    @Test
    void invalidNonBlankStorageIdsStillFailInsteadOfSilentlyDisablingStores() {
        assertThrows(IllegalArgumentException.class,
                () -> PageVolumeMvccStateStore.open(tempDir, "conglomerate\\7", new StringListCodec()));
        assertThrows(IllegalArgumentException.class,
                () -> PageVolumeMvccWriteAheadLog.open(tempDir, "conglomerate\n7"));
        assertThrows(IllegalArgumentException.class,
                () -> PageVolumeMvccCheckpointStore.open(tempDir, "conglomerate/7"));
        assertThrows(IllegalArgumentException.class,
                () -> MvccSubsystemRecoveryRecordStore.open(tempDir, "conglomerate\t7"));
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
