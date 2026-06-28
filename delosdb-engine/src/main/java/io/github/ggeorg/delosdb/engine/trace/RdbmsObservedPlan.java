package io.github.ggeorg.delosdb.engine.trace;

import java.util.Objects;

/**
 * Small reader-facing plan observation derived from already-captured trace events.
 *
 * <p>This is deliberately not an optimizer API and not a planner replacement. It observes the
 * physical table access path that inherited Derby execution exposed through the existing trace
 * stream. The observation is useful as a safe Phase 25 baseline before cost-model, predicate
 * pushdown, or storage-provider-aware planning experiments.</p>
 */
public record RdbmsObservedPlan(
        RdbmsPlanNodeKind nodeKind,
        boolean physicalPlanObserved,
        String table,
        String index,
        long conglomerateId,
        boolean forUpdate,
        boolean oneRowScan,
        boolean hasQualifiers,
        RdbmsStorageProviderKind storageProvider,
        RdbmsStorageAccessKind storageAccessKind,
        boolean predicatePushdownObserved,
        boolean keyedAccessObserved) {

    public RdbmsObservedPlan {
        nodeKind = Objects.requireNonNull(nodeKind, "nodeKind");
        table = safe(table);
        index = safe(index);
        storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        storageAccessKind = Objects.requireNonNull(storageAccessKind, "storageAccessKind");
    }

    public static RdbmsObservedPlan observe(Iterable<RdbmsTraceEvent> events) {
        Objects.requireNonNull(events, "events");

        Builder builder = new Builder();
        for (RdbmsTraceEvent event : events) {
            builder.accept(event);
        }
        return builder.build();
    }

    public String format() {
        return "plan node: " + nodeKind + System.lineSeparator()
                + "physical plan observed: " + physicalPlanObserved + System.lineSeparator()
                + "table: " + table + System.lineSeparator()
                + "index: " + index + System.lineSeparator()
                + "conglomerate id: " + conglomerateId + System.lineSeparator()
                + "storage provider: " + storageProvider + System.lineSeparator()
                + "storage access kind: " + storageAccessKind + System.lineSeparator()
                + "predicate pushdown observed: " + predicatePushdownObserved + System.lineSeparator()
                + "keyed access observed: " + keyedAccessObserved;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean parseBoolean(String value) {
        return Boolean.parseBoolean(value);
    }

    private static long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static RdbmsStorageProviderKind parseStorageProvider(String value) {
        if (value == null || value.isBlank()) {
            return RdbmsStorageProviderKind.UNKNOWN;
        }
        try {
            return RdbmsStorageProviderKind.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return RdbmsStorageProviderKind.UNKNOWN;
        }
    }

    private static RdbmsStorageAccessKind parseStorageAccess(String value) {
        if (value == null || value.isBlank()) {
            return RdbmsStorageAccessKind.UNKNOWN;
        }
        try {
            return RdbmsStorageAccessKind.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return RdbmsStorageAccessKind.UNKNOWN;
        }
    }

    private static RdbmsPlanNodeKind planNodeFor(RdbmsStorageAccessKind accessKind) {
        if (accessKind == RdbmsStorageAccessKind.BTREE_INDEX_SCAN
                || accessKind == RdbmsStorageAccessKind.BTREE_KEYED_LOOKUP) {
            return RdbmsPlanNodeKind.INDEX_SCAN;
        }
        if (accessKind == RdbmsStorageAccessKind.HEAP_SCAN
                || accessKind == RdbmsStorageAccessKind.MVCC_SCAN) {
            return RdbmsPlanNodeKind.TABLE_SCAN;
        }
        return RdbmsPlanNodeKind.UNKNOWN;
    }

    private static final class Builder {
        private RdbmsPlanNodeKind nodeKind = RdbmsPlanNodeKind.UNKNOWN;
        private boolean physicalPlanObserved;
        private String table = "";
        private String index = "";
        private long conglomerateId = -1L;
        private boolean forUpdate;
        private boolean oneRowScan;
        private boolean hasQualifiers;
        private RdbmsStorageProviderKind storageProvider = RdbmsStorageProviderKind.UNKNOWN;
        private RdbmsStorageAccessKind storageAccessKind = RdbmsStorageAccessKind.UNKNOWN;
        private boolean predicatePushdownObserved;
        private boolean keyedAccessObserved;

        void accept(RdbmsTraceEvent event) {
            if (event == null || !"table-scan".equals(event.subject())) {
                return;
            }
            if (event.stage() == RdbmsLifecycleStage.PHYSICAL_PLAN_CREATED) {
                acceptPhysicalPlan(event);
            } else if (event.stage() == RdbmsLifecycleStage.STORAGE_ACCESSED) {
                acceptStorageAccess(event);
            }
        }

        private void acceptPhysicalPlan(RdbmsTraceEvent event) {
            physicalPlanObserved = true;
            nodeKind = nodeKind == RdbmsPlanNodeKind.UNKNOWN
                    ? RdbmsPlanNodeKind.TABLE_SCAN
                    : nodeKind;
            table = safe(event.attributes().get("table"));
            index = safe(event.attributes().get("index"));
            conglomerateId = parseLong(event.attributes().get("conglomerateId"), conglomerateId);
            forUpdate = parseBoolean(event.attributes().get("forUpdate"));
            oneRowScan = parseBoolean(event.attributes().get("oneRowScan"));
            hasQualifiers = parseBoolean(event.attributes().get("hasQualifiers"));
        }

        private void acceptStorageAccess(RdbmsTraceEvent event) {
            table = safe(event.attributes().get("table"));
            index = safe(event.attributes().get("index"));
            conglomerateId = parseLong(event.attributes().get("conglomerateId"), conglomerateId);
            storageProvider = parseStorageProvider(event.attributes().get("provider"));
            storageAccessKind = parseStorageAccess(event.attributes().get("accessKind"));
            nodeKind = planNodeFor(storageAccessKind);
            predicatePushdownObserved = parseBoolean(event.attributes().get("predicatePushdown"));
            keyedAccessObserved = parseBoolean(event.attributes().get("keyed"));
            oneRowScan = parseBoolean(event.attributes().get("oneRowScan"));
        }

        RdbmsObservedPlan build() {
            return new RdbmsObservedPlan(
                    nodeKind,
                    physicalPlanObserved,
                    table,
                    index,
                    conglomerateId,
                    forUpdate,
                    oneRowScan,
                    hasQualifiers,
                    storageProvider,
                    storageAccessKind,
                    predicatePushdownObserved,
                    keyedAccessObserved);
        }
    }
}
