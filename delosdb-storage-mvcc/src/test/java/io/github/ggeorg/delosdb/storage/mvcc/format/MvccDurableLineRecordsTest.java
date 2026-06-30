package io.github.ggeorg.delosdb.storage.mvcc.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

final class MvccDurableLineRecordsTest {
    @Test
    void completeRecordsIgnoreTornFinalLineAndPreserveOriginalLineNumbers() {
        List<MvccDurableLineRecords.LineRecord> records = MvccDurableLineRecords.completeRecords(
                "1\tCOMMIT\t1\t10\n\n  1\tABORT\t2  \n1\tCOMMIT\t3");

        assertEquals(2, records.size());
        assertEquals(0, records.get(0).lineIndex());
        assertEquals("1\tCOMMIT\t1\t10", records.get(0).line());
        assertEquals(2, records.get(1).lineIndex());
        assertEquals("1\tABORT\t2", records.get(1).line());
    }

    @Test
    void untrimmedRecordsPreserveContentButSkipBlankLines() {
        List<MvccDurableLineRecords.LineRecord> records = MvccDurableLineRecords.completeRecords(
                " 1\tA \n   \n2\tB\n", false);

        assertEquals(2, records.size());
        assertEquals(" 1\tA ", records.get(0).line());
        assertEquals("2\tB", records.get(1).line());
    }

    @Test
    void parseLongReportsConsistentCorruptionMessage() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> MvccDurableLineRecords.parseLong("abc", 4, "lsn", "MVCC test log"));

        assertEquals("Corrupt MVCC test log at line 5: invalid lsn: abc", error.getMessage());
    }
}
