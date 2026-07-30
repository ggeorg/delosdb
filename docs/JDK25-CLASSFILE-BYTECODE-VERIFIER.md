# JDK 25 Class-File API bytecode verifier

This is a verifier artifact, not a bytecode-generation rewrite.

## Purpose

DelosDB uses the standard JDK 25 Class-File API both for production generated
SQL activations and for independent verification of compiled runtime classes.

```text
java.lang.classfile.ClassFile
```

ClassFileJava is the sole production generator.

External ASM removed.

The verifier parses compiled `.class` files and checks the configured JDK
class-file baseline. It does not emit classes, select the generator, transform
bytecode, or change class loading.

## Task

```text
./gradlew delosJdk25ClassFileBytecodeVerifier
```

The task invokes:

```text
org.apache.derbyBuild.DelosJdk25ClassFileVerifier
```

## Expected class-file major

For JDK 25:

```text
69
```

The expected major is derived from the configured Java release rather than
hardcoded only inside the verifier.

## Runtime roots

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

## Report

```text
build/reports/delosdb/jdk25-classfile-bytecode-verifier.txt
```

The report includes the verifier API, expected major, roots, parsed class count,
per-class version rows, and PASS/FAIL status.

## Safety rules

```text
No generated SQL execution behavior change.
No optimizer change.
No storage or MVCC change.
No JDBC or DRDA protocol change.
No internal JDK bytecode API.
No external bytecode dependency.
No S0 wiring yet.
```

The verifier remains an explicit lane because it audits all compiled runtime
classes and can be more expensive than the structural S0 closeout gates.

## Relationship to Compiler Phase 6

Compiler Phase 6 removes the retired external implementation, dependency,
module requirement, runtime artifact composition, and differential-oracle
infrastructure. The verifier remains because it supplies independent JDK-owned
class-file parsing and version validation for the sole production backend and
all runtime modules.
