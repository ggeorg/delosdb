package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.execution.VersionedStorageExecutionBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlResult;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;
import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.derby.impl.services.storetypes.EngineMvccTableAccess;
import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosPredicate;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.store.types.DelosScan;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.iapi.store.types.StoreDataValue;

/**
 * Phase C23 proof: keep temporary regex SQL routing, but make MVCC UPDATE and
 * DELETE execute as read-then-mutate table-access operations using row
 * identities produced by DelosFilterableTableAccess.scan(...).
 */
public final class StoragePhaseC23MvccContractMutationSmoke {
    private StoragePhaseC23MvccContractMutationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        verifyAdapterReadThenMutateDirectly();
        verifySqlUpdateAndDeleteUseContractMutationPath();
        System.out.println("storage_phase_c23_mvcc_contract_mutation: PASS");
    }

    private static void verifyAdapterReadThenMutateDirectly() throws Exception {
        VersionedStorageExecutionBridge bridge = VersionedStorageExecutionBridge.resolvedTableOperations();
        VersionedStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata metadata = new VersionedTableMetadata("APP", "C23_ADAPTER_DIRECT");
        VersionedTable<Long, List<Object>> table = bridge.createTable(provider, metadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext insert = coordinator.begin();
        bridge.insert(table, 1L, List.of(1, "alpha"), insert);
        bridge.insert(table, 2L, List.of(2, "bravo"), insert);
        coordinator.commit(insert);

        TxContext indexBuild = coordinator.begin();
        VersionedIndexMetadata indexMetadata = new VersionedIndexMetadata(metadata, "C23_ADAPTER_DIRECT_ID_IDX", "ID", false);
        VersionedIndex<Long, List<Object>> index = bridge.createIndex(table, indexMetadata, row -> row.get(0), indexBuild.currentView());
        coordinator.commit(indexBuild);

        EngineMvccTableAccess access = new EngineMvccTableAccess(
                DelosTableIdentity.of("APP", "C23_ADAPTER_DIRECT"),
                DelosTableShape.of(List.of(
                        new DelosTableShape.Column("ID", "INTEGER", true),
                        new DelosTableShape.Column("VALUE", "VARCHAR(40)", true))),
                table,
                bridge,
                List.of(new EngineMvccTableAccess.IndexBinding("C23_ADAPTER_DIRECT_ID_IDX", "ID", true, index)));

        TxContext mutate = coordinator.begin();
        DelosAccessContext context = DelosAccessContext.builder(true)
                .put(EngineMvccTableAccess.TX_CONTEXT_KEY, mutate)
                .put(EngineMvccTableAccess.TX_VIEW_KEY, mutate.currentView())
                .build();
        try {
            DelosRow rowToUpdate = singleContractRow(access, context, "ID", 2);
            DelosRowIdentity updateIdentity = rowToUpdate.rowIdentity().orElseThrow();
            require(Long.valueOf(2L).equals(updateIdentity.nativeIdentity()),
                    "scan must expose the provider-native identity used by update");
            List<StoreDataValue> replacement = new ArrayList<>(rowToUpdate.values());
            replacement.set(1, EngineMvccTableAccess.value("bravo-updated"));
            require(access.update(context, updateIdentity, DelosRow.withoutIdentity(List.copyOf(replacement))).affectedRows() == 1L,
                    "contract update must affect exactly one row identity");

            DelosRow rowToDelete = singleContractRow(access, context, "ID", 1);
            DelosRowIdentity deleteIdentity = rowToDelete.rowIdentity().orElseThrow();
            require(Long.valueOf(1L).equals(deleteIdentity.nativeIdentity()),
                    "scan must expose the provider-native identity used by delete");
            require(access.delete(context, deleteIdentity).affectedRows() == 1L,
                    "contract delete must affect exactly one row identity");
            coordinator.commit(mutate);
        } catch (RuntimeException e) {
            coordinator.abort(mutate);
            throw e;
        }

        TxContext read = coordinator.begin();
        try {
            List<VersionedRow<Long, List<Object>>> visibleRows = bridge.scanAll(table, read.currentView());
            require(visibleRows.size() == 1, "direct contract mutation should leave one visible row");
            VersionedRow<Long, List<Object>> visible = visibleRows.get(0);
            require(Long.valueOf(2L).equals(visible.key()), "remaining row should be the updated row identity");
            require(List.of(2, "bravo-updated").equals(visible.value()),
                    "remaining row should contain the contract-updated values");
            coordinator.commit(read);
        } catch (RuntimeException e) {
            coordinator.abort(read);
            throw e;
        }
    }

    private static DelosRow singleContractRow(
            EngineMvccTableAccess access,
            DelosAccessContext context,
            String columnName,
            Object value) {
        List<DelosPredicate> mutableFilters = new ArrayList<>();
        mutableFilters.add(DelosPredicate.equalsTo(columnName, EngineMvccTableAccess.value(value)));
        List<DelosRow> rows = new ArrayList<>();
        try (DelosScan scan = access.scan(context, mutableFilters, DelosProjection.all())) {
            while (scan.next()) {
                rows.add(scan.row());
            }
        }
        require(mutableFilters.isEmpty(), "equality predicate should be pushed before mutation");
        require(rows.size() == 1, "expected one contract row for " + columnName + "=" + value + ", got " + rows.size());
        require(rows.get(0).rowIdentity().isPresent(), "mutation scan row must carry row identity");
        return rows.get(0);
    }

    private static void verifySqlUpdateAndDeleteUseContractMutationPath() throws SQLException {
        SqlBridgePlan plan = new SqlBridgePlan("C23_CONTRACT_MUTATION");
        requireUpdateCount(plan.execute("CREATE TABLE " + plan.tableName()
                + " (id INT, value VARCHAR(40)) USING delos_mvcc"), 0L, "create table");
        requireUpdateCount(plan.execute("CREATE INDEX C23_CONTRACT_MUTATION_ID_IDX ON "
                + plan.tableName() + "(id)"), 0L, "create index");
        requireUpdateCount(plan.execute("INSERT INTO " + plan.tableName()
                + " VALUES (1, 'alpha')"), 1L, "insert alpha");
        requireUpdateCount(plan.execute("INSERT INTO " + plan.tableName()
                + " VALUES (2, 'bravo')"), 1L, "insert bravo");

        requireUpdateCount(plan.execute("UPDATE " + plan.tableName()
                + " SET value = 'bravo-updated' WHERE id = 2"), 1L, "contract update");
        requireUpdateCount(plan.execute("DELETE FROM " + plan.tableName()
                + " WHERE id = 1"), 1L, "contract delete");

        requireSingleRow(plan.execute("SELECT * FROM " + plan.tableName() + " WHERE id = 2"), 2, "bravo-updated");
        requireNoRows(plan.execute("SELECT * FROM " + plan.tableName() + " WHERE id = 1"));
    }

    private static void requireUpdateCount(
            VersionedStorageSqlResult result,
            long expected,
            String label) {
        if (result == null) {
            throw new IllegalStateException(label + " was not handled by VersionedStorageSqlBridge");
        }
        if (result.returnsRows()) {
            throw new IllegalStateException(label + " unexpectedly returned rows");
        }
        if (result.updateCount() != expected) {
            throw new IllegalStateException(label + " update count expected=" + expected
                    + " actual=" + result.updateCount());
        }
    }

    private static void requireSingleRow(VersionedStorageSqlResult result, int expectedId, String expectedValue)
            throws SQLException {
        if (result == null || !result.returnsRows()) {
            throw new IllegalStateException("select was not handled as rows by VersionedStorageSqlBridge");
        }
        try (ResultSet rows = result.resultSet()) {
            if (!rows.next()) {
                throw new IllegalStateException("expected one MVCC row");
            }
            int actualId = rows.getInt(1);
            String actualValue = rows.getString(2);
            if (actualId != expectedId || !Objects.equals(expectedValue, actualValue)) {
                throw new IllegalStateException("unexpected MVCC row: id=" + actualId + " value=" + actualValue);
            }
            if (rows.next()) {
                throw new IllegalStateException("expected exactly one MVCC row");
            }
        }
    }

    private static void requireNoRows(VersionedStorageSqlResult result) throws SQLException {
        if (result == null || !result.returnsRows()) {
            throw new IllegalStateException("select was not handled as rows by VersionedStorageSqlBridge");
        }
        try (ResultSet rows = result.resultSet()) {
            if (rows.next()) {
                throw new IllegalStateException("expected no MVCC rows after contract delete");
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record SqlBridgePlan(String tableName) {
        private VersionedStorageSqlResult execute(String sql) throws SQLException {
            return VersionedStorageSqlBridge.tryExecute(sql, this, true, Connection.TRANSACTION_READ_COMMITTED);
        }
    }
}
