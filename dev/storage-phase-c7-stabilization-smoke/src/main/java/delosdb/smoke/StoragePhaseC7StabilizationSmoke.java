package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.execution.VersionedStorageExecutionBridge;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import org.apache.derby.iapi.store.types.StoreOrderable;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.iapi.types.RowLocation;
import org.apache.derby.impl.services.storetypes.EngineStoreRowLocationBridge;
import org.apache.derby.impl.store.access.heap.HeapRowLocation;

/**
 * Phase C7S stabilization smoke.
 *
 * <p>This checks two small review findings before the DELETE/UPDATE steps:
 * adapter-vs-adapter row-location behavior is real at runtime, and the SQL
 * bridge's direct table-operation bridge can no longer hide an empty provider
 * registry behind create/open methods.</p>
 */
public final class StoragePhaseC7StabilizationSmoke {
    private StoragePhaseC7StabilizationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        verifyEngineRowLocationAdapterBehavior();
        verifyResolvedTableBridgeRejectsProviderLookup();
        System.out.println("storage-phase-c7-stabilization-smoke: PASS");
    }

    private static void verifyEngineRowLocationAdapterBehavior() throws Exception {
        RowLocation left = EngineStoreRowLocationBridge.newEngineRowLocation();
        RowLocation right = EngineStoreRowLocationBridge.newEngineRowLocation();
        HeapRowLocation raw = new HeapRowLocation();

        require(left instanceof StoreRowLocation, "engine adapter must be a StoreRowLocation through RowLocation");
        require(right instanceof StoreRowLocation, "second engine adapter must be a StoreRowLocation through RowLocation");

        StoreRowLocation leftStore = EngineStoreRowLocationBridge.requireStoreRowLocation(left.getObject());
        StoreRowLocation rightStore = EngineStoreRowLocationBridge.requireStoreRowLocation(right.getObject());
        require(leftStore instanceof HeapRowLocation, "adapter object must unwrap to HeapRowLocation");
        require(rightStore instanceof HeapRowLocation, "second adapter object must unwrap to HeapRowLocation");

        require(left.compare((DataValueDescriptor) right) == 0, "adapter.compare(adapter) must not throw and must compare equal for fresh locations");
        require(right.compare((DataValueDescriptor) left) == 0, "adapter comparison must be symmetric");
        require(left.compare(StoreOrderable.ORDER_OP_EQUALS, (DataValueDescriptor) right, false, false),
                "adapter ORDER_OP_EQUALS compare must succeed");
        require(left.equals(right), "adapter.equals(adapter) must be value-based");
        require(right.equals(left), "adapter equality must be symmetric");
        require(left.equals(raw), "adapter.equals(raw HeapRowLocation) must be value-based");
        require(raw.equals(left), "raw HeapRowLocation.equals(adapter) must be value-based");
    }

    private static void verifyResolvedTableBridgeRejectsProviderLookup() {
        VersionedStorageExecutionBridge bridge = VersionedStorageExecutionBridge.resolvedTableOperations();
        try {
            bridge.createTable("delos_mvcc", new VersionedTableMetadata("APP", "C7S_FOOTGUN"));
            throw new AssertionError("resolved-table operation bridge must reject createTable provider lookup");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("provider lookup is unavailable"),
                    "resolved-table bridge should fail with an explicit provider-lookup message");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
