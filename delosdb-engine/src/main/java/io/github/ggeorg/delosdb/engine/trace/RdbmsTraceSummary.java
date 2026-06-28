package io.github.ggeorg.delosdb.engine.trace;

import java.util.Objects;

/**
 * Small reader-facing summary derived from already-captured modern RDBMS trace events.
 *
 * <p>The summary is diagnostic-only. It does not subscribe to the trace registry, does not install
 * a sink, and does not participate in planning, execution, storage routing, or row production. It
 * compresses an event stream into the minimum row-flow facts that a focused proof or tutorial can
 * show without adding new Derby hooks.</p>
 */
public record RdbmsTraceSummary(
        String statementKind,
        boolean executionStarted,
        boolean executionFinished,
        int storageAccesses,
        String storageProvider,
        String storageAccessKind,
        long rowsProduced,
        long rowsSeen,
        long rowsFiltered,
        String transactionOutcome) {

    private static final String UNKNOWN = "UNKNOWN";

    public RdbmsTraceSummary {
        statementKind = normalize(statementKind);
        storageProvider = normalize(storageProvider);
        storageAccessKind = normalize(storageAccessKind);
        transactionOutcome = transactionOutcome == null ? "" : transactionOutcome;
    }

    public static RdbmsTraceSummary summarize(Iterable<RdbmsTraceEvent> events) {
        Objects.requireNonNull(events, "events");

        Builder builder = new Builder();
        for (RdbmsTraceEvent event : events) {
            builder.accept(event);
        }
        return builder.build();
    }

    public String format() {
        StringBuilder builder = new StringBuilder();
        builder.append("statement kind: ").append(statementKind).append(System.lineSeparator())
                .append("execution started: ").append(executionStarted).append(System.lineSeparator())
                .append("execution finished: ").append(executionFinished).append(System.lineSeparator())
                .append("storage accesses: ").append(storageAccesses).append(System.lineSeparator())
                .append("storage provider: ").append(storageProvider).append(System.lineSeparator())
                .append("storage access kind: ").append(storageAccessKind).append(System.lineSeparator())
                .append("rows produced: ").append(rowsProduced);

        if (rowsSeen >= 0) {
            builder.append(System.lineSeparator())
                    .append("rows seen: ").append(rowsSeen);
        }
        if (rowsFiltered >= 0) {
            builder.append(System.lineSeparator())
                    .append("rows filtered: ").append(rowsFiltered);
        }
        if (!transactionOutcome.isEmpty()) {
            builder.append(System.lineSeparator())
                    .append("transaction outcome: ").append(transactionOutcome);
        }

        return builder.toString();
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        return value;
    }

    private static long addMetric(long current, long value) {
        if (value < 0) {
            return current;
        }
        if (current < 0) {
            return value;
        }
        return current + value;
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

    private static final class Builder {
        private String statementKind = UNKNOWN;
        private boolean executionStarted;
        private boolean executionFinished;
        private int storageAccesses;
        private String storageProvider = UNKNOWN;
        private String storageAccessKind = UNKNOWN;
        private long rowsProduced;
        private long rowsSeen = -1L;
        private long rowsFiltered = -1L;
        private String transactionOutcome = "";

        void accept(RdbmsTraceEvent event) {
            if (event == null) {
                return;
            }

            if ("statement".equals(event.subject())) {
                acceptStatementEvent(event);
            }
            if ("table-scan".equals(event.subject())) {
                acceptTableScanEvent(event);
            }
            if ("transaction".equals(event.subject())) {
                acceptTransactionEvent(event);
            }
        }

        private void acceptStatementEvent(RdbmsTraceEvent event) {
            String kind = event.attributes().get("kind");
            if (kind != null && !kind.isBlank()) {
                statementKind = kind;
            }
            if (event.stage() == RdbmsLifecycleStage.EXECUTION_STARTED) {
                executionStarted = true;
            }
        }

        private void acceptTableScanEvent(RdbmsTraceEvent event) {
            if (event.stage() == RdbmsLifecycleStage.STORAGE_ACCESSED) {
                storageAccesses++;
                storageProvider = normalize(event.attributes().get("provider"));
                storageAccessKind = normalize(event.attributes().get("accessKind"));
            } else if (event.stage() == RdbmsLifecycleStage.ROWS_PRODUCED) {
                rowsProduced = addMetric(
                        rowsProduced,
                        parseLong(event.attributes().get("rowsThisScan"), -1L));
            } else if (event.stage() == RdbmsLifecycleStage.EXECUTION_FINISHED) {
                executionFinished = true;
                rowsSeen = addMetric(
                        rowsSeen,
                        parseLong(event.attributes().get("rowsSeen"), -1L));
                rowsFiltered = addMetric(
                        rowsFiltered,
                        parseLong(event.attributes().get("rowsFiltered"), -1L));
            }
        }

        private void acceptTransactionEvent(RdbmsTraceEvent event) {
            if (event.stage() == RdbmsLifecycleStage.TRANSACTION_COMMITTED
                    || event.stage() == RdbmsLifecycleStage.TRANSACTION_ROLLED_BACK) {
                String outcome = event.attributes().get("outcome");
                if (outcome != null && !outcome.isBlank()) {
                    transactionOutcome = outcome;
                }
            }
        }

        RdbmsTraceSummary build() {
            return new RdbmsTraceSummary(
                    statementKind,
                    executionStarted,
                    executionFinished,
                    storageAccesses,
                    storageProvider,
                    storageAccessKind,
                    rowsProduced,
                    rowsSeen,
                    rowsFiltered,
                    transactionOutcome);
        }
    }
}
