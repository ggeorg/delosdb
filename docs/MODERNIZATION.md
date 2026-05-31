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


- Replaced `EmbedPreparedStatement.finalize()` with Cleaner fallback activation cleanup while preserving explicit close as the primary path.

### Client JDBC finalizer cleanup batch

The client connection, client statement, logical connection, and pooled connection classes no longer override `Object.finalize()`. DelosDB now treats explicit `close()` and existing ownership paths as the supported cleanup mechanism for these client-side JDBC objects on Java 21+.

The remaining high-risk embedded engine connection and statement finalizers have now been removed. DelosDB relies on explicit close paths for embedded JDBC connections/statements, result-set close for single-use activations, and targeted Cleaner fallbacks only for resources where a safe non-owning cleanup state exists.


### Embedded engine finalizer cleanup batch

The embedded engine no longer overrides `Object.finalize()` in `EmbedConnection` or `EmbedStatement`. DelosDB treats explicit JDBC `close()` calls as the supported lifecycle path for embedded connections and statements on Java 21+. This avoids running connection close or activation lifecycle logic from GC/finalizer threads. Single-use activations continue to be closed through result-set close, while prepared-statement activation fallback cleanup remains handled by the dedicated Cleaner state introduced earlier.

### Java 21 audit guardrail

The modernization audit now separates production runtime modules from inherited tests, demos, and historical material. `./dev/modernization-audit.sh --verify` is the guardrail for the Java 21 cleanup track and currently enforces that production code has no `Object.finalize()` overrides, no direct JVM security-manager inspection calls, and no privileged-action wrappers.

The next cleanup target after finalizers and SecurityManager-era code is synchronized legacy collection usage. `Vector` and `Hashtable` are still reported but not failed yet because those replacements require ownership and concurrency review.

### Tools collection cleanup batch

The first legacy collection cleanup batch removes low-risk `Vector` usage from ij URL validation and JDBC result display utilities. The active rule for collection modernization is to replace synchronized legacy collections only where ownership and synchronization behavior are clear; engine/store/compiler structures remain audit-only until reviewed in focused batches.

### Identifier and sysinfo collection cleanup batch

The second legacy collection cleanup batch targets private utility/diagnostic accumulation state: identifier parsing helpers, statement-cache diagnostics, and sysinfo zip-location merging. These paths now use `ArrayList`/`List` internally while preserving existing array and string-returning APIs. Engine/store/compiler collection structures remain intentionally untouched until reviewed in focused batches.

### Client collection cleanup batch

The third legacy collection cleanup batch targets private client driver maps. Low-risk `Hashtable` fields in metadata lookup, section/cursor tracking, connection cursor-name caching, and DRDA trace name lookup now use `HashMap`/`Map`. Engine/store/compiler collection structures remain intentionally audit-only until reviewed in focused batches.


### Classfile collection cleanup batch

The fourth legacy collection cleanup batch targets the internal classfile generation/inspection utilities. These structures are build/runtime bytecode metadata containers owned by the classfile package, so replacing private `Vector`/`Hashtable` storage with `List`/`Map` is lower-risk than changing store, lock, compiler, or public ij APIs. Existing enumeration-facing methods still return `Enumeration` through `Collections.enumeration(...)` to preserve caller behavior.
