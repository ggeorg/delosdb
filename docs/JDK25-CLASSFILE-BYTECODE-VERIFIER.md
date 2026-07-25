# JDK 25 Class-File API bytecode verifier

This document records the first DelosDB JDK 25 bytecode-modernization lane.

This is a verifier artifact, not a bytecode-generation rewrite.

## Purpose

DelosDB currently keeps Derby's ASM-backed execution-bytecode generator as the
transitional production backend during Compiler Phase 1. The JDK 25 Class-File API is used here only as a verifier
around compiled DelosDB runtime class files.

The verifier gives DelosDB a JDK-owned bytecode inspection lane before any future
migration away from ASM is considered.

## Scope

The task is:

```text
./gradlew delosJdk25ClassFileBytecodeVerifier
```

It compiles the normal Derby-compatible runtime modules and then invokes:

```text
org.apache.derbyBuild.DelosJdk25ClassFileVerifier
```

The verifier parses `.class` files with:

```text
java.lang.classfile.ClassFile
```

and checks that the compiled runtime outputs match the configured DelosDB Java
baseline.

For JDK 25, the expected class-file major version is:

```text
69
```

## Runtime roots

The verifier covers the compiled runtime class roots for:

```text
org.apache.derby.commons
io.github.ggeorg.delosdb.runtime.api
org.apache.derby.engine
org.apache.derby.client
org.apache.derby.tools
org.apache.derby.server
org.apache.derby.optionaltools
org.apache.derby.runner
```

## Non-goals

No ASM removal. Compiler Phase 1 records the baseline before any backend change.

No replacement of Derby's bytecode-generation backend.

No generated SQL execution behavior change.

No Derby optimizer change.

No storage behavior change.

No heap/raw-store behavior change.

No DRDA behavior change.

No S0 wiring yet.

## Why Class-File API first

The standard `java.lang.classfile` API evolves with the JDK class-file format.
That makes it a good guardrail for DelosDB's JDK 25 runtime baseline and a safer
first step than replacing ASM generation immediately.

## Relationship to ASM

ASM remains the production generator. It is transitional rather than a final v1 dependency.

The verifier is a second opinion over compiled output. It does not emit classes,
transform classes, or alter class loading.

Compiler Phase 1 now adds a focused runtime fixture that generates a deterministic
class through the existing JavaFactory/ClassBuilder/MethodBuilder contract, parses it
with the same JDK API, loads it, executes it, and records generation, allocation,
class-size, loading, and steady-execution evidence.

## Report

The task writes:

```text
build/reports/delosdb/jdk25-classfile-bytecode-verifier.txt
```

The report includes:

```text
Verifier API: java.lang.classfile.ClassFile
Expected class-file major
Runtime roots
Total class files
Parsed class files
Per-class major/minor rows
PASS/FAIL status
```

## Safety rules

The verifier must remain a verification lane until it has proven stable.

It must not be wired into S0 in this slice.

It must not weaken ASM generation checks.

It must not change Java behavior.

It must not create a new dependency.

It must not use internal JDK APIs.

## Generated-class baseline

The focused task is:

```text
./gradlew :delosdb-tests:runDelosGeneratedClassAsmBaselineTest
```

It writes:

```text
build/reports/delosdb/compiler/asm-generated-class-baseline.txt
```

This fixture does not introduce another generation abstraction. It exercises the
existing Derby generation contract and freezes the evidence required for the later
Class-File API differential backend.
