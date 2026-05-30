# Building DelosDB

DelosDB uses Gradle as its supported build system.

The build is now Gradle-native for the active developer workflow: source generation, parser generation, module compilation, resource generation, jar assembly, smoke testing, jar metadata verification, and CI are all driven by Gradle.

## Requirements

- JDK 21 or newer
- The checked-in Gradle Wrapper

## Main commands

```bash
./gradlew build
./gradlew smoke
./gradlew smokeFromJars
./gradlew sysinfo
./gradlew sysinfoFromJars
./gradlew jars
./gradlew verifyJars
./gradlew verifyReleaseArtifacts
./gradlew verifyReleaseDistribution
./gradlew dist
./gradlew publishToMavenLocal
./gradlew verifyMavenPublications
./gradlew verifyMavenLocalConsumer
./gradlew printArtifactInventory
./gradlew verifyArtifactInventory
./gradlew verifyExtractedOptionalToolsProject
./gradlew verifyExtractedServerProject
```

## What `./gradlew build` does

The main build lifecycle now performs these steps:

1. Generate `SanityState.java`.
2. Run JavaCC for the SQL parser.
3. Run JavaCC for the `ij` parser.
4. Compile the core JPMS modules.
5. Compile the small inherited build-time generators still needed by the source tree.
6. Generate split engine/client message bundles.
7. Generate `ClassSizeCatalogImpl`.
8. Generate product `info.properties` resources.
9. Assemble jars into `build/libs/`.
10. Verify that all expected runtime jars were assembled.
11. Verify release metadata, manifest attributes, and legal attribution files inside each jar.
12. Verify that public build docs expose only the Gradle workflow.
13. Verify that the artifact/module inventory docs match the current Gradle build model.
14. Run the embedded JDBC smoke test from compiled classes.
15. Run the embedded JDBC smoke test from assembled jars.
16. Build and verify the binary distribution archives.

## Current jar outputs

```text
build/libs/derby.jar
build/libs/derbyclient.jar
build/libs/derbynet.jar
build/libs/derbyoptionaltools.jar
build/libs/derbyrun.jar
build/libs/derbyshared.jar
build/libs/derbytools.jar
build/libs/osgi-framework-stub.jar
```

## Release artifact metadata

Each runtime jar is expected to contain:

```text
META-INF/MANIFEST.MF
META-INF/LICENSE
META-INF/NOTICE
META-INF/NOTICE-FORK.md
```

The manifest includes DelosDB identity metadata, compatibility metadata for the Apache Derby 10.17.1.0 base, and the Git build revision when available.

Use this command to verify the release artifact shape:

```bash
./gradlew verifyReleaseArtifacts
./gradlew verifyReleaseDistribution
./gradlew dist
./gradlew printArtifactInventory
./gradlew verifyArtifactInventory
./gradlew verifyExtractedOptionalToolsProject
./gradlew verifyExtractedServerProject
```


## Artifact inventory

```bash
./gradlew printArtifactInventory
./gradlew verifyArtifactInventory
./gradlew verifyExtractedOptionalToolsProject
./gradlew verifyExtractedServerProject
```

`printArtifactInventory` shows the planned Gradle subproject name, current JPMS module, source root, compile task, jar task, jar name, dependencies, generated inputs, and extraction risk for each artifact.

`verifyArtifactInventory` keeps `docs/ARTIFACTS.md`, `docs/MODULE-SPLIT-PLAN.md`, and `docs/BUILD-INVENTORY.md` aligned with the current Gradle build model. This is the guardrail before the real multi-project split begins.

## Smoke tests

```bash
./gradlew smoke
./gradlew smokeFromJars
```

`smoke` runs `dev/smoke.sql` through `ij` using the Gradle-built class directories.

`smokeFromJars` runs the same script from the assembled jars in `build/libs/`. This is important because it verifies the artifact shape, not only the compiler output tree.

## System info

```bash
./gradlew sysinfo
./gradlew sysinfoFromJars
```

`sysinfo` runs the inherited Derby `sysinfo` entry point from the Gradle-built class directories.

`sysinfoFromJars` runs it from the assembled jars in `build/libs/`.

## Notes

This is still a conservative baseline. Package names remain compatible with Apache Derby 10.17.1.0 while the project identity, build workflow, documentation, and release path move toward DelosDB.


## Extracted subprojects

`delosdb-commons` is the first extracted Gradle subproject. It compiles the inherited `org.apache.derby.commons` JPMS module from `java/org.apache.derby.commons` and writes its class output under `delosdb-commons/build/classes/modules/org.apache.derby.commons`.

