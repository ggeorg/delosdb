# DelosDB permanent gate policy

## Purpose

Permanent gates protect durable product and architecture invariants before slower integration and
compatibility suites run. They must remain small, deterministic, and resilient to harmless source
refactoring.

## S0 authorities

`./gradlew s0CloseoutVerification` has seven direct dependencies:

| Task | Evidence |
|---|---|
| `delosModuleDependencyBoundaryStaticAnalysis` | generated dependency/resource reports and executable monitor structure |
| `delosV1ModuleArchitectureStaticAnalysis` | settings, Gradle dependencies, artifacts, providers, module descriptors, structured target manifest |
| `delosGeneratedClassStaticAnalysis` | compiler contract, implementation inventory, dependency/import boundaries, executable test inventory |
| `delosRepositoryIntegrityStaticAnalysis` | javac AST inventory, monotonic metrics, classified catches and duplicates, compiler authority |
| `verifyDelosRuntimeStorageProviders` | built runtime artifacts and provider discovery |
| `delosJdk25ClassFileBytecodeVerifier` | generated class bytes and JDK verifier behavior |
| `:delosdb-tests:runDelosSecurityTruthTest` | executable security behavior |

## Allowed evidence

Permanent gates may use:

```text
Java AST structure
module and dependency metadata
service-provider files
JPMS descriptors
artifact inventories
bytecode and class loading
runtime tests
exact structured manifests with stable schemas
retired-file existence checks for permanent architecture boundaries
```

## Prohibited evidence

Permanent gates must not fail because of:

```text
Markdown or documentation wording
comments or Javadocs
TODO/FIXME text
roadmap status
commit messages
archive or overlay names
exact report sentences
source line numbers
private helper names that are not architectural contracts
another task's presence as text in a Gradle script
```

Documentation is reviewed for correctness by humans and normal change review. It is not executable
architecture authority.

## Gate lifecycle

A temporary implementation gate may be useful while a migration is active. At closeout it must be:

1. promoted into a durable structural or executable invariant;
2. merged into an existing permanent gate; or
3. retired.

Historical stage gates must not accumulate in S0. New permanent tasks require a distinct durable
invariant that is not already covered by the seven authorities.

## Monotonic repository integrity

The repository-integrity baseline records accepted inherited debt. New findings fail immediately;
accepted counts may decrease.

Current permanent values include:

```text
dead private production methods: 0
dead private production fields:  0
exact duplicate groups:          48
silent empty catches:            102
generic catches:                 434
methods >= 100 lines:            443
complexity >= 20:                169
classes >= 1000 lines:           137
parse errors:                    0
```

The remaining duplicates and broad catches have structural classifications. Metrics are not a reason
to perform mechanical refactoring.

## Performance and external validation

Wall-clock benchmarks, JMH, JFR recordings, jcstress, SQLancer, long-reader soak tests, baseline
capture, and destructive fault campaigns are opt-in or closeout evidence. They do not belong in
normal S0.
