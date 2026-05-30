# DelosDB Artifacts

This document records the current runtime artifacts produced by the Gradle-only build and the planned project names for the future multi-project split.

The current build is being extracted incrementally from the repository root. `delosdb-commons`, `delosdb-engine`, `delosdb-client`, `delosdb-tools`, `delosdb-runner`, `delosdb-optionaltools`, and `delosdb-server` now own their compile lifecycles; the root build still owns shared resource processing and jar assembly. The table below is the authoritative inventory for the next extraction steps.

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

`delosdb-server` compiles the inherited `org.apache.derby.server` JPMS module from `java/org.apache.derby.server` and writes its class output under `delosdb-server/build/classes/modules/org.apache.derby.server`.

`delosdb-optionaltools` compiles the inherited `org.apache.derby.optionaltools` JPMS module from `java/org.apache.derby.optionaltools` and writes its class output under `delosdb-optionaltools/build/classes/modules/org.apache.derby.optionaltools`.

`delosdb-client` is the second extracted Gradle subproject. It compiles the inherited `org.apache.derby.client` JPMS module from `java/org.apache.derby.client` and writes its class output under `delosdb-client/build/classes/modules/org.apache.derby.client`.

`delosdb-tools` is the third extracted Gradle subproject. It owns `generateIjParsers` and compiles the inherited `org.apache.derby.tools` JPMS module from `java/org.apache.derby.tools` into `delosdb-tools/build/classes/modules/org.apache.derby.tools`.

`delosdb-runner` is the fourth extracted Gradle subproject. It compiles the inherited `org.apache.derby.runner` JPMS module from `java/org.apache.derby.runner` into `delosdb-runner/build/classes/modules/org.apache.derby.runner`.

The root build consumes both outputs for downstream module compilation and jar assembly. Source files have not been moved yet; the current split extracts build ownership first.

Verification command:

```bash
./gradlew :delosdb-commons:compileDerbyCommons verifyExtractedCommonsProject
./gradlew :delosdb-client:compileDerbyClient verifyExtractedClientProject
./gradlew :delosdb-tools:compileDerbyTools verifyExtractedToolsProject
./gradlew :delosdb-runner:compileDerbyRunner verifyExtractedRunnerProject
./gradlew :delosdb-engine:compileDerbyEngine verifyExtractedEngineProject
./gradlew :delosdb-server:compileDerbyServer verifyExtractedServerProject
```

`delosdb-engine` compiles the inherited `org.apache.derby.engine` JPMS module from `java/org.apache.derby.engine` into `delosdb-engine/build/classes/modules/org.apache.derby.engine`. It now owns SQL parser generation and the class-size catalog compile step; the root build still coordinates shared resources, split messages, and jar assembly.
