package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Table-scan proof for the experimental DelosDB MVCC storage module. These
 * tests prove visible-row enumeration without touching Derby heap, B-tree,
 * locking, logging, recovery, or SQL execution.
 */
public final class MvccTableScanModelTest {
    @Test
    public void testScanReturnsOnlyRowsVisibleToSnapshot() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction committedWriter = txManager.begin();
        table.insert(1, "alpha", committedWriter);
        txManager.commit(committedWriter);

        MvccTransaction activeWriter = txManager.begin();
        table.insert(2, "beta", activeWriter);

        MvccTransaction reader = txManager.begin();
        MvccSnapshot beforeSecondCommit = txManager.snapshot(reader);

        assertEquals(List.of("1=alpha"), rows(table.openScan(beforeSecondCommit, txManager)));

        txManager.commit(activeWriter);
        assertEquals(List.of("1=alpha"), rows(table.openScan(beforeSecondCommit, txManager)),
                "a stable snapshot must not see a transaction committed after capture");

        MvccTransaction newReader = txManager.begin();
        assertEquals(List.of("1=alpha", "2=beta"), rows(table.openScan(txManager.snapshot(newReader), txManager)));
    }

    @Test
    public void testScanPreservesOldSnapshotAcrossUpdateAndDelete() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction inserter = txManager.begin();
        table.insert(1, "v1", inserter);
        table.insert(2, "two", inserter);
        txManager.commit(inserter);

        MvccTransaction oldReader = txManager.begin();
        MvccSnapshot oldSnapshot = txManager.snapshot(oldReader);

        MvccTransaction updater = txManager.begin();
        table.update(1, "v2", updater, txManager.snapshot(updater), txManager);
        txManager.commit(updater);

        MvccTransaction deleter = txManager.begin();
        table.delete(2, deleter, txManager.snapshot(deleter), txManager);
        txManager.commit(deleter);

        assertEquals(List.of("1=v1", "2=two"), rows(table.openScan(oldSnapshot, txManager)));

        MvccTransaction newReader = txManager.begin();
        assertEquals(List.of("1=v2"), rows(table.openScan(txManager.snapshot(newReader), txManager)));
    }

    @Test
    public void testOpenedScanOwnsStableVisibleRows() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction inserter = txManager.begin();
        table.insert(1, "before", inserter);
        txManager.commit(inserter);

        MvccTransaction reader = txManager.begin();
        MvccSnapshot snapshot = txManager.snapshot(reader);
        MvccScan<Integer, String> scan = table.openScan(snapshot, txManager);

        MvccTransaction updater = txManager.begin();
        table.update(1, "after", updater, txManager.snapshot(updater), txManager);
        txManager.commit(updater);

        assertEquals(List.of("1=before"), rows(scan),
                "an already-open scan keeps the rows visible when it was opened");
    }

    @Test
    public void testScanSkipsAbortedRowsAndCleanupDoesNotChangeVisibleOutput() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction committed = txManager.begin();
        table.insert(1, "kept", committed);
        txManager.commit(committed);

        MvccTransaction aborted = txManager.begin();
        table.insert(2, "discarded", aborted);
        txManager.abort(aborted);

        MvccTransaction reader = txManager.begin();
        MvccSnapshot snapshot = txManager.snapshot(reader);
        assertEquals(List.of("1=kept"), rows(table.openScan(snapshot, txManager)));
        assertEquals(1, table.cleanup(txManager).removedVersions());
        assertEquals(List.of("1=kept"), rows(table.openScan(snapshot, txManager)));
    }

    @Test
    public void testScanRowPositionAndCloseContracts() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction writer = txManager.begin();
        table.insert(1, "alpha", writer);
        txManager.commit(writer);

        MvccTransaction reader = txManager.begin();
        MvccScan<Integer, String> scan = table.openScan(txManager.snapshot(reader), txManager);

        assertThrows(IllegalStateException.class, scan::row);
        assertEquals(1, scan.visibleRowCount());
        assertEquals(true, scan.next());
        assertEquals(new MvccRow<>(1, "alpha"), scan.row());
        assertEquals(false, scan.next());
        assertThrows(IllegalStateException.class, scan::row);

        scan.close();
        assertThrows(IllegalStateException.class, scan::next);
    }

    private static <K, V> List<String> rows(MvccScan<K, V> scan) {
        List<String> values = new ArrayList<>();
        try (scan) {
            while (scan.next()) {
                MvccRow<K, V> row = scan.row();
                values.add(row.key() + "=" + row.value());
            }
        }
        return values;
    }
}
