# JDK 25 Class-File API bytecode verifier

This document records the first DelosDB JDK 25 bytecode-modernization lane.

This is a verifier artifact, not a bytecode-generation rewrite.

## Purpose

DelosDB currently keeps Derby's ASM-backed execution-bytecode generator as the
production backend. The JDK 25 Class-File API is used here only as a verifier
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

No ASM removal.

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

ASM remains the production generator.

The verifier is a second opinion over compiled output. It does not emit classes,
transform classes, or alter class loading.

Future work may add a small runtime fixture that captures an ASM-generated Derby
execution class and verifies that generated class with the same JDK API. That is
not part of this slice.

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
