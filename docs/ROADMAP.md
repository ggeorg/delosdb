# DelosDB Roadmap

DelosDB is being modernized in two tracks:

1. preserve and verify the proven Derby-compatible SQL/JDBC foundation;
2. evolve that foundation into a modular database platform with explicit extension contracts.

The roadmap intentionally keeps compatibility, test recovery, and platform APIs separated so that DelosDB does not lose Derby's most valuable property: a small, embeddable, standards-oriented Java SQL engine.

## Phase 0 — Modernization foundation

Status: largely complete.

Goals:

- Replace the supported developer workflow with Gradle only.
- Move production sources into real Gradle modules.
- Preserve legal attribution and Derby-compatible runtime behavior.
- Establish release metadata, artifact inventory, Maven Local publication, and distribution checks.
- Remove unsupported Ant workflow from the supported path.
- Add smoke tests from classes and assembled jars.
- Add modernization audit checks for legacy Java patterns.
- Add reproducible benchmark baselines.

Key outputs:

- `delosdb-engine`, `delosdb-client`, `delosdb-server`, `delosdb-tools`, `delosdb-runner`, `delosdb-commons`, `delosdb-optionaltools`, `delosdb-osgi-stub`
- `delosdb-buildtools`
- `delosdb-storeless`
- `fullVerification`
- `dev/modernization-audit.sh --verify`
- `dev/benchmark-baseline.sh`

## Phase 0.5 — Inherited test activation and harness modernization

Status: active.

Goals:

- Recover inherited Derby tests under Gradle without restoring `derbyall` as the supported workflow.
- Keep broad activation compile-only until runtime behavior is reviewed.
- Promote execution only through small, stable, curated islands.
- Keep old harness, path-sensitive scripts, message-sensitive tests, optimizer-plan-sensitive tests, and missing-resource tests isolated until each can be modernized intentionally.
- Maintain a visible activation ledger so the recovered inherited test surface is auditable.

Rules:

- Do not run `derbyall`.
- Do not reintroduce the old harness as the supported workflow.
- Do not activate full `lang`, `jdbcapi`, `jdbc4`, `store`, `tools`, or `derbynet` suites in one step.
- Prefer compile activation first, then curated execution promotion.
- Keep `fullVerification` green after each island.

Primary commands:

```bash
./gradlew :delosdb-tests:listActivatedDerbyTestIslands
./gradlew :delosdb-tests:compileActivatedDerbyTests
./gradlew :delosdb-tests:runActivatedDerbySmokeExecutionIslands
./gradlew fullVerification
```

## Phase 1 — API/SPI boundaries and stability markers

Status: active. IndexProvider v0 is checkpointed for the built-in `btree` seam; the physical `IndexAccess` SPI exists and `btree` is bridged structurally while Derby remains authoritative for first-release behavior.

Goals:

- Create the first explicit DelosDB API/SPI home.
- Add stability annotations for public, experimental, internal, and legacy-internal surfaces.
- Separate extension-facing contracts from inherited implementation internals.
- Keep JDBC as the compatibility baseline while exposing a small modern API layer.

Planned artifacts:

- `delosdb-spi` or `delosdb-api` module
- `@PublicSpi`
- `@ExperimentalSpi`
- `@InternalApi`
- `@LegacyInternal`
- API package conventions and compatibility policy

Definition of done:

- Stability annotations exist and are documented.
- Initial API/SPI module compiles independently.
- No extension contract depends on an inherited implementation package by accident.
- Existing runtime jars and JDBC behavior remain compatible.
- IndexProvider v0 records `btree` provider identity, persists metadata, exposes descriptor diagnostics, and supports opt-in provider cost visibility.
- Controlled internal IndexProvider registration exists before provider discovery or external jar loading.
- `IndexAccess` begins the physical index provider lifecycle contract and the built-in `btree` provider has a structural bridge without wiring new storage or public provider names yet.
- First release defers `hash` and other non-Derby physical providers in favor of Derby compatibility, Java 21 modernization, and LEGO-style modules.

