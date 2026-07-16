# DelosDB architecture

## System boundary

DelosDB is one relational database engine with two storage modes. The parser, binder, optimizer,
execution engine, transaction boundary, catalog, JDBC layer, and DRDA server are shared.

```text
Embedded JDBC                         Derby-compatible network client
      |                                           |
      +-------------------+-----------------------+
                          |
                    SQL statement
                          |
                    GenericStatement
                          |
                 parser and binder
                          |
                    Derby optimizer
                          |
              generated Activation bytecode
                          |
                  NoPutResultSet tree
                          |
              LanguageConnectionContext
                          |
                TransactionController
                          |
               conglomerate/access method
                    /                 \
          Derby heap/raw store      delos_mvcc
                    \                 /
                row and index results
                          |
                  EmbedResultSet / DRDA
```

## Compilation

### Parse and bind

Derby's SQL grammar produces statement nodes. Binding resolves schemas, tables, columns, types,
privileges, routines, constraints, and catalog dependencies.

### Optimize

The Derby optimizer remains the planning authority. Storage providers expose the cost and access
information needed by the existing optimizer; they do not introduce a second planner.

### Generate

The compiler generates activation bytecode and result-set construction calls. DelosDB uses the
permanent ASM backend for generated classes while preserving Derby's activation and result-set
contracts.

## Execution

Generated activations construct a `NoPutResultSet` tree. Operators implement scans, joins,
aggregates, sorts, projections, mutations, constraints, and result delivery.

The authoritative heap path uses Derby's established result-set and access-method implementation.
Phase-named proof routes and hidden system-property alternatives are not supported production
features and are prohibited from the main source tree.

## Transaction and storage dispatch

The language connection coordinates commit, rollback, savepoints, isolation, and transaction
participants. Table descriptors and conglomerate metadata select the physical access method.

### Derby heap

The heap path owns Derby-compatible page, row-location, lock, raw-log, and recovery behavior.

### `delos_mvcc`

The MVCC access-method bridge adapts Derby's conglomerate contracts to the DelosDB storage API and
page-backed MVCC implementation. It owns version visibility, ordered-index sidecars, transaction
status, outcomes, page WAL, recovery, maintenance, and database-scoped backup coordination.

## Result delivery

Embedded execution returns `EmbedResultSet` instances. Network execution uses the same compiled and
executed query but encodes results through the Derby-compatible DRDA server.

## Ownership rules

- The Derby compiler and optimizer remain authoritative for SQL semantics and plan selection.
- Storage providers own physical row, index, durability, and consistency behavior.
- The transaction layer owns cross-table commit and rollback participation.
- The DRDA server preserves the wire protocol and must not become a second SQL engine.
- Diagnostics observe authoritative execution; they do not become hidden routing mechanisms.
- Test comparison modes remain package-private or test-only and are removed after an algorithm is
  accepted.

## Maintainability requirement

Each major subsystem must provide:

- a named responsibility and owner;
- explicit inputs, outputs, and invariants;
- documented lifecycle and failure behavior;
- focused verification and diagnostics;
- a corresponding architecture or book section.

Large classes are extraction candidates when they own multiple independent invariants or lifecycle
stages. Line count alone is not the criterion.
