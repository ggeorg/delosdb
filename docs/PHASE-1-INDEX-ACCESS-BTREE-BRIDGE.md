# Phase 1 IndexAccess B-tree Bridge Checkpoint

Status: built-in `btree` provider now has a structural bridge to the
provider-neutral `IndexAccess` SPI.

## First-release rule

The first DelosDB release stays Derby-compatible at the behavior level.

DelosDB can modernize the build, module boundaries, Java baseline, SPI surface,
and diagnostics, but the default embedded SQL engine behavior must remain the
existing Derby B-tree path:

```text
CREATE INDEX idx ON t(c);
CREATE INDEX idx ON t(c) USING btree;
```

Both forms still use Derby's existing physical index implementation.

## What was added

The built-in `btree` provider now implements the optional physical access hook:

```text
IndexProvider.openAccess(IndexOpenRequest)
  -> DerbyBTreeIndexAccess
```

`DerbyBTreeIndexAccess` is an internal adapter. It proves that the built-in
provider can be represented through the new `IndexAccess` SPI without exposing
Derby internals to provider authors.

## What remains Derby-owned

For the first release, Derby remains authoritative for:

```text
B-tree storage
insert/delete/update maintenance
scan/controller execution
locking
transactions
recovery
uniqueness enforcement
constraint-backed indexes
```

The bridge is structural only. Provider-owned mutation and cursor operations are
not enabled yet.

## Why `hash` is deferred

A real `hash` index provider is still a good future milestone, but it is not a
first-release requirement. Shipping a second physical index too early would
risk compatibility, recovery, and transaction semantics.

The first release goal is instead:

```text
100% Derby-compatible behavior
modern JDK 21 baseline
LEGO-style modules and explicit SPI seams
```

## Next move

Index work can pause here. The next platform step should move sideways to a
safer extension module, such as `FunctionProvider`, while preserving the index
seam for later physical providers.
