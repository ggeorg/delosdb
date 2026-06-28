# DelosDB package naming strategy

## Purpose

This document defines how Java packages are named and assigned to Gradle modules in DelosDB.

Gradle modules define ownership. Packages describe local purpose inside that ownership. Packages
must not invent architecture that the build does not enforce.

## Core rule

Production packages are owned by exactly one Gradle module.

No production package may appear in more than one module. This prevents split packages, keeps JPMS
legal, and makes the module graph verifiable.

Test packages are checked separately. Tests may mirror production package names only when inherited
Derby tests require it or when explicitly allowed.

## Namespaces

DelosDB has two Java namespace families.

```text
org.apache.derby.*
```

means inherited or adapted Derby code. This includes original Derby source, Derby internals carried
forward for compatibility, and Derby files modified in place by DelosDB.

```text
io.github.ggeorg.delosdb.*
```

means DelosDB-owned top-level code.

The two namespaces are not mixed inside one package.

## Inherited Derby code

Inherited Derby code is owned at package granularity.

A package under `org.apache.derby.*` is never split across modules. If a Derby concern is extracted,
the whole Derby package moves to the new owning module as one unit. The package name does not
change.

Adaptation is not extraction.

A Derby class may contain DelosDB changes such as hooks, routing, provider seams, trace calls, or
compatibility adjustments. That class remains in its original Derby package and original owning
module.

A new DelosDB-owned top-level class should not be created under `org.apache.derby.*` unless it is a
required compatibility shim for inherited Derby package ownership.

## DelosDB-owned code

Each module has explicit allowed DelosDB package roots. The mapping table is the source of truth.

A package must live under the allowed root of its owning module. No two modules may own the same
exact package.

| Module | Allowed DelosDB package root |
|---|---|
| `delosdb-api` | `io.github.ggeorg.delosdb.api` |
| `delosdb-spi` | `io.github.ggeorg.delosdb.spi` |
| `delosdb-runtime-api` | `io.github.ggeorg.delosdb.runtime` |
| `delosdb-engine` | `io.github.ggeorg.delosdb.engine` |
| `delosdb-client` | `io.github.ggeorg.delosdb.client` |
| `delosdb-server` | `io.github.ggeorg.delosdb.server` |
| `delosdb-runner` | `io.github.ggeorg.delosdb.runner` |
| `delosdb-commons` | `io.github.ggeorg.delosdb.commons` |
| `delosdb-derby-store-api` | `io.github.ggeorg.delosdb.derby.store` |
| `delosdb-storage-api` | `io.github.ggeorg.delosdb.storage` |
| `delosdb-storage-io` | `io.github.ggeorg.delosdb.storage.io` |
| `delosdb-storage-bridge` | `io.github.ggeorg.delosdb.storage.bridge` |
| `delosdb-storage-derby` | `io.github.ggeorg.delosdb.storage.derby` |
| `delosdb-storage-mvcc` | `io.github.ggeorg.delosdb.storage.mvcc` |
| `delosdb-storeless` | `io.github.ggeorg.delosdb.storage.storeless` |

The storage family deliberately uses a shared prefix:

```text
io.github.ggeorg.delosdb.storage
io.github.ggeorg.delosdb.storage.derby
io.github.ggeorg.delosdb.storage.mvcc
io.github.ggeorg.delosdb.storage.bridge
```

This is a naming family, not Java package ownership inheritance. The bare `storage` package belongs
only to `delosdb-storage-api`. Provider modules own their full provider sub-root.

## API, SPI, and internal placement

Placement follows the consumer, not the topic.

Public user-facing contracts belong in `delosdb-api`.

Provider or plugin extension contracts belong in `delosdb-spi`.

Provider-neutral storage contracts belong in `delosdb-storage-api`.

Low-level inherited runtime/service contracts belong in `delosdb-runtime-api`.

Implementation, proof, diagnostic, and internal trace code remains in its owning implementation
module until a real consumer forces promotion.

Nothing is promoted to API or SPI speculatively.

## Package depth

Packages are shallow by default.

A subpackage is introduced only when there are two or more groups with different dependency
direction, change-driver, or ownership pressure.

A topic alone does not justify a package.

Avoid architectural-sounding package names unless the build already enforces that architecture.

Do not use `rdbms` as a package segment. The whole project is an RDBMS.

## Engine model, trace, and diagnostics code

The DelosDB-owned engine model is not the same thing as tracing. The intended internal package
shape inside `delosdb-engine` is:

```text
io.github.ggeorg.delosdb.engine.model
  RDBMS building-block vocabulary and small engine model contracts

io.github.ggeorg.delosdb.engine.trace
  trace events, sinks, registries, and Derby execution hook helpers

io.github.ggeorg.delosdb.engine.diagnostics
  reader-facing formatters, summaries, and observed-plan reports
```

These packages are internal to `delosdb-engine`. They are not public API and not provider SPI.

`engine.model` names concepts such as statement kind, lifecycle stage, plan node kind, execution
node kind, storage provider kind, storage access kind, and transaction/recovery concept.

`engine.trace` records observations of those concepts from real execution points. Trace is an
observability mechanism, not an RDBMS building block.

`engine.diagnostics` renders or summarizes already-captured observations for students, researchers,
and focused proofs.

Storage modules must not depend on `delosdb-engine` in order to emit trace events. If storage
providers later need to emit shared trace events directly, the relevant contract must move to the
correct lower/shared module, such as `delosdb-storage-api`, `delosdb-spi`, or `delosdb-api`,
depending on the real consumer.

## Moving code across namespaces

A class leaves `org.apache.derby.*` only when it becomes DelosDB-owned code with a DelosDB package
and an owning DelosDB module.

Transitional adapters are allowed, but they must not duplicate package ownership or pretend that a
Derby package has been extracted when it has not.

No half-migrations:

```text
bad:
  DelosDB-owned implementation hidden under org.apache.derby.*
  DelosDB package placed in a module whose allowed root does not match
  same package appearing in two modules
```

## Enforcement

Mechanical checks enforce the objective rules:

```text
1. no production package appears in more than one module
2. DelosDB-owned packages must be under an allowed root for their module
3. forbidden package roots are rejected
4. package ownership reports are generated for review
```

Package-depth justification is reviewed, not automatically proven. A script can report depth and new
package roots, but humans decide whether a new subpackage is justified.

## Small-step rule

Package cleanup follows the small-step rule:

```text
change one package ownership problem
prove it
then continue
```

Do not rename the whole project to satisfy a naming strategy.

Do not promote internal code to API/SPI just because the name sounds general.

Do not create a new Gradle module unless a real dependency direction requires it.

## Immediate application

The package tree:

```text
io.github.ggeorg.delosdb.engine.rdbms.*
```

created a fake architecture inside `delosdb-engine` because the whole project is an RDBMS. It was
collapsed as a cleanup step.

The corrected internal shape is:

```text
io.github.ggeorg.delosdb.engine.model
io.github.ggeorg.delosdb.engine.trace
io.github.ggeorg.delosdb.engine.diagnostics
```

No behavior change. No new module. No API/SPI promotion. No storage dependency on engine. The point
is to make the RDBMS model visible without pretending it is a public API yet.
