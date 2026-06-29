# DelosDB splitEngineMessages cycle fix overlay

This overlay fixes the circular Gradle dependency introduced when `derbyclient.jar`
was made to depend on generated client message bundles.

## Problem

The previous client message overlay correctly made `:delosdb-client:jar` and
`:delosdb-client:derbyClientJar` depend on root `:splitEngineMessages`, but
root `splitEngineMessages` still depended on `processDerbyResources`.

That created this cycle:

```text
processDerbyResources
  -> runtime compile tasks
  -> tools/server/runner compile
  -> delosdb-client:jar
  -> splitEngineMessages
  -> processDerbyResources
```

## Fix

`splitEngineMessages` now depends only on `compileDerbyBuildTools`.

That is sufficient because the task:

- reads `delosdb-engine/src/main/java/org/apache/derby/loc/messages.xml`
- creates its own output directories
- runs Derby build tools directly
- writes generated engine/client/locale message resources

It does not need `processDerbyResources`.

## Apply

From the repository root:

```sh
unzip -oq ~/Downloads/delosdb-split-messages-cycle-fix-overlay.zip
```

No cleanup script is needed.

## Verify

```sh
./gradlew clean fullVerification :delosdb-storage-mvcc:check
```

Optional focused jar check:

```sh
./gradlew clean :delosdb-client:derbyClientJar
jar tf build/libs/derbyclient.jar | grep 'org/apache/derby/loc/client/clientmessages'
```

Expected entries:

```text
org/apache/derby/loc/client/clientmessages.properties
org/apache/derby/loc/client/clientmessages_en.properties
```

## Commit comment

```text
Fix generated client message task ordering cycle
```
