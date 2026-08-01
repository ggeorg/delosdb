# DelosDB generated-class architecture

Status: **verified and permanently closed**

## Decision

DelosDB generates SQL activation classes with the JDK 25 Class-File API through the inherited
compiler-facing boundary:

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

`ClassFileJava` is the sole production backend. SQL compiler nodes do not import
`java.lang.classfile` directly. There is no second generated-code IR, backend selector, fallback
backend, or external ASM dependency.

## Fixed registration and authority

```text
derby.module.javaCompiler=org.apache.derby.impl.services.bytecode.classfile.ClassFileJava
```

The generic Derby monitor remains configurable for other inherited modules, but external boot,
system, application, service, and database properties cannot replace `JavaFactory`.

## Frozen contract

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

The executable behavior proof exercises all 52 methods. Ten behavior groups cover the inherited
contract, with supplemental category-two `long`/`double` branch-merge cases.

Frozen generated fixture:

```text
Generated methods:     38
Generated class bytes: 3465
SHA-256: 31df8ee46dcc6256a7ad556c90d5772e69c8656670c4356cd0df3205a47abefe
Statement split point: 128
```

## Permanent evidence

```text
delosGeneratedClassStaticAnalysis
GeneratedClassContractFreezeTest
GeneratedClassContractBehaviorTest
GeneratedClassProductionTest
:delosdb-tests:runDelosGeneratedClassProductionAcceptance
delosJdk25ClassFileBytecodeVerifier
```

The production test proves representative SQL compilation, SQLState preservation, exact system and
application override rejection, plan-cache reuse, and absence of an external bytecode dependency.
The focused acceptance also covers JDBC/DRDA, class loading, and the modular runtime image without
running the full Derby language suite.

## Direct API boundary

Authorized direct Class-File API users:

```text
ClassFileJava
DelosJdk25ClassFileVerifier
```

## Non-compromise rules

Future compiler work must preserve:

- SQL semantics and generated activation interfaces;
- null and exception behavior;
- SQLStates;
- class-loader and statement-cache ownership;
- deterministic generation;
- the existing `JavaFactory` / `ClassBuilder` / `MethodBuilder` boundary;
- no runtime backend selector or fallback.

Phase 10.1 stable plan modelling belongs above this completed generation boundary. It must not add a
second generated-class IR.
