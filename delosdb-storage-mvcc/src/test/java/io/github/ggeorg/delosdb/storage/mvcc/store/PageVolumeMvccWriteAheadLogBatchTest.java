package io.github.ggeorg.delosdb.storage.mvcc.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;

final class PageVolumeMvccWriteAheadLogBatchTest {
    @TempDir
    Path tempDir;

    @Test
    void transactionVersionBatchReservesContiguousPageLsnsAndWritesOneCompleteWalUnit() throws Exception {
        PageVolumeMvccWriteAheadLog log = PageVolumeMvccWriteAheadLog.open(tempDir, "conglomerate-0-7");

        List<DelosLogSequenceNumber> pageLsns = log.appendVersionBatch(
                11L,
                22L,
                List.of(
                        PageVolumeMvccWriteAheadLog.VersionWrite.insert(101L),
                        PageVolumeMvccWriteAheadLog.VersionWrite.update(102L),
                        PageVolumeMvccWriteAheadLog.VersionWrite.delete(103L)));

        assertEquals(List.of(
                new DelosLogSequenceNumber(2L),
                new DelosLogSequenceNumber(3L),
                new DelosLogSequenceNumber(4L)), pageLsns);
        String content = Files.readString(log.path(), StandardCharsets.UTF_8);
        assertTrue(content.contains("\tBEGIN\t11\t0\tconglomerate-0-7\t0\n"));
        assertTrue(content.contains("\tINSERT_VERSION\t11\t0\tconglomerate-0-7\t101\n"));
        assertTrue(content.contains("\tUPDATE_VERSION\t11\t0\tconglomerate-0-7\t102\n"));
        assertTrue(content.contains("\tDELETE_VERSION\t11\t0\tconglomerate-0-7\t103\n"));
        assertTrue(content.contains("\tCOMMIT\t11\t22\tconglomerate-0-7\t0\n"));
        assertEquals(5, content.lines().count());
    }
}