`delosdb-client` is the second extracted Gradle subproject. It compiles the inherited `org.apache.derby.client` JPMS module from `java/org.apache.derby.client` and writes its class output under `delosdb-client/build/classes/modules/org.apache.derby.client`.

The root build still owns shared resource generation, message splitting, build-tool generation, and product-level verification. The extracted subprojects now own compilation and runtime jar assembly for the DelosDB runtime artifacts, including the OSGi framework stub. Source files have not been moved yet; the current split extracts build and artifact ownership incrementally.

Verification command:

```bash
./gradlew :delosdb-commons:compileDerbyCommons verifyExtractedCommonsProject
./gradlew :delosdb-client:compileDerbyClient
./gradlew :delosdb-client:derbyClientJar
./gradlew :delosdb-tools:compileDerbyTools
./gradlew :delosdb-runner:compileDerbyRunner verifyExtractedClientProject
./gradlew :delosdb-engine:compileDerbyEngine verifyExtractedEngineProject
./gradlew :delosdb-server:compileDerbyServer :delosdb-server:derbyNetJar verifyExtractedServerProject
./gradlew verifyExtractedToolsProject
./gradlew verifyExtractedRunnerProject
```

`delosdb-server` is the sixth extracted Gradle subproject. It compiles the inherited `org.apache.derby.server` JPMS module from `java/org.apache.derby.server`, writes its class output under `delosdb-server/build/classes/modules/org.apache.derby.server`, and owns `derbynet.jar` assembly.

## Extracted engine subproject

`delosdb-engine` is the seventh extracted Gradle subproject. It owns SQL parser generation, compilation of the inherited `org.apache.derby.engine` JPMS module, class-size catalog compilation, and `derby.jar` assembly. The root build still owns shared resource processing and message splitting until those cross-module concerns are extracted in later cleanup patches.

## Shared release metadata helpers

The root build defines the shared legal-file set and runtime jar manifest attributes. Extracted jar-owning subprojects consume these helpers instead of duplicating release metadata locally. The `verifyCentralizedReleaseMetadata` task guards this convention.


## Shared Gradle conventions

The root build owns shared compiler conventions for all extracted subprojects. Subprojects should call `rootProject.ext.delosdbConfigureJavaCompile(...)` instead of declaring local JavaCompile defaults. This keeps the Java release, encoding, warning policy, classpath clearing, and JPMS module-path handling consistent.


## Binary distribution

Use the distribution task to produce release-candidate binary archives:

```bash
./gradlew dist
```

Outputs:

```text
build/distributions/delosdb-0.1.0-dev-bin.zip
build/distributions/delosdb-0.1.0-dev-bin.tar.gz
```

The archive layout is:

```text
delosdb-0.1.0-dev/
├── bin/
│   ├── delosdb
│   └── delosdb.bat
├── docs/
├── examples/
│   └── smoke.sql
├── lib/
│   ├── derby.jar
│   ├── derbyclient.jar
│   ├── derbynet.jar
│   ├── derbyoptionaltools.jar
│   ├── derbyrun.jar
│   ├── derbyshared.jar
│   ├── derbytools.jar
│   └── osgi-framework-stub.jar
├── LICENSE
├── NOTICE
├── NOTICE-FORK.md
└── README.md
```

`verifyReleaseDistribution` checks that the distribution archives exist and contain the expected legal files, documentation, launcher scripts, example SQL, and runtime jars.


## Local Maven publication

DelosDB can publish its extracted runtime subprojects to Maven Local. This is the current publication baseline and is not yet a Maven Central release workflow.

```bash
./gradlew publishToMavenLocal
./gradlew verifyMavenPublications
./gradlew verifyMavenLocalConsumer
```

The publication task writes DelosDB-branded artifacts under the local Maven repository:

```text
~/.m2/repository/io/github/ggeorg/delosdb/
```

The current published artifact IDs are:

```text
delosdb-commons
delosdb-engine
delosdb-client
delosdb-tools
delosdb-runner
delosdb-server
delosdb-optionaltools
```

`verifyMavenPublications` publishes to Maven Local and checks that each publication has a non-empty jar, a non-empty POM, DelosDB coordinates, license metadata, SCM metadata, and the expected DelosDB inter-artifact dependencies.

The OSGi framework stub is still a build/runtime support jar for the inherited engine compile path and is not part of the public Maven publication baseline.

## Maven Local consumer verification

Run this after `publishToMavenLocal` or `verifyMavenPublications` to prove that an external Gradle project can consume DelosDB from Maven Local:

```bash
./gradlew verifyMavenLocalConsumer
```
