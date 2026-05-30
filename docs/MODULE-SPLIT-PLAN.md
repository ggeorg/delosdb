# DelosDB Module Split Plan

The current Gradle-only build compiles the inherited source tree from the repository root. The next architectural goal is to convert this into a real Gradle multi-project build without changing runtime behavior.

The split must be incremental. Each step should keep these proof commands green:

```bash
./gradlew clean build
./gradlew sysinfoFromJars
./gradlew printArtifactInventory
./gradlew verifyArtifactInventory
```

## Target project layout

```text
delosdb/
├── delosdb-osgi-stub
├── delosdb-commons
├── delosdb-client
├── delosdb-tools
├── delosdb-runner
├── delosdb-optionaltools
├── delosdb-server
└── delosdb-engine
```

The final order is intentionally not the same as the runtime dependency graph. We extract the safest modules first and leave `delosdb-engine` last because it owns the SQL parser, class-size catalog generation, message splitting, embedded driver metadata, and the largest amount of inherited code.

## Extraction order

### 0. `delosdb-osgi-stub`

Current jar: `osgi-framework-stub.jar`  
Current module: `org.osgi.framework`  
Current source root: `java/stubs/felix`

This is internal support code. It is useful as an early extraction candidate only if we need to prove custom source roots before touching a public artifact.

### 1. `delosdb-commons`

Current jar: `derbyshared.jar`  
Current module: `org.apache.derby.commons`  
Current source root: `java/org.apache.derby.commons`

This is the first public extraction target. It has the smallest dependency surface and only one generated-source concern: `generateSanityState`.

Acceptance checks:

```bash
./gradlew clean build
./gradlew verifyReleaseArtifacts
./gradlew smokeFromJars
./gradlew sysinfoFromJars
```

### 2. `delosdb-client`

Current jar: `derbyclient.jar`  
Current module: `org.apache.derby.client`  
Current source root: `java/org.apache.derby.client`

This depends on `delosdb-commons` and receives generated service metadata and client-side message output. It is lower risk than the engine because it does not own parser generation.

### 3. `delosdb-tools`

Current jar: `derbytools.jar`  
Current module: `org.apache.derby.tools`  
Current source root: `java/org.apache.derby.tools`

This module owns `ij` parser generation through `generateIjParsers`. Its compile and jar assembly lifecycle is now extracted into `:delosdb-tools`; the root build depends directly on the subproject for coordinated product builds.

### 4. `delosdb-runner`

Current jar: `derbyrun.jar`  
Current module: `org.apache.derby.runner`  
Current source root: `java/org.apache.derby.runner`

This provides the main entry point used by `sysinfo`, `ij`, and smoke verification. Its compile and jar assembly lifecycle is now extracted into `:delosdb-runner`, including the `Main-Class` manifest entry for `org.apache.derby.run.run`.

### 5. `delosdb-optionaltools`

Current jar: `derbyoptionaltools.jar`  
Current module: `org.apache.derby.optionaltools`  
Current source root: `java/org.apache.derby.optionaltools`

This depends on commons, engine, tools, and external optional jars. Keep it after tools.

### 6. `delosdb-server`

Current jar: `derbynet.jar`  
Current module: `org.apache.derby.server`  
Current source root: `java/org.apache.derby.server`

This depends on commons, engine, tools, and servlet/external module path entries. Keep it late.

### 7. `delosdb-engine`

Current jar: `derby.jar`  
Current module: `org.apache.derby.engine`  
Current source root: `java/org.apache.derby.engine`

This is the final and highest-risk extraction. It owns SQL parser generation, class-size catalog generation, engine resources, service registration, embedded product metadata, and split message bundles.

## Rules for each extraction

1. Move one module at a time.
2. Do not rename packages during the module split.
3. Preserve current jar names until release compatibility is explicitly revisited.
4. Keep manifest and legal metadata verification green.
5. Keep smoke and sysinfo tests green from assembled jars, not only class directories.
6. Update `docs/ARTIFACTS.md` immediately when an artifact becomes a real subproject.
7. Keep `verifyArtifactInventory` green after every step.

