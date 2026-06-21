package delosdb.smoke;

import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosFilterableTableAccess;
import org.apache.derby.iapi.store.types.DelosIndexableTableAccess;
import org.apache.derby.iapi.store.types.DelosMutableTableAccess;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.store.types.DelosTableCapability;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.impl.services.storetypes.EngineHeapTableAccessProof;

/** Phase C22 proof smoke: heap contracts compile but heap SQL is not rerouted. */
public final class StoragePhaseC22HeapHonestySmoke {
    private StoragePhaseC22HeapHonestySmoke() {
    }

    public static void main(String[] args) {
        EngineHeapTableAccessProof proof = new EngineHeapTableAccessProof(
                DelosTableIdentity.of("APP", "C22_HEAP_PROOF"),
                DelosTableShape.of(List.of(
                        new DelosTableShape.Column("ID", "INTEGER", false),
                        new DelosTableShape.Column("VALUE", "VARCHAR(40)", true))));

        require(proof instanceof DelosFilterableTableAccess,
                "heap proof must type-check as filterable access");
        require(proof instanceof DelosIndexableTableAccess,
                "heap proof must type-check as indexable access");
        require(proof instanceof DelosMutableTableAccess,
                "heap proof must type-check as mutable access");
        require(proof.capabilities().supports(DelosTableCapability.FILTERABLE),
                "heap proof must advertise filterable capability");
        require(proof.capabilities().supports(DelosTableCapability.MUTABLE),
                "heap proof must advertise mutable capability");
        require(EngineHeapTableAccessProof.TRANSACTION_CONTROLLER_KEY.name().contains("transactionController"),
                "heap proof must expose typed TransactionController context key");
        require(EngineHeapTableAccessProof.LOCK_LEVEL_KEY.type() == Integer.class,
                "heap proof must model Derby lock-level context");
        require(EngineHeapTableAccessProof.ISOLATION_LEVEL_KEY.type() == Integer.class,
                "heap proof must model Derby isolation-level context");

        StoreRowLocation storeRowLocation = new TestStoreRowLocation();
        DelosRowIdentity rowIdentity = EngineHeapTableAccessProof.rowIdentity(storeRowLocation);
        require("heap".equals(rowIdentity.providerName()),
                "heap row identity must keep provider identity");
        require(rowIdentity.nativeIdentity() instanceof StoreRowLocation,
                "heap row identity must wrap the native StoreRowLocation/RowLocation object");

        expectUnsupported(() -> proof.scan(
                DelosAccessContext.empty(true),
                new ArrayList<>(),
                DelosProjection.all()),
                "C22 heap table-access proof only");

        System.out.println("storage_phase_c22_heap_honesty: PASS");
    }

    private static void expectUnsupported(Runnable action, String expectedMessageFragment) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            require(expected.getMessage().contains(expectedMessageFragment),
                    "unexpected UnsupportedOperationException message: " + expected.getMessage());
            return;
        }
        throw new IllegalStateException("expected UnsupportedOperationException");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class TestStoreRowLocation implements StoreRowLocation {
    }
}
