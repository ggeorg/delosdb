# DelosDB generated-class architecture

Status: VERIFIED AND CONSOLIDATED

## Decision

DelosDB generates SQL activation classes with the final JDK 25 Class-File API
from `java.lang.classfile`. The inherited compiler-facing boundary is stable:

```text
Bound SQL tree
    -> ExpressionClassBuilder / ActivationClassBuilder
    -> JavaFactory
    -> ClassBuilder
    -> MethodBuilder
    -> LocalField
    -> ClassFileJava
    -> generated activation class
```

SQL compiler nodes do not import `java.lang.classfile` directly. `ClassFileJava`
is the sole implementation and the only production source that uses the API.
There is no second generated-code IR, backend selector, fallback backend, or
dual-generation production path.

## Fixed registration

```text
derby.module.javaCompiler=org.apache.derby.impl.services.bytecode.classfile.ClassFileJava
```

## Stable contract

```text
JavaFactory methods:                    1
ClassBuilder methods:                   8
MethodBuilder signatures:              43
MethodBuilder operation names:         32
LocalField methods:                     0
Total declared methods:                52
Methods declaring checked exceptions:  2
Contract SHA-256: 13871aded0743d1c5da22687d8e05a525bb115649397708d487c44261fc57cc6
```

Every MethodBuilder signature is mapped to one of ten executable behavior
groups. The deterministic behavior proof generates the complete fixture twice,
compares bytes and digest, loads the class, executes all groups, and preserves
representative exception and statement-splitting contracts.

## Semantic invariants

The architecture must not change:

```text
SQL semantics
generated activation interfaces
null behavior
exception behavior
SQLStates
statement-cache behavior
class-loader ownership
query results
JDBC or DRDA protocol fields
storage or MVCC behavior
```

## Dependency policy

No external ASM dependency remains.

The engine uses the API supplied by `java.base`. Runtime artifacts contain no
separate bytecode library, JPMS contains no external bytecode module edge, and
normal execution exposes no compiler-backend property.

## Permanent executable evidence

```text
GeneratedClassContractFreezeTest
GeneratedClassContractBehaviorTest
GeneratedClassProductionTest
runDerbyLangSuite
runDelosGeneratedClassProductionJdbcDrdaTest
runDelosV1ModularImageDrdaTest
delosJdk25ClassFileBytecodeVerifier
```

The built-in aggregate is:

```text
:delosdb-tests:runDelosGeneratedClassProductionAcceptance
```

## One permanent generated-class gate

```text
delosGeneratedClassStaticAnalysis
```

The gate owns contract counts and digest, behavior-manifest completeness, sole
backend registration, direct-import boundaries, zero-external-ASM source/build/
module policy, stable task wiring, and removal of migration-only artifacts.

## SQLancer boundary

The repository provides the SQLancer profile skeleton and a wrapper that rejects
hidden `derby.module.javaCompiler` overrides:

```text
delosGeneratedClassProductionSqlancerValidation
```

External randomized SQLancer remains operator-supplied; the repository does not
bundle the SQLancer executable. Profile and wrapper wiring are verified, while a
real randomized campaign requires an actual caller-supplied command.

## Verification

Focused structure and contract:

```text
./gradlew \
  delosGeneratedClassStaticAnalysis \
  :delosdb-tests:runDelosGeneratedClassContractFreezeTest \
  :delosdb-tests:runDelosGeneratedClassContractBehaviorTest \
  :delosdb-tests:runDelosGeneratedClassProductionTest \
  --console=plain
```

Built-in production acceptance:

```text
./gradlew \
  :delosdb-tests:runDelosGeneratedClassProductionAcceptance \
  --console=plain
```

External SQLancer wrapper:

```text
./gradlew \
  delosGeneratedClassProductionSqlancerValidation \
  -Pdelosdb.compiler.generatedClass.production.sqlancer.command="<command>" \
  --console=plain
```

## Historical note

The completed migration inventoried the inherited generation surface, froze its
contract and behavior, proved a bounded JDK vertical slice, completed semantic
differential coverage, switched authority, and removed ASM. Those phases are
historical evidence; they are not active runtime modes or permanent build
concepts.
