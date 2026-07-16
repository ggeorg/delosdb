
# DelosDB Architecture

## Complete system

DelosDB is one relational engine with Derby-compatible heap and optional MVCC storage.

```text
Embedded JDBC or Derby-compatible DRDA
    → parser and binder
    → Derby optimizer
    → generated Activation
    → NoPutResultSet tree
    → language and transaction boundary
    → heap or delos_mvcc
    → EmbedResultSet or DRDA result
```

The SQL compiler, optimizer, catalog, and execution infrastructure are shared. MVCC is not a second
SQL engine.

## Database ownership

### Current defect

The current MVCC bridge contains mutable process-global database-directory state used to resolve
stores and table state. That is not an acceptable ownership boundary for multiple active databases.

### V1 target

Each database owns one explicit runtime:

```text
MvccDatabaseRuntime
    canonical identity
    storage and table-state registry
    maintenance service
    backup coordinator
    transaction coordinator
    database diagnostics
    close lifecycle
```

Conglomerates attach to the runtime through their owning database/factory context. Deserialization
restores persistent metadata before runtime attachment. Diagnostics never select a database through
ambient global state.

## Transaction ownership

### Current limitation

The transaction registry currently completes MVCC writers sequentially before Derby raw-store
commit. This coordinates commit timing but does not prove one failure-atomic outcome when several
MVCC tables or heap and MVCC participate.

### V1 target

```text
prepare participants
validate
stage durable payloads
record one authoritative transaction decision
publish participant outcomes
recover interrupted publication
acknowledge
```

Until the protocol is complete, unsafe multi-MVCC, mixed-write, and XA combinations reject before
mutation with stable SQLStates.

## Compilation and execution

Derby's grammar, binding, optimizer, generated activations, and result-set operators remain
authoritative. Phase-named proof routes and hidden production alternatives are prohibited.

## Storage modes

### Heap

Heap owns Derby-compatible pages, locks, raw logging, recovery, and durable formats.

### `delos_mvcc`

MVCC owns versions, visibility, page-backed rows, ordered indexes, transaction payloads, recovery,
maintenance, consistency, and database-scoped backup participation.

## Plan and diagnostic model

V1 adds stable, read-only plan, profile, and diagnostic snapshots derived from authoritative engine
state. DuckDB's first-class EXPLAIN/profiling model is a useful reference; DelosDB retains its own
execution architecture.

## Source and module ownership

Artifacts, Gradle projects, and JPMS modules are separate graphs. Source ownership should make the
RDBMS understandable, but one project or module per conceptual chapter is not required. DuckDB's
subsystem directories assembled into one library demonstrate that distinction.

## Maintainability

Each major subsystem has one named owner, explicit invariants and failure behavior, focused proof,
diagnostics, and corresponding architecture/book material.
