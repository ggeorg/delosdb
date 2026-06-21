# Legacy Derby Store Phase B Closeout

Last updated: 2026-06-20

Phase B is the closeout lane for the real inherited Derby store module boundary.
It is intentionally compatibility-first: the inherited heap/raw/access/WAL store
is compiled by `delosdb-storage-derby`, while the default Derby-compatible runtime
packaging still makes the store classes available from `derby.jar`.

## Closed result

```text
delosdb-storage-derby
  org.apache.derby.iapi.store.*
  org.apache.derby.impl.store.*
```

The closeout proves these facts together:

- `delosdb-storage-derby` owns real store source compilation.
- `delosdb-engine` no longer compiles relocated store sources directly.
- `delosdb-engine` consumes compiled storage output.
- `derby.jar` still packages inherited Derby store runtime classes for existing
  jar-based users.
- direct relocated-store `org.apache.derby.iapi.types` imports are closed.
- remaining fully-qualified inherited type references in relocated store code are
  closed.
- the full Derby language suite remains part of the B8 closeout gate.

## Proof gates

Use the consolidated B9 gate before starting Phase C:

```bash
./scripts/cleanup-overlay-b9-stale-files.sh
./gradlew verifyLegacyDerbyStoreB9StaticAnalysis
./gradlew verifyLegacyDerbyStorePhaseBCloseout
./gradlew verifyLegacyDerbyStoreB9Consolidation
```

The underlying closeout gates remain useful for focused diagnosis:

```bash
./gradlew verifyLegacyDerbyStoreB6oCloseout
./gradlew verifyLegacyDerbyStoreB7RuntimePackaging
./gradlew verifyLegacyDerbyStoreB8Closeout
```

## B9 static-analysis scope

B9 does not delete production Java code. Inherited Derby code is removed only
behind focused behavior proofs. The B9 static-analysis gate instead checks the
safe cleanup signals that matter before Phase C:

- stale exact `B6 readiness is NOT YET` proof text is gone;
- Gradle task registrations are unique;
- top-level Gradle helper definitions are unique;
- legacy Derby store source ownership is not duplicated across engine, kernel,
  and storage modules;
- local stale artifacts such as `derby.log`, `.DS_Store`, `*.orig`, `*.rej`, and
  `__MACOSX` have been cleaned or are explicitly reported.

The cleanup script intentionally does not remove `.git/`, `.gradle/`, `.idea/`,
`build/`, or source files.

## Phase C entry rule

Phase C may start only after this is green:

```bash
./gradlew verifyLegacyDerbyStoreB9Consolidation
```

Phase C should then start with the common operational storage facade. It should
not create a third parallel SPI family and should preserve Derby locking,
isolation, and runtime-packaging semantics.
