package io.github.ggeorg.delosdb.engine.extension.cost;

import java.util.Optional;

/**
 * Deterministic built-in B-tree cost model used for the native adapter proof.
 * Factory id 1 is Derby's B-tree access method.
 */
final class BuiltInBTreeCostModelProvider implements CostModelProvider {
    static final int BTREE_FACTORY_ID = 1;
    static final BuiltInBTreeCostModelProvider INSTANCE = new BuiltInBTreeCostModelProvider();

    private BuiltInBTreeCostModelProvider() {
    }

    @Override
    public String name() {
        return "btree";
    }

    @Override
    public int accessMethodFactoryId() {
        return BTREE_FACTORY_ID;
    }

    @Override
    public Optional<CostModelEstimate> estimateScanCost(CostModelRequest request) {
        if (request.factoryId() != accessMethodFactoryId() || !request.hasSafeDerbyBaseline()) {
            return Optional.empty();
        }

        long rows = request.derbyEstimatedRows() > 0
                ? request.derbyEstimatedRows()
                : Math.max(1L, request.inputRowCount());
        double startup = Math.max(1.0d, log2(Math.max(2L, request.inputRowCount())));
        double total = startup + Math.max(1.0d, rows);
        return Optional.of(new CostModelEstimate(
                startup,
                total,
                rows,
                "built-in btree store-cost adapter estimate"));
    }

    private static double log2(long value) {
        return Math.log(value) / Math.log(2.0d);
    }
}
