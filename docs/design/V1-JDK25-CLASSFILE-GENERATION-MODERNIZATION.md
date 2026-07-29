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
org.apache.derby.impl.services.bytecode.classfile.ClassFileJava
```

`AsmJava` remains compiled only as a bounded post-cutover test oracle until
Compiler Phase 6 removes it and the external ASM dependency.

Therefore DelosDB will not add `GeneratedClassPlan`, `GeneratedClassBackend`, or
another general bytecode IR. Such a layer would duplicate the existing contract
and increase code size without creating a new responsibility.

## Final compiler architecture

During the Phase 5.2 proof period:

```text
Bound SQL tree
    -> existing JavaFactory/ClassBuilder/MethodBuilder contract
        -> ClassFileJava           sole normal production backend
        -> AsmJava                 bounded test oracle only
```

After Phase 6:

```text
Bound SQL tree
    -> existing JavaFactory/ClassBuilder/MethodBuilder contract
        -> ClassFileJava           sole backend
```

The final implementation name is fixed as `ClassFileJava`; the verified vertical
slice and complete differential campaign proved the implementation remains
bounded by the inherited DelosDB generation contract.

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
Compiler Phase 2.2 status: VERIFIED
Compiler Phase 3 status: VERIFIED
Compiler Phase 4 status: VERIFIED
Compiler Phase 5.1 status: VERIFIED
Compiler Phase 5.2 status: IMPLEMENTED / PENDING VERIFICATION
Compiler Phase 6 status: NOT STARTED — NEXT
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

Compiler Phase 2 is closed. The Phase 2.2 focused test, contract static gate,
generated-class baseline, complete language suite, JDK 25 verifier, and
modular-image verification are green.

Compiler Phase 3 is verified. It introduced the package-internal, test-only
JDK 25 Class-File API backend and proved the bounded vertical slice through the
same inherited DelosDB abstraction as ASM. The focused differential task,
complete language suite, JDK 25 verifier, modular-image DRDA lane, and normal
closeout gates are green.

Compiler Phase 4 is verified. It extends that same backend to the complete inherited
`MethodBuilder` operation surface and proves all 43 signatures and ten behavior
groups. Phase 5.1 promoted the exact verified implementation into engine
production source and completed the focused language, JDBC/DRDA, modular-image,
SQLancer, lifecycle, and performance acceptance campaign. Phase 5.2 switches
`modules.properties` to the Class-File API backend. ASM remains compiled only
as a bounded differential and baseline oracle until its Phase 6 removal.

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

The JDK backend is test-only during this phase. The implemented bounded slice
covers class and field creation, methods and parameters, primitive and typed-null
constants, generated and external field access, static/virtual/interface and
cached method invocation, primitive conversion, reference casts and upcasts,
`instanceof`, null and Boolean branches, and typed returns. It parses, verifies,
loads, and executes ASM and Class-File API classes and compares method
signatures, results, and representative exceptions.

The original Phase 3 fixture remains as a bounded regression proof. Phase 4
removes its former unsupported-operation boundary by implementing arrays,
object construction, stack choreography, checked-exception declarations, and
statement splitting in the same implementation that later becomes the production-packaged Phase 5.1 candidate.

## Compiler Phase 4 — complete differential backend

Extend the Class-File API backend to every operation that is actually used by
DelosDB compiler nodes, including arrays, constructor calls, stack operations,
casts, exception declarations, and statement splitting.

The implemented Phase 4 increment reuses the frozen 43-signature ASM behavior
oracle. Both backends generate the same 38-method fixture, execute all ten
behavior groups, compare public method and checked-exception contracts, compare
representative runtime failures, and record generation, allocation, class-size,
class-loading, loaded-class, and reflective execution diagnostics.

```text
MethodBuilder signatures covered: 43
Behavior fixture groups executed: 10
Generated methods per backend: 38
Unsupported MethodBuilder operations: 0
Normal runtime backend selector: none
```

ASM remained the sole production authority through Phase 4. Phase 5.1 moved
the complete JDK backend unchanged into engine production source and verified
it through focused acceptance tasks. Phase 5.2 now uses that exact accepted
implementation as the fixed normal registration; ASM is no longer registered.

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

## Compiler Phase 5.1 — production candidate acceptance

The complete backend is compiled into the engine under:

```text
org.apache.derby.impl.services.bytecode.classfile.ClassFileJava
```

The verified Phase 5.1 campaign used Derby's inherited module override only
inside isolated test JVMs while normal production remained on ASM. That campaign
is complete. There is still no user-facing backend selector and no normal
runtime mode switch.

The built-in campaign covers:

```text
real SQL compiler selection and execution
complete 43-signature differential generation
complete inherited language suite
focused JDBC and DRDA integration
security and application-UDT boundaries
jlink modular-image DRDA execution
class loading and shutdown diagnostics
```

SQLancer remains an explicit external command. The dedicated candidate task
injects the internal module selection through `JAVA_TOOL_OPTIONS` without
making SQLancer a normal build dependency.

## Compiler Phase 5.2 — switch authority

The Phase 5.1 acceptance requirements are verified:

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

Phase 5.2 therefore changes the fixed normal registration to:

```text
derby.module.javaCompiler=org.apache.derby.impl.services.bytecode.classfile.ClassFileJava
```

The Class-File API backend is now the fixed normal production authority.
The production path now executes exactly one backend: the JDK 25 Class-File API
implementation. A focused default-selection proof, the normal language suite,
normal JDBC/DRDA lane, normal modular-image lane, and post-switch SQLancer task
must all remain green without a backend override.

ASM remains compiled only as a bounded test oracle for this proof period. Its
retained evidence is limited to the deterministic ASM baseline, the frozen
behavior oracle, and the complete differential test. It is not registered as a
normal module and is removed completely in Phase 6.

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