Checkpoint:

- `docs/PHASE-1-INDEX-PROVIDER-V0-CHECKPOINT.md`
- `docs/PHASE-1-INDEX-ACCESS-SPI.md`
- `docs/PHASE-1-INDEX-ACCESS-BTREE-BRIDGE.md`

## Phase 2 — Extension registry and provider loading

Current entry point:

- `IndexProviderRegistry` supports explicit internal registration of provider instances.
- Built-in `btree` registration remains the only public SQL provider path.
- `ServiceLoader` and external jar loading are intentionally deferred.

Goals:

- Introduce a small extension registry independent of the CLI and old tooling.
- Define provider metadata, lifecycle, version compatibility, and diagnostics.
- Support local discovery for platform experiments without destabilizing embedded JDBC.

Candidate provider families:

- `StorageProvider`
- `IndexProvider`
- `FunctionProvider`
- `RewriteRule`
- `CostModel`
- `DiagnosticProvider`

Definition of done:

- Providers can be declared, discovered, and validated.
- Registry diagnostics are deterministic and testable.
- Provider loading is isolated from the existing Derby-compatible runtime path unless explicitly enabled.

## Phase 3 — StorageProvider bridge

Goals:

- Turn `delosdb-storeless` into a practical bridge for storage experimentation.
- Identify the minimum storage contract needed by the compiler, optimizer, and execution layers.
- Keep the embedded Derby storage engine as the default provider.
- Allow experimental storage providers behind explicit preview boundaries.

Candidate work:

- storage capability model
- transaction and locking expectations
- catalog/storage split review
- in-memory and test providers
- compatibility tests for provider-backed execution

Definition of done:

- Default storage remains compatible.
- At least one non-default provider can be registered in a controlled test path.
- Provider failure modes produce structured diagnostics.

## Phase 4 — Optimizer and index extension points

Goals:

- Expose narrow extension seams for indexes and cost modeling.
- Preserve Derby optimizer correctness while making selected decisions inspectable and replaceable.
- Support experiments with specialized indexes and statistics without broad engine rewrites.

Candidate work:

- `IndexProvider` contract
- index capability descriptors
- statistics bridge
- cost-model adapter
- explain-plan diagnostics

Definition of done:

- Existing indexes remain the default.
- Experimental indexes can participate in planning through a reviewed bridge.
- Plan changes are observable through tests and diagnostics.

## Phase 5 — Modern embedded API layer

Goals:

- Keep JDBC as the standard compatibility API.
- Add a small Java-native convenience API for embedded use.
- Provide examples for plain Java, server applications, and desktop/local apps.
- Avoid building an ORM or framework-specific abstraction.

Candidate work:

- fluent embedded boot API
- structured configuration
- typed diagnostics
- lifecycle management
- examples for Java applications

Definition of done:

- Embedded boot is simpler than raw JDBC setup.
- JDBC remains fully available.
- The modern API is additive and optional.

## Phase 6 — Release hardening and identity cleanup

Goals:

- Decide final runtime jar naming policy.
- Add sources jars and javadocs for publication.
- Harden Maven Central publication steps if public releases are desired.
- Clarify binary compatibility and migration policy.
- Remove or document remaining inherited root artifacts that are not part of the Gradle workflow.

Candidate decisions:

- keep Derby-compatible jar names forever as compatibility aliases;
- add DelosDB-branded jar names alongside compatibility jars;
- migrate binary distribution naming in a major preview boundary.

Definition of done:

- Artifact naming policy is explicit.
- Public release process is documented and reproducible.
- Legacy artifact directories do not confuse contributors.

## Current near-term priority

The next near-term work should stay focused:

1. Keep `fullVerification` green.
2. Keep public index-provider SQL limited to real registered providers.
3. Prove controlled test-scope provider registration before external discovery.
4. Avoid storage rewrites until the IndexProvider seam has stable registration, physical `IndexAccess` adapters, and failure-mode diagnostics.
