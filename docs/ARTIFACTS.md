# DelosDB Artifacts

This document records the current runtime artifacts produced by the Gradle-only build and the planned project names for the future multi-project split.

The current build has extracted compile and runtime jar ownership from the repository root into Gradle subprojects. `delosdb-commons`, `delosdb-engine`, `delosdb-client`, `delosdb-tools`, `delosdb-runner`, `delosdb-optionaltools`, and `delosdb-server` now own their compile lifecycles and their main runtime jars. The root build coordinates shared resource processing, message splitting, build-tool generation, product-level verification, and the remaining OSGi stub jar assembly. The table below is the authoritative inventory for the next extraction steps.

| Planned Gradle project | Current JPMS module | Current source root | Current jar | Public runtime artifact | Current compile task | Extraction order | Risk |
|---|---|---|---|---:|---|---:|---|
| `delosdb-osgi-stub` | `org.osgi.framework` | `java/stubs/felix` | `osgi-framework-stub.jar` | No | `compileOsgiStubs` | 0 | low |
| `delosdb-commons` | `org.apache.derby.commons` | `java/org.apache.derby.commons` | `derbyshared.jar` | Yes | `compileDerbyCommons` | 1 | low |
| `delosdb-client` | `org.apache.derby.client` | `java/org.apache.derby.client` | `derbyclient.jar` | Yes | `compileDerbyClient` | 2 | low-medium |
| `delosdb-tools` | `org.apache.derby.tools` | `java/org.apache.derby.tools` | `derbytools.jar` | Yes | `compileDerbyTools` | 3 | medium |
| `delosdb-runner` | `org.apache.derby.runner` | `java/org.apache.derby.runner` | `derbyrun.jar` | Yes | `compileDerbyRunner` | 4 | low-medium |
| `delosdb-optionaltools` | `org.apache.derby.optionaltools` | `java/org.apache.derby.optionaltools` | `derbyoptionaltools.jar` | Yes | `compileDerbyOptionalTools` | 5 | medium |
| `delosdb-server` | `org.apache.derby.server` | `java/org.apache.derby.server` | `derbynet.jar` | Yes | `compileDerbyServer` | 6 | medium-high |
| `delosdb-engine` | `org.apache.derby.engine` | `java/org.apache.derby.engine` | `derby.jar` | Yes | `compileDerbyEngine` | 7 | high |

## Current dependency shape

```text
osgi-framework-stub

commons

engine
├── osgi-framework-stub
└── commons

client
└── commons

tools
├── commons
├── engine
└── client

server
├── commons
├── engine
└── tools

optionaltools
├── commons
├── engine
└── tools

runner
├── commons
├── tools
└── server
```

## Generated source and resource ownership

| Artifact | Generated inputs/resources |
|---|---|
| `delosdb-commons` / `derbyshared.jar` | `generateSanityState` |
| `delosdb-engine` / `derby.jar` | `generateSqlParser`, `generateClassSizeCatalog`, `processDerbyResources`, `splitEngineMessages` |
| `delosdb-client` / `derbyclient.jar` | `processDerbyResources`, client-side split messages from `splitEngineMessages` |
| `delosdb-tools` / `derbytools.jar` | `generateIjParsers`, `processDerbyResources` |
| `delosdb-server` / `derbynet.jar` | `processDerbyResources` |
| `delosdb-optionaltools` / `derbyoptionaltools.jar` | `processDerbyResources` |
| `delosdb-runner` / `derbyrun.jar` | no generated sources currently |
| `delosdb-osgi-stub` / `osgi-framework-stub.jar` | no generated sources currently |

## Verification commands

```bash
./gradlew printArtifactInventory
./gradlew verifyArtifactInventory
./gradlew verifyReleaseArtifacts
./gradlew build
```

`verifyArtifactInventory` checks that this inventory remains aligned with the current Gradle build model before we start moving source roots into real subprojects.


## Extraction status

The extracted Gradle subprojects currently own compilation for:

- `delosdb-commons`
- `delosdb-client`
- `delosdb-tools`
- `delosdb-runner`
- `delosdb-optionaltools`
- `delosdb-server`
- `delosdb-engine`

`delosdb-server` compiles the inherited `org.apache.derby.server` JPMS module from `java/org.apache.derby.server`, writes its class output under `delosdb-server/build/classes/modules/org.apache.derby.server`, and owns `derbynet.jar` assembly with direct subproject ownership.

`delosdb-optionaltools` compiles the inherited `org.apache.derby.optionaltools` JPMS module from `java/org.apache.derby.optionaltools` and writes its class output under `delosdb-optionaltools/build/classes/modules/org.apache.derby.optionaltools`.

`delosdb-client` is the second extracted Gradle subproject. It compiles the inherited `org.apache.derby.client` JPMS module from `java/org.apache.derby.client` and writes its class output under `delosdb-client/build/classes/modules/org.apache.derby.client`.

`delosdb-tools` is the third extracted Gradle subproject. It owns `generateIjParsers`, compiles the inherited `org.apache.derby.tools` JPMS module from `java/org.apache.derby.tools` into `delosdb-tools/build/classes/modules/org.apache.derby.tools`, and assembles `build/libs/derbytools.jar`.

`delosdb-runner` is the fourth extracted Gradle subproject. It compiles the inherited `org.apache.derby.runner` JPMS module from `java/org.apache.derby.runner` into `delosdb-runner/build/classes/modules/org.apache.derby.runner`, and assembles `build/libs/derbyrun.jar`.

The root build consumes these outputs for downstream module compilation and product-level verification. `delosdb-commons` owns `derbyshared.jar`, `delosdb-engine` owns `derby.jar`, `delosdb-client` owns `derbyclient.jar`, `delosdb-tools` owns `derbytools.jar`, `delosdb-runner` owns `derbyrun.jar`, `delosdb-optionaltools` owns `derbyoptionaltools.jar`, and `delosdb-server` owns `derbynet.jar`; the root still coordinates the remaining OSGi stub jar. Source files have not been moved yet; the current split extracts build and artifact ownership incrementally.

Verification command:

```bash
./gradlew :delosdb-commons:compileDerbyCommons verifyExtractedCommonsProject
./gradlew :delosdb-client:compileDerbyClient :delosdb-client:derbyClientJar verifyExtractedClientProject
./gradlew :delosdb-tools:compileDerbyTools :delosdb-tools:derbyToolsJar verifyExtractedToolsProject
./gradlew :delosdb-runner:compileDerbyRunner :delosdb-runner:derbyRunJar verifyExtractedRunnerProject
./gradlew :delosdb-engine:compileDerbyEngine verifyExtractedEngineProject
./gradlew :delosdb-server:compileDerbyServer :delosdb-server:derbyNetJar verifyExtractedServerProject
```

`delosdb-engine` compiles the inherited `org.apache.derby.engine` JPMS module from `java/org.apache.derby.engine` into `delosdb-engine/build/classes/modules/org.apache.derby.engine`. It now owns SQL parser generation, the class-size catalog compile step, and `derby.jar` assembly; the root build still coordinates shared resources, split messages, and product-level verification.

## Jar ownership status

| Jar | Current owner |
|---|---|
| `derbyshared.jar` | `:delosdb-commons` |
| `derbyclient.jar` | `:delosdb-client` |
| `derbytools.jar` | `:delosdb-tools` |
| `derbyrun.jar` | `:delosdb-runner` |
| `derbyoptionaltools.jar` | `:delosdb-optionaltools` |
| `derbynet.jar` | `:delosdb-server` |
| `derby.jar` | `:delosdb-engine` |
| `osgi-framework-stub.jar` | root project, pending extraction decision |

## Release metadata ownership

All main runtime jar subprojects consume the root build's shared DelosDB release metadata helpers. This keeps legal attribution files and manifest identity metadata consistent while artifact ownership continues to move out of the root project.
