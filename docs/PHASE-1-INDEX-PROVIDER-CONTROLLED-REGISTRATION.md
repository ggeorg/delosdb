# Phase 1 IndexProvider Controlled Registration

Status: initial internal seam.

## Purpose

DelosDB now has a controlled registration path for `IndexProvider` implementations before any discovery mechanism is introduced.

This step is intentionally smaller than plugin loading:

```text
explicit provider instance
  -> internal descriptor registration
  -> IndexProviderResolver
  -> capability/cost bridge
```

The registration path is internal and is used to prove the boundary. It does not add public SQL provider names, catalog-backed extension state, or `ServiceLoader` discovery.

## What is allowed here

```text
built-in btree provider registration
manual test-scope provider registration
resolver-based capability/cost checks
internal descriptors for enabled providers
```

## What is still not allowed

```text
fake public providers
public debug SQL syntax
ServiceLoader discovery
classpath scanning
external jar loading
new physical index storage
```

## Public SQL position

The only public index provider name remains:

```sql
CREATE INDEX idx ON t(c) USING btree;
```

Manually registered providers are not automatically accepted by SQL syntax. SQL provider admission remains a separate product decision.

## Next work

The next step is a test-scope provider fixture wired through the controlled registry to prove cost/capability behavior without making that provider public SQL.
