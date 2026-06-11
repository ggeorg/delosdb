# Phase 1 checkpoint: first-release index compatibility

DelosDB's first release keeps Derby-compatible index behavior as the product contract.

## What is complete

- `CREATE INDEX ... USING btree` is accepted as an explicit spelling for the built-in Derby-compatible provider.
- Ordinary Derby `CREATE INDEX` remains equivalent to the built-in `btree` provider.
- Provider identity is persisted in index descriptor metadata.
- `IndexDescriptor` exposes provider identity for diagnostics.
- `IndexProvider` supports provider-neutral capability and cost estimates.
- The optimizer can observe and, behind an explicit opt-in switch, consume provider cost estimates.
- `IndexAccess` exists as the first physical index provider access contract.
- The built-in `btree` provider has a structural `IndexAccess` bridge, but Derby's existing B-tree path remains authoritative.
- btree is the only enabled first-release index provider.
- Unknown provider names such as `hash` or `nonsense` fail before execution with a clean unsupported-feature diagnostic.

## What is intentionally deferred

- hash is deferred; no `hash` provider ships in the first release.
- No public non-Derby physical index provider in the first release.
- No replacement for Derby B-tree storage, scans, locking, uniqueness, or recovery.
- No external provider discovery or `ServiceLoader` loading in the runtime path.
- No provider-owned insert/update/delete execution path yet.

## First-release rule

For indexing, DelosDB 1.x is a modern Java 21, LEGO-style modular platform around Derby-compatible behavior. New physical index behavior is a post-first-release experiment, not part of the compatibility baseline.


## Done-done boundary

IndexProvider is considered done-done for the first DelosDB release when these commands are green:

```text
./gradlew :delosdb-engine:verifyBTreeIndexAccessBridge
./gradlew :delosdb-engine:verifyDelosDbExtensionRegistrySkeleton
./gradlew indexProviderMetadataSmoke
./gradlew indexProviderCostInfluenceSmoke
./gradlew fullVerification
```

That boundary means end-to-end `btree` provider integration over Derby's existing physical B-tree, not a new physical index backend. Alternative physical providers such as `hash`, MapDB-backed indexes, and MVStore-style indexes are post-first-release work.
