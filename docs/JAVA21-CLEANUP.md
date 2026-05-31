# Java 21 Cleanup Notes

## First cleanup included in this milestone

`LuceneQueryVTI` in optional tools no longer uses `finalize()` to close its Lucene `IndexReader` fallback path.

The old code relied on `Object.finalize()`, which has been deprecated since Java 9 and is no longer a good cleanup mechanism on modern Java. The new code keeps the deterministic `close()` path and registers a `Cleaner` fallback for callers which forget to close the VTI explicitly.

Important behavior preserved:

- Explicit `close()` still closes the Lucene `IndexReader`.
- Explicit close still reports `IOException` as a Derby `SQLException` through the existing `ToolUtilities.wrap(...)` path.
- The fallback cleanup path remains best-effort and only logs `IOException`, matching the old finalizer behavior.
- Cleanup is idempotent to avoid double-closing the reader.


## Additional cleanup included in this milestone

`LOBStreamControl` in the embedded engine no longer relies on `finalize()` to release temporary LOB files. The explicit `free()` path still performs the primary cleanup and still reports `IOException` to callers. A `Cleaner` fallback now releases and deletes the temporary file only when callers fail to free the control explicitly.

Important behavior preserved:

- Explicit `free()` still removes the LOB file from the owning connection.
- Explicit `free()` still closes and deletes the temporary file.
- Explicit cleanup still reports `IOException`.
- Fallback cleanup remains best-effort because `Cleaner` cannot report checked exceptions.

## Next Java 21 cleanup candidates

Run:

```bash
./dev/modernization-audit.sh
```

Then inspect:

```text
build/reports/modernization/modernization-audit.md
```

Likely next candidates:

1. Remaining finalizers in JDBC/client/server classes.
2. `java.security.AccessController` and `doPrivileged` usage.
3. `SecurityManager` references which only exist for legacy Java runtime assumptions.
4. `Vector` and `Hashtable` usage in hot or internal paths, only where synchronization is unnecessary.

## Cleanup order

Prefer this order:

```text
optional / low-risk modules first
client cleanup
server cleanup
engine cleanup last
```

The engine should be touched only with focused tests or strong compatibility confidence.

### Network Server `-noSecurityManager` command surface

DelosDB still accepts the inherited `-noSecurityManager` switch as a no-op so existing startup scripts do not fail, but the switch is no longer advertised in Network Server usage text and no longer toggles internal server state. The active runtime baseline is Java 21+, where DelosDB does not install or manage a JVM SecurityManager.


- Replaced `EmbedPreparedStatement.finalize()` with Cleaner fallback activation cleanup while preserving explicit close as the primary path.

### Client JDBC finalizer cleanup batch

The client connection, client statement, logical connection, and pooled connection classes no longer override `Object.finalize()`. DelosDB now treats explicit `close()` and existing ownership paths as the supported cleanup mechanism for these client-side JDBC objects on Java 21+.

The remaining high-risk embedded engine connection and statement finalizers have now been removed. DelosDB relies on explicit close paths for embedded JDBC connections/statements, result-set close for single-use activations, and targeted Cleaner fallbacks only for resources where a safe non-owning cleanup state exists.


### Embedded engine finalizer cleanup batch

The embedded engine no longer overrides `Object.finalize()` in `EmbedConnection` or `EmbedStatement`. DelosDB treats explicit JDBC `close()` calls as the supported lifecycle path for embedded connections and statements on Java 21+. This avoids running connection close or activation lifecycle logic from GC/finalizer threads. Single-use activations continue to be closed through result-set close, while prepared-statement activation fallback cleanup remains handled by the dedicated Cleaner state introduced earlier.

### SecurityManager audit cleanup batch

The production modernization audit now focuses on active runtime modules rather than inherited test/demo/history content. The audit can be run in report mode or verification mode:

```bash
./dev/modernization-audit.sh
./dev/modernization-audit.sh --verify
```

Verification mode now fails if production code reintroduces any of these Java 21 cleanup regressions:

- `Object.finalize()` overrides
- direct JVM security-manager inspection calls
- privileged-action cleanup wrappers

This batch also removed stale Network Server localization messages that described installing or disabling a JVM SecurityManager. The inherited `-noSecurityManager` switch remains accepted as a no-op for compatibility, but the active Java 21 server startup path no longer exposes SecurityManager installation failures or authentication warnings tied to that removed mechanism.

### Tools collection cleanup batch

The first synchronized-collection cleanup batch targets low-risk tooling code rather than engine runtime internals. URL validation and JDBC result display utilities now use `ArrayList`/`List` where no external synchronization or legacy `Vector` API contract is required. Internal ij result contracts which still expose `Vector` remain unchanged until a separate compatibility review.

### Identifier and sysinfo collection cleanup batch

This batch removes additional low-risk `Vector` usage from utility code where no external synchronization or legacy `Vector` API contract is required. Identifier parsing now uses `ArrayList`/`List` for local accumulation before converting to arrays or identifier-list strings. Statement cache diagnostics and sysinfo zip-location merging now also use `List` collections for private iteration state.

The batch intentionally does not touch store, compiler, daemon, lock, or public ij vector-result contracts. Those remain audit-only until their ownership and concurrency behavior are reviewed separately.

### Client collection cleanup batch

This batch removes low-risk client-side `Hashtable` usage from private lookup/cache state. Column metadata name lookup, positioned-update cursor maps, client cursor-name caches, and DRDA trace code-point names now use `HashMap`/`Map` internally. These structures are connection/metadata owned and do not expose `Hashtable` as part of a public API contract.


### Classfile collection cleanup batch

This batch replaces low-risk synchronized collection usage in the internal classfile generation and inspection package. Constant-pool entries, member tables, attribute lists, and implemented-interface accumulation now use `ArrayList`/`List` and `HashMap`/`Map` internally. Public enumeration-style return behavior is preserved with `Collections.enumeration(...)` where callers still expect `Enumeration`.

The batch intentionally does not touch compiler query-tree vectors, store/sort structures, lock tables, daemon queues, or public ij vector-result contracts. Those remain audit-only until their ownership and concurrency behavior are reviewed separately.
