package io.github.ggeorg.delosdb.engine.extension.cost;

/**
 * Provider-neutral cost input captured from Derby's StoreCostController path.
 *
 * <p>This request intentionally contains only scalar values. It does not expose
 * Derby optimizer, conglomerate, row-template, or scan-key objects.</p>
 */
public record CostModelRequest(
        long conglomerateId,
        int factoryId,
        int scanType,
        long inputRowCount,
        int groupSize,
        boolean forUpdate,
        boolean reopenScan,
        int accessType,
        double derbyCost,
        long derbyEstimatedRows
) {
}
