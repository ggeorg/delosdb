# DelosDB Artifacts

This document records the current runtime artifacts produced by the Gradle-only build and the planned project names for the future multi-project split.

The current build is still implemented from the repository root. The table below is the authoritative inventory for the next extraction steps.

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