## Current extraction target

All planned compile-owner subprojects and main runtime jar owners have now been extracted. The next cleanup target is root build orchestration: the root project should coordinate product-level verification, shared resource/message generation, build-tool generation, and the OSGi stub jar without exposing compatibility alias tasks for module compile or jar assembly.

## Extraction progress

`delosdb-commons` is the first extracted Gradle subproject. It compiles the inherited `org.apache.derby.commons` JPMS module from `java/org.apache.derby.commons` and writes its class output under `delosdb-commons/build/classes/modules/org.apache.derby.commons`.

`delosdb-client` is the second extracted Gradle subproject. It compiles the inherited `org.apache.derby.client` JPMS module from `java/org.apache.derby.client` and writes its class output under `delosdb-client/build/classes/modules/org.apache.derby.client`.

`delosdb-tools` is the third extracted Gradle subproject. It owns `generateIjParsers` and compiles the inherited `org.apache.derby.tools` JPMS module from `java/org.apache.derby.tools` into `delosdb-tools/build/classes/modules/org.apache.derby.tools`.

`delosdb-runner` is the fourth extracted Gradle subproject. It compiles the inherited `org.apache.derby.runner` JPMS module from `java/org.apache.derby.runner` into `delosdb-runner/build/classes/modules/org.apache.derby.runner`.

`delosdb-optionaltools` is the fifth extracted Gradle subproject. It compiles the inherited `org.apache.derby.optionaltools` JPMS module from `java/org.apache.derby.optionaltools` into `delosdb-optionaltools/build/classes/modules/org.apache.derby.optionaltools` and owns `derbyoptionaltools.jar` assembly.

`delosdb-server` is the sixth extracted Gradle subproject. It compiles the inherited `org.apache.derby.server` JPMS module from `java/org.apache.derby.server` into `delosdb-server/build/classes/modules/org.apache.derby.server` and owns `derbynet.jar` assembly.

The root build consumes these outputs for downstream module compilation and product-level verification. `delosdb-commons` owns `derbyshared.jar`, `delosdb-engine` owns `derby.jar`, `delosdb-client` owns `derbyclient.jar`, `delosdb-tools` owns `derbytools.jar`, `delosdb-runner` owns `derbyrun.jar`, `delosdb-optionaltools` owns `derbyoptionaltools.jar`, and `delosdb-server` owns `derbynet.jar`; the root still coordinates the remaining OSGi stub jar. Source files have not been moved yet; the current split extracts build and artifact ownership incrementally.

Verification command:

```bash
./gradlew :delosdb-commons:compileDerbyCommons verifyExtractedCommonsProject
./gradlew :delosdb-client:compileDerbyClient :delosdb-client:derbyClientJar verifyExtractedClientProject
./gradlew :delosdb-tools:compileDerbyTools verifyExtractedToolsProject
./gradlew :delosdb-runner:compileDerbyRunner verifyExtractedRunnerProject
./gradlew :delosdb-optionaltools:compileDerbyOptionalTools :delosdb-optionaltools:derbyOptionalToolsJar verifyExtractedOptionalToolsProject
./gradlew :delosdb-engine:compileDerbyEngine verifyExtractedEngineProject
./gradlew :delosdb-server:compileDerbyServer :delosdb-server:derbyNetJar verifyExtractedServerProject
```


## Current extraction status

The build ownership extraction currently includes:

1. `delosdb-commons`
2. `delosdb-client`
3. `delosdb-tools`
4. `delosdb-runner`
5. `delosdb-optionaltools`
6. `delosdb-server`

All planned compile-owner subprojects and main runtime jar owners have now been extracted. The next phase is to reduce root build orchestration, then move shared resource processing and message splitting into clearer owners.

`delosdb-engine` is the seventh extracted Gradle subproject. It owns SQL parser generation, `compileDerbyEngine`, the class-size catalog compile step, and `derby.jar` assembly for the inherited embedded engine module.
