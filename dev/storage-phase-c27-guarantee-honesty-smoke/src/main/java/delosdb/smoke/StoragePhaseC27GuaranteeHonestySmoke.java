package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.execution.VersionedStorageExecutionBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageAccessPath;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;
import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.apache.derby.impl.services.storetypes.EngineHeapTableAccessProof;
import org.apache.derby.impl.services.storetypes.EngineMvccTableAccess;
import org.apache.derby.iapi.store.types.DelosTableAccess;
import org.apache.derby.iapi.store.types.DelosTableCapabilities;
import org.apache.derby.iapi.store.types.DelosTableCapability;
import org.apache.derby.iapi.store.types.DelosTableGuarantee;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;

/** Phase C27 proof: capabilities remain structural; guarantees are semantic and checked at execution. */
public final class StoragePhaseC27GuaranteeHonestySmoke {
    private StoragePhaseC27GuaranteeHonestySmoke() {
    }

    public static void main(String[] args) throws Exception {
        verifyBaseOnlyAccessHasNoGuarantees();
        verifyMvccAndHeapGuaranteeDeclarations();
        verifySqlExecutionStillCrossesGuaranteedMvccPath();
        System.out.println("storage_phase_c27_guarantee_honesty: PASS");
    }

    private static void verifyBaseOnlyAccessHasNoGuarantees() {
        DelosTableAccess storeless = new StorelessBaseOnlyAccess();
        require(storeless.capabilities().values().isEmpty(),
                "storeless/base-only proof must advertise no physical capabilities");
        require(storeless.guarantees().isEmpty(),
                "storeless/base-only proof must advertise no semantic guarantees");
    }

    private static void verifyMvccAndHeapGuaranteeDeclarations() throws Exception {
        EngineMvccTableAccess mvcc = newMvccAccess("C27_GUARANTEE_DIRECT");
        require(mvcc.capabilities().supports(DelosTableCapability.FILTERABLE),
                "MVCC must keep structural filterable capability");
        require(mvcc.capabilities().supports(DelosTableCapability.MUTABLE),
                "MVCC must keep structural mutable capability");
        require(mvcc.guarantees().contains(DelosTableGuarantee.SNAPSHOT_ISOLATION),
                "MVCC must truthfully advertise snapshot isolation");
        require(mvcc.guarantees().contains(DelosTableGuarantee.DURABLE_RECOVERY_LOG),
                "MVCC must truthfully advertise a durable recovery log");
        require(!mvcc.guarantees().contains(DelosTableGuarantee.ROW_LOCKING),
                "MVCC must not claim row locking before a real lock/reservation primitive exists");

        EngineHeapTableAccessProof heap = new EngineHeapTableAccessProof(
                DelosTableIdentity.of("APP", "C27_HEAP_PROOF"),
                DelosTableShape.of(List.of(
                        new DelosTableShape.Column("ID", "INTEGER", false),
                        new DelosTableShape.Column("VALUE", "VARCHAR(40)", true))));
        require(heap.capabilities().supports(DelosTableCapability.FILTERABLE),
                "heap proof keeps structural capability honesty");
        require(heap.guarantees().contains(DelosTableGuarantee.ROW_LOCKING),
                "heap proof must declare Derby row-locking semantics as proof-only honesty");
        require(heap.guarantees().contains(DelosTableGuarantee.DURABLE_RECOVERY_LOG),
                "heap proof must declare Derby durable recovery-log semantics as proof-only honesty");
        require(!heap.guarantees().contains(DelosTableGuarantee.SNAPSHOT_ISOLATION),
                "heap proof must not claim MVCC snapshot isolation");
    }

    private static void verifySqlExecutionStillCrossesGuaranteedMvccPath() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect("storage-phase-c27-guarantee-honesty-db", true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE C27_GUARANTEE_SQL (id INT, value VARCHAR(40)) USING delos_mvcc");
            statement.executeUpdate("CREATE INDEX C27_GUARANTEE_SQL_ID_IDX ON C27_GUARANTEE_SQL(id)");
            statement.executeUpdate("INSERT INTO C27_GUARANTEE_SQL VALUES (1, 'alpha')");
            statement.executeUpdate("INSERT INTO C27_GUARANTEE_SQL VALUES (2, 'bravo')");

            try (ResultSet rows = statement.executeQuery("SELECT * FROM C27_GUARANTEE_SQL WHERE id = 2")) {
                require(rows.next(), "guaranteed MVCC SELECT should return one row");
                require(rows.getInt(1) == 2, "guaranteed MVCC SELECT should return id=2");
                require("bravo".equals(rows.getString(2)), "guaranteed MVCC SELECT should return bravo");
                require(!rows.next(), "guaranteed MVCC SELECT should return exactly one row");
            }

            VersionedStorageAccessPath accessPath = VersionedStorageSqlBridge.lastAccessPath()
                    .orElseThrow(() -> new IllegalStateException("guaranteed SELECT did not expose access path"));
            require("select-where".equals(accessPath.operation()),
                    "guaranteed SELECT should still use the contract equality path");
            require(VersionedStorageAccessPath.INDEX_SCAN.equals(accessPath.selectedAccessMethod()),
                    "guaranteed SELECT should still use the provider-owned index path");
            require("javacc-query-tree".equals(
                            VersionedStorageSqlBridge.lastRouteClassifierForTesting().orElseThrow()),
                    "guaranteed SELECT should still be classified by Derby JavaCC / QueryTreeNode");
        } finally {
            SmokeUtils.shutdown("storage-phase-c27-guarantee-honesty-db");
        }
    }

    private static EngineMvccTableAccess newMvccAccess(String tableName) throws Exception {
        VersionedStorageExecutionBridge bridge = VersionedStorageExecutionBridge.resolvedTableOperations();
        VersionedStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata metadata = new VersionedTableMetadata("APP", tableName);
        VersionedTable<Long, List<Object>> table = bridge.createTable(provider, metadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        io.github.ggeorg.delosdb.spi.storage.versioned.TxContext tx = coordinator.begin();
        VersionedIndexMetadata indexMetadata = new VersionedIndexMetadata(metadata, tableName + "_ID_IDX", "ID", true);
        VersionedIndex<Long, List<Object>> index = bridge.createIndex(table, indexMetadata, row -> row.get(0), tx.currentView());
        coordinator.commit(tx);

        return new EngineMvccTableAccess(
                DelosTableIdentity.of("APP", tableName),
                DelosTableShape.of(List.of(
                        new DelosTableShape.Column("ID", "INTEGER", false),
                        new DelosTableShape.Column("VALUE", "VARCHAR(40)", true))),
                table,
                bridge,
                List.of(new EngineMvccTableAccess.IndexBinding(indexMetadata.indexName(), "ID", true, index)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class StorelessBaseOnlyAccess implements DelosTableAccess {
        @Override
        public DelosTableIdentity identity() {
            return DelosTableIdentity.of("APP", "C27_STORELESS_BASE_ONLY");
        }

        @Override
        public DelosTableShape rowShape() {
            return DelosTableShape.of(List.of());
        }

        @Override
        public DelosTableCapabilities capabilities() {
            return DelosTableCapabilities.none();
        }
    }
}
