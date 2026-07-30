# JDK 25 Class-File API generation modernisation

## Decision

DelosDB generates SQL activation classes with the standard JDK 25 Class-File
API:

```text
java.lang.classfile
```

The inherited compiler boundary remains unchanged:

```text
Bound SQL tree
    -> ExpressionClassBuilder / ActivationClassBuilder
    -> JavaFactory
    -> ClassBuilder
    -> MethodBuilder
    -> LocalField
    -> ClassFileJava
```

Compiler nodes do not import `java.lang.classfile` directly. They depend on the
existing DelosDB generation contract. No `GeneratedClassPlan`,
`GeneratedClassBackend`, second general IR, normal backend selector, or fallback
backend is introduced.

## Final architecture

```text
SQL parse and bind
    -> optimise
    -> JavaFactory / ClassBuilder / MethodBuilder / LocalField
    -> ClassFileJava
    -> JDK 25 class-file bytes
    -> generated activation loading
```

`ClassFileJava` is the sole production implementation. No external ASM
dependency remains.

The fixed monitor registration is:

```text
derby.module.javaCompiler=org.apache.derby.impl.services.bytecode.classfile.ClassFileJava
```

There is no production or test-time alternative backend after Compiler Phase 6.

## Semantic invariants

The migration does not change:

```text
SQL semantics
generated activation interfaces
null behaviour
exception behaviour
SQLStates
statement-cache behaviour
class-loader ownership
query results
JDBC or DRDA protocol fields
storage or MVCC behaviour
```

Byte-for-byte identity with the retired backend was never required. Verification,
contract parity, semantic parity, language-suite compatibility, integration
coverage, lifecycle evidence, and performance measurements were required.

## Implementation status

```text
Compiler Phase 1 status: VERIFIED
Compiler Phase 2.1 status: VERIFIED
Compiler Phase 2.2 status: VERIFIED
Compiler Phase 3 status: VERIFIED
Compiler Phase 4 status: VERIFIED
Compiler Phase 5.1 status: VERIFIED
Compiler Phase 5.2 status: VERIFIED
Compiler Phase 6 status: IMPLEMENTED / PENDING VERIFICATION
```

## Frozen generation contract

The inherited boundary remains:

```text
1 JavaFactory method
8 ClassBuilder methods
43 MethodBuilder signatures
32 MethodBuilder operation names
0 LocalField methods
52 total declared methods
2 methods declaring checked exceptions
```

Every MethodBuilder signature is mapped to one of ten executed behaviour groups:

```text
method lifecycle
parameters and constants
field access
objects and arrays
receiver and conversion behaviour
stack and statement behaviour
control flow
invocation
constructor chaining
statement splitting
```

The contract digest remains fixed. The production behaviour test generates the
complete fixture twice through `ClassFileJava`, proves deterministic bytes and
digest, loads the generated class, executes all ten groups, and verifies the
representative failure contracts.

## Migration evidence

### Phase 1 — inventory and baseline

The repository inventoried the generation abstraction, compiler consumers,
backend imports, build dependencies, module edges, generated class-file version,
class size, generation time, allocation, class loading, loaded-class count, and
steady reflective execution.

### Phase 2.1 — contract freeze

Reflection and source inventory froze all 52 interface methods and the exact
checked-exception declarations. Compiler nodes remained isolated from backend
libraries.

### Phase 2.2 — operation behaviour freeze

All 43 MethodBuilder signatures were mapped to ten executable groups. The
fixture covered constants, parameters, fields, construction, arrays, primitive
and reference conversion, stack operations, branches, calls, exceptions, and
statement splitting.

### Phase 3 — bounded JDK vertical slice

A test-only Class-File API implementation proved class creation, fields,
methods, parameters, constants, field access, calls, conversions, branches,
returns, parsing, verification, loading, execution, and exception comparison.

### Phase 4 — complete differential backend

The JDK implementation reached complete MethodBuilder coverage:

```text
MethodBuilder signatures covered: 43
Behaviour fixture groups executed: 10
Generated methods: 38
Unsupported MethodBuilder operations: 0
Generated class-file major: 69
```

The campaign also measured class size, generation latency, generation
allocation, class loading, loaded-class deltas, and reflective execution. These
measurements were diagnostic and did not replace semantic acceptance.

### Phase 5.1 — production candidate acceptance

The complete backend moved into engine production source but remained
unregistered. Isolated acceptance covered:

```text
real SQL compilation
prepared reads and updates
primitive arithmetic and null branches
wide projection generation
SQLState preservation
complete inherited language suite
JDBC and DRDA
security and deserialisation boundaries
application UDT and aggregate paths
jlink modular-image DRDA
external SQLancer
class loading and shutdown
```

The candidate campaign exposed and corrected two inherited-contract details:

```text
null field owner means infer the owner from the receiver type
upCast may retype primitive computational values, including short -> int
```

### Phase 5.2 — production authority switch

`modules.properties` switched normal authority to `ClassFileJava`. The default
SQL proof, complete contract behaviour, normal language suite, default
JDBC/DRDA lane, modular image, and post-switch SQLancer campaign verified the
same fixed production path without an override.

### Compiler Phase 6 — external ASM removal

Phase 6 removes:

```text
AsmJava production source
external ASM dependency declarations
root ASM version and configuration
JPMS requires org.objectweb.asm
runtime ASM artifact composition
module compile-only ASM edges
benchmark ASM runtime edges
ASM baseline and differential tests
candidate-only and oracle-only Gradle tasks
candidate backend propagation into modular-image JVMs
JDK-internal ASM export flags left by the retired path
```

The final retained executable evidence is:

```text
frozen contract proof
complete ClassFileJava behaviour proof
real SQL production-selection proof
complete inherited language suite
focused JDBC/DRDA/security/UDT lane
normal modular-image DRDA lane
external SQLancer through fixed production registration
JDK 25 class-file verifier
```

The permanent gates are:

```text
delosGeneratedClassModernizationStaticAnalysis
delosGeneratedClassContractStaticAnalysis
delosGeneratedClassClassFileAsmRemovalStaticAnalysis
```

They prohibit external ASM source references, dependencies, runtime artifacts,
module requirements, retired oracle files and tasks, backend overrides, and
alternative production registration.

## Performance and lifecycle acceptance

Acceptance requires:

```text
no material steady-state execution regression
no unacceptable compilation regression
no generated-class leak
verified class-file major 69
```

The previous diagnostic comparison showed similar generation, loading, and
reflective execution behaviour, with higher Class-File API generation
allocation in the synthetic fixture. Phase 6 does not hide that measurement or
invent a threshold; the normal language, JDBC/DRDA, modular-image, and SQLancer
campaigns remain the authoritative compatibility evidence.

## Final dependency policy

No external ASM dependency remains.

The standard `java.lang.classfile` API is supplied by `java.base`. DelosDB does
not package a bytecode library, add an automatic bytecode module, expose a
backend-selection property, or keep a fallback implementation.

## Verification

Focused final verification:

```text
./gradlew \
  delosGeneratedClassModernizationStaticAnalysis \
  delosGeneratedClassContractStaticAnalysis \
  delosGeneratedClassClassFileAsmRemovalStaticAnalysis \
  :delosdb-tests:runDelosGeneratedClassContractFreezeTest \
  :delosdb-tests:runDelosGeneratedClassContractBehaviorTest \
  :delosdb-tests:runDelosGeneratedClassClassFileProductionTest \
  --console=plain
```

Built-in acceptance:

```text
./gradlew \
  :delosdb-tests:runDelosGeneratedClassClassFileProductionAcceptance \
  --console=plain
```

External SQLancer:

```text
./gradlew \
  delosGeneratedClassClassFileProductionSqlancerValidation \
  -Pdelosdb.compiler.classfile.production.sqlancer.command='<existing SQLancer command>' \
  --console=plain
```
