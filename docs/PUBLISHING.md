# DelosDB Publishing

DelosDB currently supports a **local Maven publication baseline**. This is used to validate artifact identity, POM metadata, and inter-artifact dependencies before any remote repository or Maven Central work begins.

## Local Maven publication

From the repository root:

```bash
./gradlew clean build
./gradlew publishToMavenLocal
./gradlew verifyMavenPublications
./gradlew verifyMavenLocalConsumer
```

The local repository location is:

```text
~/.m2/repository/io/github/ggeorg/delosdb/
```

## Published coordinates

```text
io.github.ggeorg.delosdb:delosdb-commons:0.1.0-dev
io.github.ggeorg.delosdb:delosdb-engine:0.1.0-dev
io.github.ggeorg.delosdb:delosdb-client:0.1.0-dev
io.github.ggeorg.delosdb:delosdb-tools:0.1.0-dev
io.github.ggeorg.delosdb:delosdb-runner:0.1.0-dev
io.github.ggeorg.delosdb:delosdb-server:0.1.0-dev
io.github.ggeorg.delosdb:delosdb-optionaltools:0.1.0-dev
```

The Maven identity is DelosDB-branded. The binary runtime jars still keep Derby-compatible file names for this phase:

```text
derby.jar
derbyclient.jar
derbynet.jar
derbytools.jar
derbyrun.jar
derbyshared.jar
derbyoptionaltools.jar
```

## Verification

`verifyMavenPublications` publishes to Maven Local and verifies:

- each expected artifact directory exists;
- each publication has a non-empty jar;
- each publication has a non-empty POM;
- each POM contains DelosDB group/artifact/version coordinates;
- each POM contains license and SCM metadata;
- each POM contains the expected DelosDB inter-artifact dependencies.



## External consumer verification

`verifyMavenLocalConsumer` generates a temporary external Gradle project under:

```text
build/consumer-tests/maven-local-embedded-smoke/
```

The generated project uses only Maven Local resolution and depends on:

```text
io.github.ggeorg.delosdb:delosdb-engine:0.1.0-dev
```

It then runs a small embedded JDBC smoke test through the published artifact. This proves that the DelosDB-branded Maven coordinates are not only published, but also consumable from a separate Gradle build.

## Not Maven Central yet

This is intentionally not a Maven Central release workflow. Before remote publication, add and verify:

- sources jars;
- javadoc jars;
- signing;
- reproducible archive metadata;
- release versioning;
- staging repository configuration;
- release notes and changelog generation.
