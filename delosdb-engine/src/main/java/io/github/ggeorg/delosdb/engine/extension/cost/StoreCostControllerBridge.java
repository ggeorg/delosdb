package io.github.ggeorg.delosdb.engine.extension.cost;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.StoreCostController;
import org.apache.derby.iapi.store.access.StoreCostResult;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.iapi.types.RowLocation;
import org.apache.derby.shared.common.error.StandardException;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal adapter from DelosDB cost-model providers to Derby's native
 * {@link StoreCostController} path.
 */
public final class StoreCostControllerBridge {
    private static final int FACTORY_ID_MASK = 0x0f;
    private static final CostModelProviderResolver BUILT_IN_RESOLVER = CostModelProviderResolver.builtIns();

    private StoreCostControllerBridge() {
    }

    public static StoreCostController wrap(long conglomerateId, StoreCostController delegate) {
        Objects.requireNonNull(delegate, "delegate");
        CostModelMode mode = CostModelMode.fromSystemProperties();
        int factoryId = factoryId(conglomerateId);
        if (!mode.probesProviderCost()) {
            return delegate;
        }

        Optional<CostModelProvider> provider = BUILT_IN_RESOLVER.findEnabledForFactoryId(factoryId);
        if (provider.isEmpty()) {
            return delegate;
        }
        return new Adapter(conglomerateId, factoryId, delegate, mode, provider.get());
    }

    private static int factoryId(long conglomerateId) {
        return (int) (conglomerateId & FACTORY_ID_MASK);
    }

    private static final class Adapter implements StoreCostController {
        private final long conglomerateId;
        private final int factoryId;
        private final StoreCostController delegate;
        private final CostModelMode mode;
        private final CostModelProvider provider;

        private Adapter(
                long conglomerateId,
                int factoryId,
                StoreCostController delegate,
                CostModelMode mode,
                CostModelProvider provider) {
            this.conglomerateId = conglomerateId;
            this.factoryId = factoryId;
            this.delegate = delegate;
            this.mode = mode;
            this.provider = Objects.requireNonNull(provider, "provider");
        }

        @Override
        public void close() throws StandardException {
            delegate.close();
        }

        @Override
        public double getFetchFromRowLocationCost(
                FormatableBitSet validColumns,
                int accessType) throws StandardException {
            return delegate.getFetchFromRowLocationCost(validColumns, accessType);
        }

        @Override
        public double getFetchFromFullKeyCost(
                FormatableBitSet validColumns,
                int accessType) throws StandardException {
            return delegate.getFetchFromFullKeyCost(validColumns, accessType);
        }

        @Override
        public void getScanCost(
                int scanType,
                long rowCount,
                int groupSize,
                boolean forUpdate,
                FormatableBitSet scanColumnList,
                DataValueDescriptor[] template,
                DataValueDescriptor[] startKeyValue,
                int startSearchOperator,
                DataValueDescriptor[] stopKeyValue,
                int stopSearchOperator,
                boolean reopenScan,
                int accessType,
                StoreCostResult costResult) throws StandardException {
            delegate.getScanCost(
                    scanType,
                    rowCount,
                    groupSize,
                    forUpdate,
                    scanColumnList,
                    template,
                    startKeyValue,
                    startSearchOperator,
                    stopKeyValue,
                    stopSearchOperator,
                    reopenScan,
                    accessType,
                    costResult);

            double derbyCost = costResult.getEstimatedCost();
            long derbyRows = costResult.getEstimatedRowCount();
            CostModelRequest request = new CostModelRequest(
                    conglomerateId,
                    factoryId,
                    scanType,
                    rowCount,
                    groupSize,
                    forUpdate,
                    reopenScan,
                    accessType,
                    derbyCost,
                    derbyRows);
            Optional<CostModelEstimate> estimate = provider.estimateScanCost(request);
            CostModelProbe probe = probeFor(request, estimate);
            if (mode.consumesProviderCost() && probe.canSafelyReplaceDerbyCost()) {
                CostModelEstimate value = estimate.get();
                costResult.setEstimatedCost(value.totalCost());
                costResult.setEstimatedRowCount(value.estimatedRows());
                probe = probe.withConsumed(true);
            }
            CostModelDiagnostics.record(probe);
        }

        @Override
        public RowLocation newRowLocationTemplate() throws StandardException {
            return delegate.newRowLocationTemplate();
        }

        @Override
        public long getEstimatedRowCount() throws StandardException {
            return delegate.getEstimatedRowCount();
        }

        @Override
        public void setEstimatedRowCount(long count) throws StandardException {
            delegate.setEstimatedRowCount(count);
        }

        private CostModelProbe probeFor(
                CostModelRequest request,
                Optional<CostModelEstimate> estimate) {
            String modeName = mode.name().toLowerCase(Locale.ROOT);
            if (estimate.isEmpty()) {
                return CostModelProbe.unavailable(
                        modeName,
                        provider.name(),
                        request.conglomerateId(),
                        request.factoryId(),
                        request.scanType(),
                        request.inputRowCount(),
                        request.derbyCost(),
                        request.derbyEstimatedRows(),
                        "provider returned no estimate for factory id " + request.factoryId());
            }
            CostModelEstimate value = estimate.get();
            return new CostModelProbe(
                    modeName,
                    provider.name(),
                    request.conglomerateId(),
                    request.factoryId(),
                    request.scanType(),
                    request.inputRowCount(),
                    request.derbyCost(),
                    request.derbyEstimatedRows(),
                    true,
                    value.startupCost(),
                    value.totalCost(),
                    value.estimatedRows(),
                    false,
                    value.explanation());
        }
    }
}
