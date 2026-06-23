# Storage Phase I — Mutation concurrency primitive

Phase I starts after Phase H cost integration is green.  It must not reopen SQL
bridge routing, add SQL regex routes, or claim real locking before a provider
state primitive exists.

## I1 — optimistic mutation preparation

I1 chooses Option A from the deferred E4 plan:

```text
validateMutable(context, rowIdentity)
prepareMutation(context, rowIdentity)
```

This is a row-identity-boundary validation primitive.  It proves that native
mutation can explicitly validate and prepare an opaque row identity before
UPDATE or DELETE.  It does **not** claim row-lock acquisition, reservation state,
write latches, or deadlock handling.

The new contract surface is:

```text
DelosMutableTableAccess.validateMutable(...)
DelosMutableTableAccess.prepareMutation(...)
DelosMutationPreparation
```

For `EngineMvccTableAccess`, validation checks that the supplied
`DelosRowIdentity` belongs to `delos_mvcc` and is visible in the current MVCC
mutation view.  Preparation is the same optimistic validation marked as
prepared, with `lockAcquired=false` by construction.

Acceptance:

```text
CREATE TABLE APP.I1_MUTATION_PREP (...) USING delos_mvcc
INSERT rows through native Derby execution
native scan produces DelosRowIdentity
validateMutable(...) accepts the visible identity
prepareMutation(...) prepares the visible identity
both results report lockAcquired=false
native UPDATE/DELETE continue to mutate by DelosRowIdentity
prepareMutation(...) rejects the deleted row identity in a later view
VersionedStorageSqlBridge.tryExecute(...) is not called
```

## Deferred

Real row locking/reservation remains a separate Option B phase.  It needs a
stateful result such as:

```text
tryLock(context, rowIdentity, mode) -> DelosLockResult
```

Do not rename I1 validation as locking.  A future lock primitive must include
real reservation/ownership state and failure semantics.

## I2 — native mutation conflict mapping

I2 keeps Option A's non-locking contract and proves the next boundary: an
existing MVCC write/write conflict must surface through native Derby
UPDATE/DELETE execution as a transaction conflict, not as a generic Java
exception and not through the retired SQL bridge.

The proof deliberately creates the first writer through the native registry and
keeps that statement transaction active.  A second Derby-native SQL mutation then
tries to modify the same visible row identity.

Acceptance:

```text
CREATE TABLE APP.I2_MUTATION_CONFLICT (...) USING delos_mvcc
INSERT rows through native Derby execution
first native writer updates row id=1 and remains active
second native SQL UPDATE id=1 fails with SQLState 40001
first native writer aborts
native SQL UPDATE id=1 succeeds
first native writer deletes row id=2 and remains active
second native SQL DELETE id=2 fails with SQLState 40001
first native writer aborts
native SQL DELETE id=2 succeeds
VersionedStorageSqlBridge.tryExecute(...) is not called
```

This does not add row locks, wait queues, reservations, deadlock detection, or
ownership state.  It only maps the provider-neutral MVCC write-conflict signal at
the native Derby mutation boundary.
