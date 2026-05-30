# DelosDB Modernization Track

This document defines the first real modernization track after the build-system rescue work.

## Current baseline

DelosDB now has a Gradle-first build, extracted Gradle subprojects, verified runtime jars, release metadata, a binary distribution, and local Maven publication metadata. That foundation is enough. Further work should focus on code quality, runtime behavior, tests, and developer-facing APIs.

## Modernization priorities

1. Java 21 cleanup
   - Replace finalizers with explicit close paths and `Cleaner` only where a fallback is still needed.
   - Prioritize real resource owners first: Lucene readers, DRDA streams, LOB temporary files, JDBC statements/connections.
   - Isolate or remove SecurityManager-era code.
   - Remove obsolete privileged-action scaffolding where it is no longer meaningful.

2. Runtime safety
   - Make resource ownership explicit.
   - Prefer deterministic cleanup over garbage-collector cleanup.
   - Add focused regression tests around touched JDBC and network paths.

3. API and integration polish
   - Keep JDBC compatibility intact.
   - Add DelosDB-branded convenience APIs only as additive APIs.
   - Avoid package/module renaming until a separate compatibility strategy exists.

4. Performance and observability
   - Add benchmarks before performance rewrites.
   - Measure startup, embedded connect, insert, indexed lookup, commit, and network-server latency.

## Rule for source cleanup

Do not rename public `org.apache.derby.*` packages yet. Do not rename JPMS modules yet. Do not change runtime jar names yet. Those are compatibility decisions, not cleanup tasks.

### Network Server `-noSecurityManager` command surface

DelosDB still accepts the inherited `-noSecurityManager` switch as a no-op so existing startup scripts do not fail, but the switch is no longer advertised in Network Server usage text and no longer toggles internal server state. The active runtime baseline is Java 21+, where DelosDB does not install or manage a JVM SecurityManager.

