# DelosDB client message resources ordering overlay

## Purpose

Fixes the inherited Derby lang-suite client failure:

```text
java.util.MissingResourceException: Can't find bundle for base name org.apache.derby.loc.client.clientmessages, locale en_US
```

The failure is not caused by the SPI quarantine work. The engine runtime classpath is already clean. The failure is caused by `derbyclient.jar` being allowed to package before the root `splitEngineMessages` task has copied/generated `org/apache/derby/loc/client/clientmessages*.properties` into the client module output directory.

## Change

Updates:

```text
delosdb-client/build.gradle
```

The client jar tasks now depend on:

```text
rootProject.tasks.named('splitEngineMessages')
```

and verify that both generated client message bundles are present in `derbyclient.jar`:

```text
org/apache/derby/loc/client/clientmessages.properties
org/apache/derby/loc/client/clientmessages_en.properties
```

## Apply

From the repository root:

```sh
unzip -oq ~/Downloads/delosdb-client-messages-order-overlay.zip
```

No cleanup script is needed. This overlay only replaces `delosdb-client/build.gradle`.

## Verify

Run the full verification again:

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
Package generated Derby client message resources deterministically
```
