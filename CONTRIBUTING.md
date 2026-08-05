# Contributing to DelosDB

DelosDB accepts focused, compatibility-preserving changes backed by executable evidence.
Documentation should explain current behavior, but documentation wording must never become a build
gate.

## Supported workflow

Use JDK 25 and the checked-in Gradle Wrapper from the repository root:

```bash
./gradlew build --console=plain
```

For normal iteration, run the smallest relevant verification set:

```bash
./gradlew <affected-module>:check <focused-test-or-gate> --console=plain
```

Run the permanent structural closeout when a change affects architecture, modules, generated code,
security boundaries, or repository integrity:

```bash
./gradlew s0CloseoutVerification --console=plain
```

Run the inherited Derby language suite only at meaningful integration or release boundaries:

```bash
./gradlew :delosdb-tests:derbyLanguageTests --console=plain
```

See [`docs/BUILDING.md`](docs/BUILDING.md).

## Architectural boundaries

Preserve these constraints unless a reviewed product decision explicitly changes them:

```text
one Derby SQL/JDBC/DRDA engine
one Derby RawStore persistence authority
heap and delos_mvcc as peer access methods
no parallel MVCC file store, WAL, checkpoint, recovery, or backup authority
ClassFileJava as the sole generated-class backend
no external ASM dependency or fallback backend
no engine -> MVCC implementation dependency
```

`delos_mvcc` remains explicit through `USING delos_mvcc`; the Derby-compatible heap remains the
default path.

## Change quality

- Keep changes small enough to review semantically.
- Prefer net-negative handwritten production code for cleanup and refactoring.
- Preserve SQLStates, exception causes, resource ownership, synchronization, branch order, and
  compatibility behavior unless the change intentionally revises them.
- Classify dead code, duplicate code, broad catches, and structural outliers before changing them.
- Do not consolidate client/server protocol mirrors or generated/JDBC boilerplate merely to reduce a
  metric.
- Do not add a static gate for a one-time implementation detail.
- Do not inspect Markdown, comments, exact report prose, or task wiring as architectural truth.
- Add or update focused runtime proof when behavior changes.

## Generated-class changes

Compiler-facing changes must preserve the frozen boundary:

```text
JavaFactory methods:         1
ClassBuilder methods:        8
MethodBuilder signatures:   43
LocalField methods:          0
Total contract methods:     52
```

Run:

```bash
./gradlew   delosGeneratedClassStaticAnalysis   :delosdb-tests:runDelosGeneratedClassProductionAcceptance   delosJdk25ClassFileBytecodeVerifier   --console=plain
```

## Storage and MVCC changes

Run the affected storage module checks and a directly relevant SQL or RawStore test. Typical
closeout coverage is:

```bash
./gradlew   :delosdb-derby-store-api:check   :delosdb-storage-derby:check   :delosdb-storage-mvcc:check   :delosdb-tests:runDelosMvccSqlIntegrationTest   --console=plain
```

Do not introduce a second persistence runtime or bypass Derby transaction ownership.

## Documentation changes

Public current documentation lives in `README.md` and `docs/`. Local/private planning and manuscript
material lives in `.delosdb-v1/`. Historical documents belong under `docs/history/` or
`.delosdb-v1/99-history/` and must not be presented as active roadmaps.

Update the current owner document when public behavior, durable state, module ownership, or
verification changes. Avoid duplicating the same status across many files.
