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

