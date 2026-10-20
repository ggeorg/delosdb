# DelosDB SQLancer MVCC profile skeleton

Marker: `DELOSDB_SQLANCER_PROFILE_SKELETON`.

This directory is an opt-in profile skeleton for SQLancer-style randomized SQL
validation against DelosDB's `delos_mvcc` storage provider. It is deliberately
outside normal Gradle source sets and has no normal-build dependency on
SQLancer. Nothing in the build reads or validates these files; the Gradle tasks
that once did (`delosSqlancerProfileSkeleton`, `delosSqlancerMvccValidation`)
were removed in 0cf95e92, so running SQLancer with this profile is up to the
caller. See `docs/EXTERNAL-VALIDATION.md`.

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
