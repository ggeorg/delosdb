package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.derby.iapi.services.io.ArrayInputStream;
import org.apache.derby.iapi.store.types.DelosStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueOperations;
import org.apache.derby.shared.common.error.StandardException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

        accounts.insert(1L, row("alice", "100"), accountsTx);
        ledger.insert(1L, row("entry", "created"), ledgerTx);

        assertEquals(2, DelosStorageTransactionRegistry.pendingCountForTesting(derbyTransaction));
        DelosStorageTransactionRegistry.commit(derbyTransaction);
        assertEquals(0, DelosStorageTransactionRegistry.pendingCountForTesting(derbyTransaction));
        accounts.close();
        ledger.close();

        MvccInheritedTable reopenedAccounts = table(1, 101);
        MvccInheritedTable reopenedLedger = table(1, 102);

        assertEquals(rowText("alice", "100"), readRow(reopenedAccounts, 1L));
        assertEquals(rowText("entry", "created"), readRow(reopenedLedger, 1L));
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

        accounts.insert(1L, row("bob", "200"), accountsTx);
        ledger.insert(1L, row("entry", "rolled-back"), ledgerTx);

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
        table.insert(1L, row("stable", "before"), first);
        DelosStorageTransactionRegistry.commit(firstDerbyTransaction);

        Object secondDerbyTransaction = new Object();
        DelosStorageTransaction second = table.beginTransaction();
        DelosStorageSnapshot secondSnapshot = table.snapshot(second);
        DelosStorageTransactionRegistry.register(secondDerbyTransaction, table, second);
        table.update(1L, row("unstable", "after"), second, secondSnapshot);
        DelosStorageTransactionRegistry.abort(secondDerbyTransaction);
        table.close();

        MvccInheritedTable reopened = table(3, 301);
        assertEquals(rowText("stable", "before"), readRow(reopened, 1L));
        reopened.close();
    }

    private MvccInheritedTable table(long segmentId, long containerId) {
        return new MvccInheritedTable(segmentId, containerId, databaseDirectory);
    }

    private static StoreDataValue[] row(String left, String right) {
        return new StoreDataValue[] {new TestStoreValue(left), new TestStoreValue(right)};
    }

    private static String rowText(String left, String right) {
        return left + ":" + right;
    }

    private static String readRow(MvccInheritedTable table, long rowId) {
        return read(table, rowId)
                .map(values -> ((TestStoreValue) values[0]).value + ":" + ((TestStoreValue) values[1]).value)
                .orElseThrow(() -> new AssertionError("missing MVCC row " + rowId));
    }

    private static Optional<StoreDataValue[]> read(MvccInheritedTable table, long rowId) {
        DelosStorageTransaction reader = table.beginTransaction();
        try {
            return table.read(rowId, table.snapshot(reader));
        } finally {
            table.abort(reader);
        }
    }

    private static final class TestStoreValue implements StoreValueOperations, Serializable {
        private static final long serialVersionUID = 1L;
        private String value;

        private TestStoreValue(String value) {
            this.value = value;
        }

        @Override
        public StoreDataValue cloneHolder() {
            return new TestStoreValue(null);
        }

        @Override
        public StoreDataValue cloneValue(boolean forceMaterialization) {
            return new TestStoreValue(value);
        }

        @Override
        public StoreDataValue getNewNull() {
            return new TestStoreValue(null);
        }

        @Override
        public StoreDataValue recycle() {
            value = null;
            return this;
        }

        @Override
        public int getLength() {
            return value == null ? 0 : value.length();
        }

        @Override
        public long getLong() {
            return value == null ? 0L : Long.parseLong(value);
        }

        @Override
        public String getString() {
            return value;
        }

        @Override
        public boolean isNull() {
            return value == null;
        }

        @Override
        public Object getObject() {
            return value;
        }

        @Override
        public InputStream getStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int estimateMemoryUsage() {
            return value == null ? 0 : value.length() * Character.BYTES;
        }

        @Override
        public void setValue(StoreDataValue source) throws StandardException {
            if (source instanceof TestStoreValue testValue) {
                value = testValue.value;
            } else if (source instanceof StoreValueOperations operations) {
                value = operations.getString();
            } else {
                value = source == null ? null : source.toString();
            }
        }

        @Override
        public void setIntValue(int value) {
            this.value = Integer.toString(value);
        }

        @Override
        public void setLongValue(long value) {
            this.value = Long.toString(value);
        }

        @Override
        public void restoreToNull() {
            value = null;
        }

        @Override
        public void readExternal(ObjectInput input) throws IOException, ClassNotFoundException {
            value = (String) input.readObject();
        }

        @Override
        public void readExternalFromArray(ArrayInputStream input) throws IOException, ClassNotFoundException {
            readExternal(input);
        }

        @Override
        public void writeExternal(ObjectOutput output) throws IOException {
            output.writeObject(value);
        }

        @Override
        public int compare(StoreDataValue other) throws StandardException {
            return compare(other, true);
        }

        @Override
        public int compare(StoreDataValue other, boolean nullsOrderedLow) throws StandardException {
            String otherValue = other instanceof StoreValueOperations operations ? operations.getString() : null;
            if (value == null && otherValue == null) {
                return 0;
            }
            if (value == null) {
                return nullsOrderedLow ? -1 : 1;
            }
            if (otherValue == null) {
                return nullsOrderedLow ? 1 : -1;
            }
            return value.compareTo(otherValue);
        }

        @Override
        public boolean compare(int op, StoreDataValue other, boolean orderedNulls, boolean unknownRV)
                throws StandardException {
            return compare(op, other, orderedNulls, true, unknownRV);
        }

        @Override
        public boolean compare(
                int op,
                StoreDataValue other,
                boolean orderedNulls,
                boolean nullsOrderedLow,
                boolean unknownRV) throws StandardException {
            int comparison = compare(other, nullsOrderedLow);
            return switch (op) {
                case 1 -> comparison < 0;
                case 2 -> comparison == 0;
                case 3 -> comparison <= 0;
                case 4 -> comparison > 0;
                case 5 -> comparison >= 0;
                case 6 -> comparison != 0;
                default -> unknownRV;
            };
        }
    }
}
