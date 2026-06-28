package io.github.ggeorg.delosdb.engine.trace;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Small adapter from inherited Derby execution points into the DelosDB modern RDBMS trace model.
 *
 * <p>This class is intentionally observational. It does not participate in planning, execution, or
 * storage decisions. The default trace sink is no-op, so the adapter has no externally visible
 * effect unless a focused test or diagnostic tool installs a sink.</p>
 */
public final class DerbyRdbmsTrace {
    private DerbyRdbmsTrace() {
    }

    public static void statementReceived(String sql) {
        if (!RdbmsTraceRegistry.isEnabled()) {
            return;
        }
        RdbmsStatementKind kind = statementKind(sql);
        RdbmsTraceRegistry.emit(RdbmsTraceEvent.of(
                RdbmsLifecycleStage.SQL_TEXT_RECEIVED,
                "statement",
                attributes(
                        "kind", kind.name(),
                        "sql", safe(sql))));
    }

    public static void statementExecutionStarted(String sql) {
        if (!RdbmsTraceRegistry.isEnabled()) {
            return;
        }
        RdbmsStatementKind kind = statementKind(sql);
        RdbmsTraceRegistry.emit(RdbmsTraceEvent.of(
                RdbmsLifecycleStage.EXECUTION_STARTED,
                "statement",
                attributes(
                        "kind", kind.name(),
                        "sql", safe(sql))));
    }

    public static void tableScanPlanned(
            String tableName,
            String indexName,
            long conglomerateId,
            boolean forUpdate,
            boolean oneRowScan,
            boolean hasQualifiers) {
        if (!RdbmsTraceRegistry.isEnabled()) {
            return;
        }
        RdbmsTraceRegistry.emit(RdbmsTraceEvent.of(
                RdbmsLifecycleStage.PHYSICAL_PLAN_CREATED,
                "table-scan",
                attributes(
                        "table", safe(tableName),
                        "index", safe(indexName),
                        "conglomerateId", Long.toString(conglomerateId),
                        "forUpdate", Boolean.toString(forUpdate),
                        "oneRowScan", Boolean.toString(oneRowScan),
                        "hasQualifiers", Boolean.toString(hasQualifiers))));
    }

    public static void tableScanExecutionStarted(
            String tableName,
            String indexName,
            long conglomerateId,
            boolean keyed) {
        if (!RdbmsTraceRegistry.isEnabled()) {
            return;
        }
        RdbmsTraceRegistry.emit(RdbmsTraceEvent.of(
                RdbmsLifecycleStage.EXECUTION_STARTED,
                "table-scan",
                attributes(
                        "table", safe(tableName),
                        "index", safe(indexName),
                        "conglomerateId", Long.toString(conglomerateId),
                        "keyed", Boolean.toString(keyed))));
    }

    public static void storageAccessed(
            String tableName,
            String indexName,
            long conglomerateId,
            boolean keyed,
            boolean oneRowScan,
            boolean hasQualifiers) {
        if (!RdbmsTraceRegistry.isEnabled()) {
            return;
        }
        RdbmsStorageAccessKind accessKind = storageAccessKind(keyed, oneRowScan);
        RdbmsStorageProviderKind providerKind = storageProviderKind(keyed);
        RdbmsTraceRegistry.emit(RdbmsTraceEvent.of(
                RdbmsLifecycleStage.STORAGE_ACCESSED,
                "table-scan",
                attributes(
                        "table", safe(tableName),
                        "index", safe(indexName),
                        "conglomerateId", Long.toString(conglomerateId),
                        "accessKind", accessKind.name(),
                        "provider", providerKind.name(),
                        "keyed", Boolean.toString(keyed),
                        "oneRowScan", Boolean.toString(oneRowScan),
                        "predicatePushdown", Boolean.toString(hasQualifiers))));
    }

    public static void rowsProduced(String tableName, String indexName, long rowsThisScan) {
        if (!RdbmsTraceRegistry.isEnabled()) {
            return;
        }
        RdbmsTraceRegistry.emit(RdbmsTraceEvent.of(
                RdbmsLifecycleStage.ROWS_PRODUCED,
                "table-scan",
                attributes(
                        "table", safe(tableName),
                        "index", safe(indexName),
                        "rowsThisScan", Long.toString(rowsThisScan))));
    }

    public static void tableScanExecutionFinished(
            String tableName,
            String indexName,
            long rowsThisScan,
            long rowsSeen,
            long rowsFiltered) {
        if (!RdbmsTraceRegistry.isEnabled()) {
            return;
        }
        RdbmsTraceRegistry.emit(RdbmsTraceEvent.of(
                RdbmsLifecycleStage.EXECUTION_FINISHED,
                "table-scan",
                attributes(
                        "table", safe(tableName),
                        "index", safe(indexName),
                        "rowsThisScan", Long.toString(rowsThisScan),
                        "rowsSeen", Long.toString(rowsSeen),
                        "rowsFiltered", Long.toString(rowsFiltered))));
    }

