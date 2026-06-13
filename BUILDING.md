# Building DelosDB

DelosDB is built with Gradle. Ant is not a supported build path.

The main contributor commands are:

```bash
./gradlew build
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
./gradlew fullVerification
```

For detailed build notes, see `docs/BUILDING.md`.

## Verification levels

Use `./gradlew build` for the normal compile/package check.

Use `./gradlew derbyRuntimeSmoke` to run the DelosDB runtime smoke suite, including the extension-provider smokes.

Use `./gradlew :delosdb-tests:runDerbyLangSuite` to run the active inherited Derby language/JDBC compatibility suite.

Use `./gradlew fullVerification` before merging larger changes.
