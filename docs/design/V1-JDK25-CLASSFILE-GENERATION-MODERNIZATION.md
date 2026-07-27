# JDK 25 Class-File API generation modernisation

## Decision

DelosDB will migrate generated SQL activation classes from ASM to the standard
Class-File API available in the JDK 25 baseline:

```text
java.lang.classfile
```

This is compiler-infrastructure modernisation. It is not presented as a
standalone steady-state query-performance optimisation.

The migration is valuable because the JDK API tracks the platform class-file
model directly and provides structured class, method, code, constant-pool, and
verification-oriented builders. DelosDB expects this to reduce backend-specific
bookkeeping and create a cleaner basis for later primitive-specialised execution.
Class size, generation latency, allocation, loading, and execution effects remain
measurements rather than assumptions.

## Existing architecture discovered by inventory

The compiler already has the narrow generation abstraction required for this
migration:

```text
Bound SQL tree
    -> ExpressionClassBuilder / ActivationClassBuilder
    -> JavaFactory
    -> ClassBuilder
    -> MethodBuilder
    -> LocalField
    -> current backend
```

Compiler nodes do not import ASM and do not import `java.lang.classfile`.

The current production backend is isolated in one implementation:

```text
org.apache.derby.impl.services.bytecode.asm.AsmJava
```

Therefore DelosDB will not add `GeneratedClassPlan`, `GeneratedClassBackend`, or
another general bytecode IR. Such a layer would duplicate the existing contract
and increase code size without creating a new responsibility.

## Final compiler architecture

During migration:

```text
Bound SQL tree
    -> existing JavaFactory/ClassBuilder/MethodBuilder contract
        -> AsmJava                 transitional production backend
        -> ClassFileJava           test-only differential backend
```

After cutover:

```text
Bound SQL tree
    -> existing JavaFactory/ClassBuilder/MethodBuilder contract
        -> ClassFileJava           sole production backend
```

The final implementation name is provisional until the vertical slice proves
that the class can remain small and readable.

There is no normal runtime backend selector. Differential selection exists only
inside focused tests.

## Semantic invariants

The migration does not change:

```text
SQL semantics
generated activation interfaces
null behaviour
exception behaviour
SQLStates
class-loading lifecycle
statement-cache behaviour
query results
```

Byte-for-byte equality is not required. Semantic equivalence, verification, and
performance acceptance are required.

## Implementation status

```text
Compiler Phase 1 status: VERIFIED
Compiler Phase 2.1 status: VERIFIED
Compiler Phase 2.2 status: IMPLEMENTED — PENDING VERIFICATION
Compiler Phase 3 status: NOT STARTED
```

Phase 2.1 freezes the exact inherited boundary before any second backend exists:

```text
1 JavaFactory method
8 ClassBuilder methods
43 MethodBuilder signatures
32 MethodBuilder operation names
0 LocalField methods
```

All 32 operation names have live SQL compiler-node use and an implementation in
`AsmJava`. The reflection-based contract test records a deterministic contract
digest so a later backend cannot silently reshape the compiler-facing API.

Phase 2.2 adds the executable ASM behavior oracle. Every MethodBuilder signature
is mapped to an executed behavior fixture covering lifecycle, constants and
parameters, fields, objects and arrays, conversions, stack operations, control
flow, invocation, constructor chaining, and statement splitting. Generated
bytes are reproduced twice to prove deterministic fixture construction, then
loaded and executed to freeze results, declared exceptions, and representative
runtime failures before the Class-File API backend exists.

## Compiler Phase 1 — inventory and ASM evidence

Phase 1 records:

```text
all production ASM imports
all direct Class-File API imports
the fixed production backend registration
all build and JPMS ASM dependency edges
the existing generation-contract surface
compiler-node isolation from backend libraries
generated class-file version and size
class generation time
class generation allocation
class loading time
loaded generated-class count
steady execution timing
```

The focused baseline generates a deterministic class through
`JavaFactory/ClassBuilder/MethodBuilder`, parses it with `ClassFile`, loads it,
executes constants, parameters, null branches, method invocation, field access,
and return paths, and writes:

