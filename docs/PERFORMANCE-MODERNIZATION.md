# DelosDB performance modernisation

## Principle

DelosDB modernises performance-sensitive infrastructure only after correctness,
ownership, and baseline evidence are stable. Modernisation does not justify a
second runtime authority or a permanent implementation switch.

## Current order

RawStore convergence and production closeout are complete. The performance plan
therefore proceeds in this order:

```text
Performance Phase 0       measurement and reproducibility
Performance Phase 0.5     generated compiler modernisation
Performance Phase 1       storage and execution optimisation on the converged path
Performance Phase 2       readable plans and storage-aware profiling
```

The historical proposal order that placed storage convergence after compiler
modernisation is no longer current; that convergence has already been completed.

## Performance Phase 0 — measurement

```text
expand JMH and macro benchmarks
add compilation-phase timing
add standard JFR recordings
add generated-class size and loading metrics
capture the ASM generated-class baseline
retain semantic checksums and reproducible metadata
```

## Performance Phase 0.5 — generated compiler modernisation

```text
inventory ASM and freeze the existing JavaFactory boundary
add generated-class contract and differential tests
implement the Class-File API vertical slice
complete the Class-File API backend
switch authority only after parity and performance proof
remove ASM and every transitional dependency
```

DelosDB reuses the inherited `JavaFactory`, `ClassBuilder`, `MethodBuilder`, and
`LocalField` contracts. It does not add a second compiler IR or speculative
backend façade.

## Performance Phase 1 — converged storage and execution

Storage optimisation now targets the already-converged architecture:

```text
one RawStore physical authority
one WAL and recovery path
direct positional byte-array page I/O
heap and MVCC access methods over the same durability authority
```

Future optimisation may improve group commit, buffer policy, generated
primitive execution, and operator cost only through measured changes to that
single path.

## Code-size rule

Every compiler-modernisation overlay reports production and test deltas,
public/API changes, module edges, artifact bytes, transitional code, and removal
debt. The completed ASM migration must delete its transitional backend and
dependencies.
