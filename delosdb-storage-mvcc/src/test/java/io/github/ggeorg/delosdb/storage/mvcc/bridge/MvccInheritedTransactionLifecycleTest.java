package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.derby.iapi.store.types.DelosStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccPaths;

/** Proofs the active Derby-facing MVCC storage transaction registry path. */
final class MvccInheritedTransactionLifecycleTest {
    @TempDir
    Path databaseDirectory;

    @AfterEach
    void clearRegistry() {
        DelosStorageTransactionRegistry.clearForTesting();
    }

    @Test
    void oneDerbyTransactionCommitsTwoMvccTablesAndReopensBoth() {
        Object derbyTransaction = new Object();
        MvccInheritedTable accounts = table(1, 101);
        MvccInheritedTable ledger = table(1, 102);

        DelosStorageTransaction accountsTx = accounts.beginTransaction();
        DelosStorageTransaction ledgerTx = ledger.beginTransaction();
        DelosStorageTransactionRegistry.register(derbyTransaction, accounts, accountsTx);
        DelosStorageTransactionRegistry.register(derbyTransaction, ledger, ledgerTx);

        accounts.insert(1L, durableEmptyRow(), accountsTx);
        ledger.insert(1L, durableEmptyRow(), ledgerTx);

        assertEquals(2, DelosStorageTransactionRegistry.pendingCountForTesting(derbyTransaction));
        DelosStorageTransactionRegistry.commit(derbyTransaction);
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(derbyTransaction));
        accounts.close();
        ledger.close();

        MvccInheritedTable reopenedAccounts = table(1, 101);
        MvccInheritedTable reopenedLedger = table(1, 102);

        assertTrue(read(reopenedAccounts, 1L).isPresent());
        assertTrue(read(reopenedLedger, 1L).isPresent());
        reopenedAccounts.close();
        reopenedLedger.close();
    }

    @Test
    void oneDerbyTransactionAbortsTwoMvccTablesAndPersistsNeither() {
        Object derbyTransaction = new Object();
        MvccInheritedTable accounts = table(2, 201);
        MvccInheritedTable ledger = table(2, 202);

        DelosStorageTransaction accountsTx = accounts.beginTransaction();
        DelosStorageTransaction ledgerTx = ledger.beginTransaction();
        DelosStorageTransactionRegistry.register(derbyTransaction, accounts, accountsTx);
        DelosStorageTransactionRegistry.register(derbyTransaction, ledger, ledgerTx);

        accounts.insert(1L, durableEmptyRow(), accountsTx);
        ledger.insert(1L, durableEmptyRow(), ledgerTx);

        DelosStorageTransactionRegistry.abort(derbyTransaction);
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(derbyTransaction));
        accounts.close();
        ledger.close();

        MvccInheritedTable reopenedAccounts = table(2, 201);
        MvccInheritedTable reopenedLedger = table(2, 202);

        assertTrue(read(reopenedAccounts, 1L).isEmpty());
        assertTrue(read(reopenedLedger, 1L).isEmpty());
        assertEquals(0, reopenedAccounts.logicalRowCountForTesting());
        assertEquals(0, reopenedLedger.logicalRowCountForTesting());
        reopenedAccounts.close();
        reopenedLedger.close();
    }

    @Test
    void registeredRollbackDoesNotDisturbPreviouslyCommittedState() {
        Object firstDerbyTransaction = new Object();
        MvccInheritedTable table = table(3, 301);
        DelosStorageTransaction first = table.beginTransaction();
        DelosStorageTransactionRegistry.register(firstDerbyTransaction, table, first);
        table.insert(1L, durableEmptyRow(), first);
        DelosStorageTransactionRegistry.commit(firstDerbyTransaction);

        Object secondDerbyTransaction = new Object();
        DelosStorageTransaction second = table.beginTransaction();
        DelosStorageSnapshot secondSnapshot = table.snapshot(second);
        DelosStorageTransactionRegistry.register(secondDerbyTransaction, table, second);
        table.update(1L, durableEmptyRow(), second, secondSnapshot);
        table.insert(2L, durableEmptyRow(), second);
        DelosStorageTransactionRegistry.abort(secondDerbyTransaction);
        table.close();

        MvccInheritedTable reopened = table(3, 301);
        assertTrue(read(reopened, 1L).isPresent());
        assertTrue(read(reopened, 2L).isEmpty());
        reopened.close();
    }


    @Test
    void registeredReadOnlyTransactionDoesNotGrowDurableStatusJournal() throws Exception {
        Object writerOwner = new Object();
        MvccInheritedTable table = table(4, 401);
        DelosStorageTransaction writer = table.beginTransaction();
        DelosStorageTransactionRegistry.register(writerOwner, table, writer);
        table.insert(1L, durableEmptyRow(), writer);
        DelosStorageTransactionRegistry.commit(writerOwner);

        Path statusFile = PageVolumeMvccPaths.inheritedStoreDirectory(databaseDirectory)
                .resolve("conglomerate-4-401.txstatus");
        long statusBytesAfterWriter = Files.size(statusFile);

        Object readerOwner = new Object();
        DelosStorageTransactionRegistry.Reader reader =
                DelosStorageTransactionRegistry.reader(readerOwner, table);
        assertTrue(table.read(1L, reader.snapshot()).isPresent());
        DelosStorageTransactionRegistry.commit(readerOwner);

        assertEquals(statusBytesAfterWriter, Files.size(statusFile),
                "read-only registry lifecycle must not force ACTIVE/ABORTED status records");
        table.close();
    }

    private MvccInheritedTable table(long segmentId, long containerId) {
        return new MvccInheritedTable(segmentId, containerId, databaseDirectory);
    }

    private static StoreDataValue[] durableEmptyRow() {
        // This proof is about Derby transaction registry commit/abort/reopen
        // coordination. Use a typed-codec durable empty row so the test does
        // not revive the old arbitrary StoreValueOperations fixture and does
        // not accidentally test null-key index behavior.
        return new StoreDataValue[0];
    }

    private static Optional<StoreDataValue[]> read(MvccInheritedTable table, long rowId) {
        DelosStorageTransaction reader = table.beginReadOnlyTransaction();
        try {
            return table.read(rowId, table.snapshot(reader));
        } finally {
            table.abort(reader);
        }
    }
}
