package io.github.ggeorg.delosdb.engine.rdbms.model;

/**
 * Teachable statement categories used by the DelosDB modern RDBMS model.
 *
 * <p>This vocabulary is intentionally smaller than Derby's full statement hierarchy. It gives
 * traces and documentation a stable way to describe the database pipeline without replacing the
 * inherited compiler or executor.</p>
 */
public enum RdbmsStatementKind {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    CREATE_TABLE,
    DROP_TABLE,
    CREATE_INDEX,
    DROP_INDEX,
    DDL,
    TRANSACTION_CONTROL,
    UTILITY,
    UNKNOWN
}
