package io.github.ggeorg.delosdb.engine.extension.cost;

import java.util.Optional;

/**
 * Deterministic built-in heap cost model used to prove CostModelProvider v2.
 * Factory id 0 is Derby's heap access method.
 */
final class BuiltInHeapCostModelProvider implements CostModelProvider {
    static final int HEAP_FACTORY_ID = 0;
    static final BuiltInHeapCostModelProvider INSTANCE = new BuiltInHeapCostModelProvider();

    private BuiltInHeapCostModelProvider() {
    }

    @Override
    public String name() {
        return "heap";
    }

    @Override
    public int accessMethodFactoryId() {
        return HEAP_FACTORY_ID;
    }

    @Override
    public Optional<CostModelEstimate> estimateScanCost(CostModelRequest request) {
        if (request.factoryId() != accessMethodFactoryId()) {
            return Optional.empty();
        }

        long inputRows = Math.max(1L, request.inputRowCount());
        long rows = request.derbyEstimatedRows() > 0
                ? request.derbyEstimatedRows()
                : inputRows;

        // A heap scan has no ordered-key startup advantage. Keep startup small
        // and make total cost mostly proportional to the rows Derby expects to
        // scan. The constants are intentionally simple: this provider proves
        // dispatch and adaptation, not a production cost formula.
        double startup = 0.0d;
        double total = Math.max(1.0d, rows * 1.25d);
        return Optional.of(new CostModelEstimate(
                startup,
                total,
                rows,
                "built-in heap store-cost adapter estimate"));
    }
}