```text
build/reports/delosdb/compiler/asm-generated-class-baseline.txt
```

Timing and allocation are diagnostic; no threshold is used in Phase 1.

## Required differential fixture matrix

The complete migration proof must cover representative generated activations
for:

```text
constants
parameters
column reads
null handling
primitive arithmetic
decimal arithmetic
casts
comparisons
Boolean short-circuiting
conditional branches
method calls
user-defined functions
aggregates
subqueries
wide projections
large generated methods
exceptions and SQLState preservation
```

Phase 1 establishes the deterministic generation and measurement harness with a
bounded subset. Phases 2 through 4 extend the same fixture family until every
`MethodBuilder` operation used by compiler nodes is covered. The inventory, not
a speculative feature list, determines completion.

## Compiler Phase 2 — freeze the existing backend boundary

ASM is already isolated behind the inherited contract. Phase 2 therefore does
not introduce another interface. The implemented contract-freeze increment:

```text
freezes JavaFactory/ClassBuilder/MethodBuilder/LocalField as the migration boundary
inventories all 43 MethodBuilder signatures and 32 operation names
proves every operation name has live compiler-node use
proves AsmJava implements every inventoried operation name
adds a deterministic reflection-based contract digest
keeps AsmJava authoritative
adds no production Java, public API, module, dependency edge, or runtime selector
```

The operation-behavior fixture increment maps all 43 MethodBuilder signatures to
ten executed fixture groups and keeps the ASM backend as the sole authority.
The focused test writes a stable oracle report for the later differential
backend. An operation may be removed only when both compiler-node usage and
compatibility evidence prove it obsolete.

Compiler Phase 2 closes only after the Phase 2.2 focused test, the contract
static gate, the generated-class baseline, language tests, and modular-image
verification are green. The next implementation step is Compiler Phase 3's
package-internal, test-only Class-File API vertical slice.

## Compiler Phase 3 — JDK vertical slice

Implement a package-internal Class-File API backend for:

```text
class and field creation
method creation and parameters
constants
field access
method invocation
primitive and object conversion
null test
conditional branch
return
```

For each fixture:

```text
generate with ASM
generate with the Class-File API
parse and verify both
load both
execute both
compare result or exception
```

The JDK backend is test-only during this phase.

## Compiler Phase 4 — complete differential backend

Extend the Class-File API backend to every operation that is actually used by
DelosDB compiler nodes, including arrays, constructor calls, stack operations,
casts, exception declarations, and statement splitting.

Compare:

```text
query results
SQLStates
exception causes
generated method signatures
class verification
class-file size
generation latency
class-loading latency
steady execution
allocation
```

Normal production executes one backend only.

## Compiler Phase 5 — switch authority

The Class-File API backend becomes authoritative only after:

```text
all language tests pass
all generated-code differential tests pass
SQLancer passes
module-image tests pass
JDBC and DRDA tests pass
no material compilation regression exists
no material execution regression exists
no generated-class leak is observed
```

ASM remains only as a test oracle for one bounded proof period.

## Compiler Phase 6 — remove ASM

After the proof period:

```text
delete AsmJava
remove the direct ASM dependency
remove org.objectweb.asm from module-info.java
remove ASM-only build configurations and fixture wiring
remove differential tests that no longer provide unique evidence
add a static gate prohibiting DelosDB-owned ASM imports and direct dependencies
```

The completed migration must be net-negative in production code and dependency
surface unless the final report identifies a verified correctness or performance
property that justifies otherwise.

## Performance acceptance

Acceptance requires:

```text
no material steady-state execution regression
no unacceptable compilation regression
no generated-class leak
```

Potential improvements such as smaller classes, lower generation allocation, or
faster construction are measured rather than assumed. Steady-state execution is
expected to depend primarily on emitted bytecode and HotSpot optimisation.

## Later execution work

Primitive specialisation or other generated execution improvements must use the
existing DelosDB generation contract. Compiler nodes do not call ASM or
`java.lang.classfile` directly.
