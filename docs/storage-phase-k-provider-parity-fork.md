# DelosDB storage Phase K — provider-parity fork

Status: K0 is a truth/decision gate. It does not choose the fork.

## Why this phase exists

After the F-I native execution lane and J0 cleanup, the old SQL bridge is gone
and `delos_mvcc` has a real native Derby execution path for the supported SQL
shapes.  The remaining architectural question is provider parity:

```text
Should heap and MVCC both be live implementations of the Delos table-access
contract, or should DelosDB state honestly that delos_mvcc is the only live
Delos provider while heap remains Derby-native plus proof-only adapters?
```

Do not start new provider work until this fork is explicit.

## Current truth before the fork

```text
Live default heap SQL:
  Derby parser/binder/optimizer/executor
    -> inherited Derby heap / btree / RowChanger / ScanController path

Live delos_mvcc SQL:
  Derby parser/binder/optimizer/executor
    -> Delos native ResultSets
    -> DelosNativeTableRegistry
    -> EngineMvccTableAccess

Heap Delos adapter:
  EngineHeapTableAccessProof
    -> compile-time/proof-only capability and guarantee surface
    -> not wired into live heap SQL execution
```

This is not the final clean provider architecture.  It is the honest current
state.

## K0 acceptance

K0 proves the current state without changing it:

```text
- ordinary heap CREATE / INSERT / SELECT still works through Derby default path
- ordinary heap table resolves as Derby default provider, not a live Delos heap provider
- ordinary heap table is not registered in DelosNativeTableRegistry
- explicit delos_mvcc CREATE / INSERT / SELECT still works through native provider path
- explicit delos_mvcc table is registered in DelosNativeTableRegistry
- EngineHeapTableAccessProof remains proof-only
- DelosNativeTableRegistry remains delos_mvcc-only
- no bridge file or EmbedStatement interception returns
```

## Fork after K0

### Option A — provider parity

Make heap a live Delos provider.  The first milestone must be tiny, such as a
read-only heap SELECT proof through the same provider-aware ResultSet seam.  Do
not attempt full heap INSERT/UPDATE/DELETE parity in one step.

### Option B — single-provider honesty

Keep heap on Derby's inherited path and document that `delos_mvcc` is the only
live Delos provider.  Retain `EngineHeapTableAccessProof` only as a proof adapter
for contract honesty and cost/control comparisons.

## Non-goals for K0

```text
- no heap live-provider implementation
- no optimizer change
- no SQL coverage expansion
- no mutation behavior change
- no bridge resurrection
- no default-provider switch
```
