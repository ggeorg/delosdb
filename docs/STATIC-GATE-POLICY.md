# DelosDB static gate policy

This document records the cleanup after the engine-depth roadmap gates became too brittle.

## Rule

S0 is for stable engineering checks only. It must not fail because a roadmap document says `current`, `closed green`, `delivered by`, or uses different phase wording.

## Allowed in `s0CloseoutVerification`

S0 may include gates that check concrete code and repository safety boundaries:

- storage/server static analysis
- heap compatibility and deserialization hardening
- module dependency boundaries
- Derby module parity
- runtime provider discovery
- workspace churn
- stale Gradle scripts
- cleanup-script hygiene
- fork-diff classification rows, when file/classification based only
- advisory reports that do not fail on roadmap prose

## Not allowed in `s0CloseoutVerification`

S0 must not depend on gates whose main purpose is to validate roadmap prose, phase status, commit-message text, or overlay-delivery wording. These remain useful as optional/advisory checks, but they are not closeout gates.

Examples of forbidden S0 criteria:

- `Execution state: current`
- `Execution state: closed green`
- `delivered by <overlay>.zip`
- roadmap phase order text
- one-line commit-message markers
- documentation-only proof rows

## Long-running validation

Performance, concurrency, JMH, jcstress, SQLancer, long-reader soak, and benchmark tasks must stay outside S0. They should remain opt-in validation tasks.

## Removed obsolete roadmap/prose tasks

The following historical roadmap/prose tasks were removed from Gradle registration after the engine-depth closeout cleanup. Their source documents may remain useful as design notes, but they must not be executable gates:

- `delosBalancedStorageModernizationCloseoutStaticAnalysis`
- `delosStorageModernizationTradeoffAuditStaticAnalysis`
- `delosStorageModernizationTradeoffAuditRound2StaticAnalysis`
- `delosNextEngineDepthRoadmapContractsStaticAnalysis`
- `delosSharedStorageServiceExtractionAuditStaticAnalysis`

Implementation or compatibility checks should be expressed as code/test markers or normal Gradle test tasks, not as roadmap-status prose validation.
