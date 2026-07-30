# DelosDB repository integrity cleanup

Status: STAGE 2 IMPLEMENTED / PENDING VERIFICATION

## Purpose

Before adding the Phase 10.1 stable plan model, DelosDB performs a full-source
cleanup and no-compromise campaign. The campaign must reduce dead code,
duplication, complexity, exception-handling debt, and architecture drift
without changing SQL, transaction, storage, generated-code, JDBC, or DRDA
semantics.

The inventory parses the complete Java source tree with the public
`com.sun.source` javac tree API. Findings are classification candidates: a
private member or duplicate body is not removed until reflection, serialization,
generated-code, protocol, SQL-routine, test-discovery, and compatibility use
have been reviewed.

## Permanent tasks

```text
delosRepositoryIntegrityInventory
delosRepositoryIntegrityStaticAnalysis
```

Evidence is written under:

```text
build/reports/delosdb/repository-integrity/inventory/
```

The static task fails on Java parse errors, generated-class authority
violations, missing evidence, or increases above the checked-in debt baseline.
Candidate debt may decrease without updating the baseline; increases fail.

## Stage 1 baseline

```text
Java files:                              3303
Production Java files:                   2070
Declared types:                          4014
Declared methods:                       45713
Declared fields:                        21329
Java parse errors:                          0

Dead private production methods:           16
Dead private production fields:            71
Exact production duplicate groups:         55
Methods in duplicate groups:              137
Estimated duplicate production lines:    1184
Production methods >= 100 lines:           447
Production methods complexity >= 20:       169
Production classes >= 1000 lines:          140
Production empty catches:                  250
Production generic catches:                473
Production @SuppressWarnings occurrences:   40
Production quality markers:                816
Compiler authority compromise candidates:    1
```

## Stage 2 generated-class authority pin

The generic Derby monitor still supports inherited external module properties
for modules that remain configurable. Generated-class authority is different:
`ClassFileJava` is an architectural constant. `BaseMonitor` now rejects every
externally supplied class implementing `JavaFactory` when processing boot, JVM,
application, service, or database properties. Only the packaged
`modules.properties` inventory may provide that interface.

The production SQL proof writes an application-level `derby.module.*` entry
pointing at a deliberately rejecting `JavaFactory`. Boot must ignore it, select
`ClassFileJava`, compile representative SQL, and retain SQLState behavior.

Permanent invariants:

```text
ClassFileJava is the sole packaged JavaFactory registration
external JavaFactory module injection is rejected
no external ASM source/build/module reference exists
only ClassFileJava and DelosJdk25ClassFileVerifier import java.lang.classfile
SQL compiler nodes do not import java.lang.classfile
compiler authority violations: 0
compiler authority compromise candidates: 0
```

## Stage 2 proven dead-code batch

All 16 private production candidates from Stage 1 were removed after confirming:

```text
no source invocation
no method reference
no reflective string reference
no serialization callback contract
no generated parser or bytecode hook
no SQL external-routine binding
no compatibility or public API surface
```

Removed methods:

```text
NetConnection.flowSimpleConnect
DelosStorageTransactionRegistry.writeParticipationFor
EmbedConnection.checkDatabaseCreatePrivileges
DataDictionaryImpl.twoDigits
BasicDependencyManager.dropDependency
InsertResultSet.getTableScanResultSet
SetOpResultSet.advanceRightPastDuplicates
NetworkServerControl.hostnamesEqual
NetworkServerControl.isIPV6Address
DRDAConnThread.writeQRYPOPRM
D_BTreeController.olddiag_tabulate
B2I.traverseRight
FileContainer.switchToMultiInsertPageMode
StoredPage.logOverflowField
sysinfo.Main.tryAsResource
sysinfo.Main.lookForMainArg
```

Commented-out call sites belonging only to these methods were removed with the
methods. No public or internal callable contract changed.

## Stage 2 reduced baseline

```text
Dead private production methods:            0
Dead private production fields:            71
Exact production duplicate groups:         55
Estimated duplicate production lines:    1184
Production methods >= 100 lines:           447
Production methods complexity >= 20:       169
Production classes >= 1000 lines:          139
Production empty catches:                  249
Production generic catches:                469
Production @SuppressWarnings occurrences:   40
Production quality markers:                815
Compiler authority compromise candidates:    0
```

## Remaining cleanup sequence

### Stage 3 — DelosDB-owned duplication

Consolidate duplicated validation and test-support code introduced by DelosDB
before modifying inherited JDBC, DRDA, parser, or compatibility boilerplate.

### Stage 4 — quality and structure

Reduce dead private fields, empty/generic catches, oversized methods, deep
nesting, suppressions, and stale markers where correctness permits.

### Stage 5 — compiler no-compromise closeout

Review all 43 `MethodBuilder` operations and confirm primitive categories,
category-two stack values, inferred field owners, arrays, branches, exception
attributes, deterministic generation, and class-loading lifecycle. Authority
injection is already closed by Stage 2.

### Stage 6 — final consolidation

Replace campaign-specific evidence with reduced permanent budgets and retain
new-debt prohibition in S0. Only then does DelosDB begin Phase 10.1 stable plan
modelling.

## Non-goals

Stage 2 does not change:

```text
SQL semantics or SQLStates
generated activation interfaces or bytecode contracts
class-loading lifecycle or statement caching
JDBC or DRDA protocol behavior
storage or MVCC behavior
generic configurability of unrelated Derby modules
module ownership or dependencies
```
