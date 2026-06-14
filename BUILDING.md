# Building DelosDB

DelosDB is built with Gradle.

Use the Gradle wrapper from the repository root:

```bash
./gradlew build
```

Common verification commands:

```bash
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
./gradlew fullVerification
```

The old inherited Derby Ant build is not supported in DelosDB. Current build and verification notes live under `docs/BUILDING.md`.
