package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.execution.VersionedStorageExecutionBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;
import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;

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
 * Phase C28 proof: the caller side, not the MVCC adapter, evaluates a leftover
 * NOT_EQUAL predicate after an equality predicate has been pushed down.
 */
public final class StoragePhaseC28LeftoverPredicateSmoke {
    private StoragePhaseC28LeftoverPredicateSmoke() {
    }

    public static void main(String[] args) throws Exception {
        verifyNotEqualLeftoverFilteringAboveMvccAccess();
        System.out.println("storage_phase_c28_leftover_predicate: PASS");
    }

    private static void verifyNotEqualLeftoverFilteringAboveMvccAccess() throws Exception {
        VersionedStorageExecutionBridge bridge = VersionedStorageExecutionBridge.resolvedTableOperations();
        VersionedStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata metadata = new VersionedTableMetadata("APP", "C28_LEFTOVER_DIRECT");
        VersionedTable<Long, List<Object>> table = bridge.createTable(provider, metadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext insert = coordinator.begin();
        bridge.insert(table, 1L, List.of(1, 7, "keep"), insert);
        bridge.insert(table, 2L, List.of(2, 7, "drop"), insert);
        bridge.insert(table, 3L, List.of(3, 8, "other"), insert);
        coordinator.commit(insert);

        TxContext indexBuild = coordinator.begin();
        VersionedIndexMetadata indexMetadata = new VersionedIndexMetadata(
                metadata,
                "C28_LEFTOVER_DIRECT_GROUP_IDX",
                "GROUP_ID",
                false);
        VersionedIndex<Long, List<Object>> index = bridge.createIndex(
                table,
                indexMetadata,
                row -> row.get(1),
                indexBuild.currentView());
        coordinator.commit(indexBuild);

        EngineMvccTableAccess access = new EngineMvccTableAccess(
                DelosTableIdentity.of("APP", "C28_LEFTOVER_DIRECT"),
                DelosTableShape.of(List.of(
                        new DelosTableShape.Column("ID", "INTEGER", true),
                        new DelosTableShape.Column("GROUP_ID", "INTEGER", true),
                        new DelosTableShape.Column("VALUE", "VARCHAR(40)", true))),
                table,
                bridge,
                List.of(new EngineMvccTableAccess.IndexBinding(
                        "C28_LEFTOVER_DIRECT_GROUP_IDX",
                        "GROUP_ID",
                        false,
                        index)));

        TxContext read = coordinator.begin();
        List<DelosPredicate> mutableFilters = new ArrayList<>();
        mutableFilters.add(DelosPredicate.equalsTo("GROUP_ID", EngineMvccTableAccess.value(7)));
        mutableFilters.add(new DelosPredicate(
                "VALUE",
                DelosPredicateOperator.NOT_EQUAL,
                List.of(EngineMvccTableAccess.value("drop"))));

        List<DelosRow> contractRows = new ArrayList<>();
        try (DelosScan scan = access.scan(
                DelosAccessContext.builder(true)
                        .put(EngineMvccTableAccess.TX_CONTEXT_KEY, read)
                        .put(EngineMvccTableAccess.TX_VIEW_KEY, read.currentView())
                        .build(),
                mutableFilters,
                DelosProjection.all())) {
            while (scan.next()) {
                contractRows.add(scan.row());
            }
        } finally {
            coordinator.commit(read);
        }

        require(contractRows.size() == 2,
                "adapter equality pushdown should return both GROUP_ID=7 rows before caller-side leftover filtering");
        require(mutableFilters.size() == 1,
                "adapter must remove only the pushed equality predicate");
        require(mutableFilters.get(0).operator() == DelosPredicateOperator.NOT_EQUAL,
                "NOT_EQUAL must remain as a caller-side leftover predicate");

        List<String> columnNames = List.of("ID", "GROUP_ID", "VALUE");
        List<List<Object>> nativeRows = materializeNativeRows(contractRows);

        List<List<Object>> filteredNativeRows = VersionedStorageSqlBridge.applyLeftoverPredicatesForTesting(
                columnNames,
                nativeRows,
                mutableFilters);
        require(filteredNativeRows.size() == 1,
                "caller-side NOT_EQUAL filtering must leave exactly one native row");
        require(Integer.valueOf(1).equals(filteredNativeRows.get(0).get(0)),
                "caller-side native-row filtering must keep id=1");
        require("keep".equals(filteredNativeRows.get(0).get(2)),
                "caller-side native-row filtering must keep VALUE='keep'");

        List<DelosRow> filteredContractRows = VersionedStorageSqlBridge.applyLeftoverPredicatesToContractRowsForTesting(
                columnNames,
                contractRows,
                mutableFilters);
        require(filteredContractRows.size() == 1,
                "caller-side NOT_EQUAL filtering must leave exactly one contract row");
        require(Integer.valueOf(1).equals(EngineMvccTableAccess.nativeValue(filteredContractRows.get(0).values().get(0))),
                "caller-side contract-row filtering must keep id=1");
        require("keep".equals(EngineMvccTableAccess.nativeValue(filteredContractRows.get(0).values().get(2))),
                "caller-side contract-row filtering must keep VALUE='keep'");
    }

    private static List<List<Object>> materializeNativeRows(List<DelosRow> rows) {
        List<List<Object>> nativeRows = new ArrayList<>(rows.size());
        for (DelosRow row : rows) {
            List<Object> values = new ArrayList<>(row.values().size());
            row.values().forEach(value -> values.add(EngineMvccTableAccess.nativeValue(value)));
            nativeRows.add(List.copyOf(values));
        }
        return List.copyOf(nativeRows);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
