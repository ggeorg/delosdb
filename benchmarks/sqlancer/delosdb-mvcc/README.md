# DelosDB SQLancer MVCC profile skeleton

Marker: `DELOSDB_SQLANCER_PROFILE_SKELETON`.

This directory is an opt-in profile skeleton for SQLancer-style randomized SQL
validation against DelosDB's `delos_mvcc` storage provider. It is deliberately
outside normal Gradle source sets and has no normal-build dependency on
SQLancer.

Validate the skeleton without running SQLancer:

```bash
./gradlew delosSqlancerProfileSkeleton
```

Run a CI-provided SQLancer command explicitly:

```bash
./gradlew delosSqlancerProfileSkeleton \
  -Pdelosdb.sqlancer.profile.command="<compile-and-run SQLancer command>"
```

The command property is `delosdb.sqlancer.profile.command`. The existing R6
external-only task `delosSqlancerMvccValidation` remains available through
`delosdb.sqlancer.command`; this skeleton gives that future command a stable
profile shape first.

Files:

* `delosdb-mvcc-profile.properties` — supported generator scope and provider
  constraints.
* `delosdb-mvcc-smoke.sql` — minimal hand-written mixed heap/MVCC workload the
  generated profile must remain compatible with.
* `delosdb-mvcc-oracle-scope.md` — oracle scope, exclusions, and differential
  validation handoff.

Boundary:

* No S0 dependency.
* No normal module dependency.
* No storage/optimizer/DRDA behavior change.
* No requirement that SQLancer is present unless the caller provides an
  explicit external command.