    public static void transactionCommitted(
            String transactionId,
            boolean commitStore,
            boolean sync,
            int commitFlag,
            boolean requestedByUser) {
        if (!RdbmsTraceRegistry.isEnabled()) {
            return;
        }
        RdbmsTraceRegistry.emit(RdbmsTraceEvent.of(
                RdbmsLifecycleStage.TRANSACTION_COMMITTED,
                "transaction",
                attributes(
                        "concept", RdbmsTransactionConcept.TRANSACTION.name(),
                        "outcome", "COMMIT",
                        "provider", "DERBY_TRANSACTION",
                        "transactionId", safe(transactionId),
                        "commitStore", Boolean.toString(commitStore),
                        "sync", Boolean.toString(sync),
                        "commitFlag", Integer.toString(commitFlag),
                        "requestedByUser", Boolean.toString(requestedByUser))));
    }

    public static void transactionRolledBack(
            String transactionId,
            boolean xa,
            boolean requestedByUser) {
        if (!RdbmsTraceRegistry.isEnabled()) {
            return;
        }
        RdbmsTraceRegistry.emit(RdbmsTraceEvent.of(
                RdbmsLifecycleStage.TRANSACTION_ROLLED_BACK,
                "transaction",
                attributes(
                        "concept", RdbmsTransactionConcept.TRANSACTION.name(),
                        "outcome", "ROLLBACK",
                        "provider", "DERBY_TRANSACTION",
                        "transactionId", safe(transactionId),
                        "xa", Boolean.toString(xa),
                        "requestedByUser", Boolean.toString(requestedByUser))));
    }

    static RdbmsStorageAccessKind storageAccessKind(boolean keyed, boolean oneRowScan) {
        if (keyed && oneRowScan) {
            return RdbmsStorageAccessKind.BTREE_KEYED_LOOKUP;
        }
        if (keyed) {
            return RdbmsStorageAccessKind.BTREE_INDEX_SCAN;
        }
        return RdbmsStorageAccessKind.HEAP_SCAN;
    }

    static RdbmsStorageProviderKind storageProviderKind(boolean keyed) {
        return keyed ? RdbmsStorageProviderKind.DERBY_BTREE : RdbmsStorageProviderKind.DERBY_HEAP;
    }

    static RdbmsStatementKind statementKind(String sql) {
        String first = firstToken(sql);
        if ("SELECT".equals(first) || "VALUES".equals(first)) {
            return RdbmsStatementKind.SELECT;
        }
        if ("INSERT".equals(first)) {
            return RdbmsStatementKind.INSERT;
        }
        if ("UPDATE".equals(first)) {
            return RdbmsStatementKind.UPDATE;
        }
        if ("DELETE".equals(first)) {
            return RdbmsStatementKind.DELETE;
        }
        if ("CREATE".equals(first)) {
            return classifyCreate(sql);
        }
        if ("DROP".equals(first)) {
            return classifyDrop(sql);
        }
        if ("COMMIT".equals(first) || "ROLLBACK".equals(first) || "SAVEPOINT".equals(first)) {
            return RdbmsStatementKind.TRANSACTION_CONTROL;
        }
        if (first.isEmpty()) {
            return RdbmsStatementKind.UNKNOWN;
        }
        return RdbmsStatementKind.UTILITY;
    }

    private static RdbmsStatementKind classifyCreate(String sql) {
        String second = token(sql, 1);
        if ("TABLE".equals(second)) {
            return RdbmsStatementKind.CREATE_TABLE;
        }
        if ("INDEX".equals(second) || "UNIQUE".equals(second)) {
            return RdbmsStatementKind.CREATE_INDEX;
        }
        return RdbmsStatementKind.DDL;
    }

    private static RdbmsStatementKind classifyDrop(String sql) {
        String second = token(sql, 1);
        if ("TABLE".equals(second)) {
            return RdbmsStatementKind.DROP_TABLE;
        }
        if ("INDEX".equals(second)) {
            return RdbmsStatementKind.DROP_INDEX;
        }
        return RdbmsStatementKind.DDL;
    }

    private static String firstToken(String sql) {
        return token(sql, 0);
    }

    private static String token(String sql, int index) {
        if (sql == null) {
            return "";
        }
        String trimmed = sql.stripLeading();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] tokens = trimmed.split("\\s+", index + 2);
        if (tokens.length <= index) {
            return "";
        }
        return tokens[index].replaceAll("[^A-Za-z_]", "").toUpperCase(Locale.ROOT);
    }

    private static Map<String, String> attributes(String... pairs) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            attributes.put(pairs[i], pairs[i + 1]);
        }
        return attributes;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
