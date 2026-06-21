package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.execution.VersionedStorageExecutionBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageAccessPath;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlResult;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;
import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.impl.services.storetypes.EngineMvccTableAccess;
import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosPredicate;
import org.apache.derby.iapi.store.types.DelosPredicateOperator;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosScan;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;

/**
 * Phase C21 proof: keep temporary regex SQL routing, but execute one real
 * delos_mvcc equality SELECT through DelosFilterableTableAccess.
 */
public final class StoragePhaseC21MvccContractSelectSmoke {
    private StoragePhaseC21MvccContractSelectSmoke() {
    }

    public static void main(String[] args) throws Exception {
        verifyAdapterFilterPushdownDirectly();
        verifyJdbcSelectWhereEqualsUsesContractAdapter();
        System.out.println("storage_phase_c21_mvcc_contract_select: PASS");
    }

    private static void verifyAdapterFilterPushdownDirectly() throws Exception {
        VersionedStorageExecutionBridge bridge = VersionedStorageExecutionBridge.resolvedTableOperations();
        VersionedStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata metadata = new VersionedTableMetadata("APP", "C21_ADAPTER_DIRECT");
        VersionedTable<Long, List<Object>> table = bridge.createTable(provider, metadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext insert = coordinator.begin();
        bridge.insert(table, 1L, List.of(1, "alpha"), insert);
        bridge.insert(table, 2L, List.of(2, "bravo"), insert);
        coordinator.commit(insert);

        TxContext indexBuild = coordinator.begin();
        VersionedIndexMetadata indexMetadata = new VersionedIndexMetadata(metadata, "C21_ADAPTER_DIRECT_ID_IDX", "ID", false);
        VersionedIndex<Long, List<Object>> index = bridge.createIndex(table, indexMetadata, row -> row.get(0), indexBuild.currentView());
        coordinator.commit(indexBuild);

        EngineMvccTableAccess access = new EngineMvccTableAccess(
                DelosTableIdentity.of("APP", "C21_ADAPTER_DIRECT"),
                DelosTableShape.of(List.of(
                        new DelosTableShape.Column("ID", "INTEGER", true),
                        new DelosTableShape.Column("VALUE", "VARCHAR(40)", true))),
                table,
                bridge,
                List.of(new EngineMvccTableAccess.IndexBinding("C21_ADAPTER_DIRECT_ID_IDX", "ID", true, index)));

        TxContext read = coordinator.begin();
        List<DelosPredicate> mutableFilters = new ArrayList<>();
        mutableFilters.add(DelosPredicate.equalsTo("ID", EngineMvccTableAccess.value(2)));
        mutableFilters.add(new DelosPredicate("VALUE", DelosPredicateOperator.NOT_EQUAL,
                List.of(EngineMvccTableAccess.value("unsupported-above-access"))));
        List<DelosRow> rows = new ArrayList<>();
        try (DelosScan scan = access.scan(
                DelosAccessContext.builder(true)
                        .put(EngineMvccTableAccess.TX_CONTEXT_KEY, read)
                        .put(EngineMvccTableAccess.TX_VIEW_KEY, read.currentView())
                        .build(),
                mutableFilters,
                DelosProjection.all())) {
            while (scan.next()) {
                rows.add(scan.row());
            }
        } finally {
            coordinator.commit(read);
        }

        require(rows.size() == 1, "contract scan should return the equality-selected row");
        require(Long.valueOf(2L).equals(rows.get(0).rowIdentity().orElseThrow().nativeIdentity()),
                "contract scan must expose provider-native row identity");
        require(Integer.valueOf(2).equals(EngineMvccTableAccess.nativeValue(rows.get(0).values().get(0))),
                "contract scan must return row values through DelosRow");
        require(mutableFilters.size() == 1,
                "MVCC adapter must remove pushed equality predicate and leave unsupported predicates above access");
        require(mutableFilters.get(0).operator() == DelosPredicateOperator.NOT_EQUAL,
                "unsupported predicate must remain in the mutable filter list");
        VersionedStorageAccessPath accessPath = access.lastAccessPath()
                .orElseThrow(() -> new IllegalStateException("adapter did not expose access path"));
        require(VersionedStorageAccessPath.INDEX_SCAN.equals(accessPath.selectedAccessMethod()),
                "adapter equality scan should use provider-owned index when available");
    }

    private static void verifyJdbcSelectWhereEqualsUsesContractAdapter() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect("storage-phase-c21-mvcc-contract-select-db", true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE C21_CONTRACT_SELECT (id INT, value VARCHAR(40)) USING delos_mvcc");
            statement.executeUpdate("CREATE INDEX C21_CONTRACT_SELECT_ID_IDX ON C21_CONTRACT_SELECT(id)");
            statement.executeUpdate("INSERT INTO C21_CONTRACT_SELECT VALUES (1, 'alpha')");
            statement.executeUpdate("INSERT INTO C21_CONTRACT_SELECT VALUES (2, 'bravo')");

            try (ResultSet rows = statement.executeQuery("SELECT * FROM C21_CONTRACT_SELECT WHERE id = 2")) {
                require(rows.next(), "SQL equality SELECT should return one row");
                require(rows.getInt(1) == 2, "SQL equality SELECT should return id=2");
                require("bravo".equals(rows.getString(2)), "SQL equality SELECT should return bravo");
                require(!rows.next(), "SQL equality SELECT should return exactly one row");
            }

            VersionedStorageAccessPath accessPath = VersionedStorageSqlBridge.lastAccessPath()
                    .orElseThrow(() -> new IllegalStateException("SQL equality SELECT did not expose access path"));
            require("select-where".equals(accessPath.operation()),
                    "SQL equality SELECT should still report select-where operation");
            require(VersionedStorageAccessPath.INDEX_SCAN.equals(accessPath.selectedAccessMethod()),
                    "SQL equality SELECT should use the MVCC contract adapter and provider-owned index");
            require("C21_CONTRACT_SELECT_ID_IDX".equalsIgnoreCase(accessPath.selectedIndex()),
                    "SQL equality SELECT should report the provider-owned index selected by the adapter");
        } finally {
            SmokeUtils.shutdown("storage-phase-c21-mvcc-contract-select-db");
        }
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
            if (actualId != expectedId || !expectedValue.equals(actualValue)) {
                throw new IllegalStateException("unexpected MVCC row: id=" + actualId + " value=" + actualValue);
            }
            if (rows.next()) {
                throw new IllegalStateException("expected exactly one MVCC row");
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

}
