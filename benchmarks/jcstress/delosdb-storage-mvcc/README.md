# DelosDB MVCC jcstress visibility probes

This directory contains opt-in jcstress probes for DelosDB MVCC visibility and
publication algorithms. The sources are deliberately outside the normal Gradle
source sets so S0, module checks, and normal integration tests do not require
jcstress on the classpath.

Run the built-in deterministic baseline and validate this probe layout with:

```bash
./gradlew delosJcstressMvccVisibilityProbes
```

Run an external jcstress build/runner command with:

```bash
./gradlew delosJcstressMvccVisibilityProbes \
  -Pdelosdb.jcstress.visibility.command="<compile-and-run jcstress command>"
```

The adapter task writes:

```text
build/reports/delosdb/jcstress-mvcc-visibility-probes.txt
```

Probe classes:

* `DelosMvccSnapshotIsolationJcstressProbe` — proves a snapshot captured before
  commit never observes the later commit.
* `DelosMvccCommitVisibilityJcstressProbe` — allows either pre-commit or
  post-commit observation for a new reader, but forbids crashes or impossible
  values.
* `DelosMvccSnapshotLeaseHorizonJcstressProbe` — verifies a retained snapshot
  lease remains visible to cleanup horizon calculations while commit/close races
  with horizon reads.
* `DelosMvccBufferPinDirtyJcstressProbe` — stresses pin/unpin and dirty-page
  accounting as a future buffer publication probe.

These probes are external validation only. They do not change MVCC semantics,
transaction publication, purge horizon behavior, buffer behavior, checkpoint
behavior, Derby heap compatibility, or optimizer behavior.
