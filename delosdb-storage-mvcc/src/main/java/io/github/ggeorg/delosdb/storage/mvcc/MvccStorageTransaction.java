package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxView;

import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosContextKey;

/** Storage-api transaction facade for the native MVCC transaction context. */
public final class MvccStorageTransaction {
    public static final DelosContextKey<TxContext> TX_CONTEXT_KEY =
            DelosContextKey.of("delosdb.storage.mvcc.tx.context", TxContext.class);

    public static final DelosContextKey<TxView> TX_VIEW_KEY =
            DelosContextKey.of("delosdb.storage.mvcc.tx.view", TxView.class);

    private final TxContext context;

    MvccStorageTransaction(TxContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public long transactionId() {
        return context.transactionId();
    }

    public TxView currentView() {
        return context.currentView();
    }

    public DelosAccessContext accessContext() {
        return accessContext(true);
    }

    public DelosAccessContext accessContext(boolean physicalAccessAllowed) {
        return DelosAccessContext.builder(physicalAccessAllowed)
                .put(TX_CONTEXT_KEY, context)
                .put(TX_VIEW_KEY, context.currentView())
                .build();
    }

    TxContext context() {
        return context;
    }
}
