# Phase 6 build, cleanup, and module closeout

## Status

Phase 6 is ready to close when the focused JDK 25 verification and normal gates
for this slice are green. This document records the resulting build ownership;
it does not replace executable verification.

## Objective evidence

The module dependency report must remain at:

```text
23 modules
0 missing declared production dependencies
0 unresolved project imports
0 duplicate resource paths
0 package-owner collisions
0 cross-module output-directory backdoors
```

The remaining declarations without ordinary source references are intentional:

```text
delosdb-pptesting
    testImplementation -> delosdb-derby-store-api
    inherited StringDataValue signatures expose Store* interfaces

delosdb-engine
    derbyRuntimePatchArtifacts -> delosdb-storage-derby
    inherited org/apache/derby/impl/store/** classes are assembled into derby.jar

delosdb-optionaltools
    enginePatchModule -> delosdb-storage-api
    inherited DataValueDescriptor signatures expose StoreOrderable/StoreDataValue
```

## Generated-resource ownership

Generated resources now have one explicit owner and one output location.

| Resource | Producer | Output owner | Artifact consumer |
| --- | --- | --- | --- |
| Product `info.properties` | shared `gradle/delosdb-product-info.gradle` convention, configured by each module | publishing module | commons, engine, client, tools, server, optional-tools JAR |
| Embedded JDBC driver service | static engine resource | `delosdb-engine` | `delosdb-engine.jar`, `derby.jar` |
| Client JDBC driver service | static client resource | `delosdb-client` | `derbyclient.jar` |
| Engine/client message bundles | root `splitEngineMessages` cross-module generator | dedicated generated-resource directories in engine/client | engine and client `processResources` |
| Locale message bundles | root `splitEngineMessages` | root locale output | locale/distribution tasks |
| ODBC metadata | root `generateOdbcMetadata` build-tool execution | dedicated generated-resource directory in engine | engine `processResources` |
| Catalog metadata | ordinary source resource under engine source ownership | engine `processResources` | engine artifacts |
| SQL parser | `delosdb-engine:generateSqlParser` | engine generated sources | engine compilation |
| ij parsers | `delosdb-tools:generateIjParsers` | tools generated sources | tools compilation |
| `ClassSizeCatalogImpl` | root `generateClassSizeCatalog` plus runtime-api compile task | isolated runtime-api generated class output | Derby-compatible runtime-api JAR |

The previous shared class/resource staging workaround is removed. Compilation
writes classes to module class directories. `processResources` writes resources
to the standard resource output. JAR tasks consume the source-set outputs rather
than relying on compilation/resource ordering inside one mutable directory.

The six identical product-information implementations are consolidated in one
small convention backed by Gradle's typed `WriteProperties` task. Each module still declares only its artifact-specific task
name, resource path, technology name, and product filename.

## Artifact ownership

The root runtime-artifact model points to canonical `jar` tasks whenever the
normal Gradle JAR and the Derby-compatible distribution JAR are identical.
Duplicate JAR tasks and forwarding aliases were removed for:

```text
annotations
SPI
storage I/O
OSGi stub
commons
storage API
Derby store API
MVCC provider
client
tools
server
optional tools
runner
```

The canonical JAR writes directly to root `build/libs` for distribution-owned
artifacts. Publication uses the same canonical task, so publication no longer
expects archive properties from lifecycle-only aliases.

Separate JAR tasks remain only where artifact contents genuinely differ:

```text
delosdb-engine:jar
    ordinary engine module artifact

delosdb-engine:derbyJar
    Derby-compatible engine with patched store API/implementation content

delosdb-runtime-api:jar
    ordinary project-dependency artifact

delosdb-runtime-api:delosDbRuntimeApiJar
    Derby-compatible artifact including generated ClassSizeCatalogImpl
```

`delosdb-storage-derby` and `delosdb-storage-bridge` keep their standard
module-local JARs because those exact outgoing artifacts are consumed as
`derbyRuntimePatchElements`. Their duplicate root-copy JAR tasks were removed.

The engine and client JAR tasks verify their boot-critical resources before
completion. The engine check covers the embedded driver service,
`modules.properties`, and engine product information. The client check covers
the client driver service, product information, and generated messages.

## Task and report cleanup

Removed build surface includes:

```text
unused root module inventory and metadata exports
41 root MVCC forwarding tasks
five previously unconsumed module compile aliases
one additional unconsumed storage-I/O compile alias
15 duplicate or forwarding JAR tasks
one obsolete engine service-copy alias
three duplicate generated-resource wrapper/copy tasks
two unconsumed historical test aggregators
28 completed phase-specific static-analysis tasks
28 completed phase-specific proof/state manifests
stale one-shot cleanup scripts
```

Completed proof manifests were removed only when their task had no aggregate,
CI, documentation, packaging, or runtime consumer and the underlying behavior is
covered by maintained tests or current design documentation. Stable S0 checks,
current compatibility classifications, JDK/JFR/optimizer/null-key proofs, and
opt-in external validation tooling remain.

The retained reusable script is:

```text
scripts/module-dependency-tree.py
```

It is consumed by `delosModuleDependencyReport`.

## Configuration-cache and Gradle ownership

Configuration-time Git revision lookup uses a `ValueSource`, preserving the
short revision and `local` fallback while making the revision a tracked input.

Execution-time build-tool tasks use captured file/provider inputs and injected
Gradle services. The engine and tools parser generators, root message/ODBC/class
size generators, server/optional-tools patch-module compilation, runtime artifact
assembly, and stable S0 static checks do not reach back into a Gradle script
object during task execution.

`WriteProperties` declares each generated file as a typed task output and writes
reproducible UTF-8 content without a timestamp.

Configuration-cache verification is required for:

```text
canonical runtime JAR assembly
s0CloseoutVerification
```

Gradle deprecation warnings must be reviewed with `--warning-mode all`. This
phase does not hide unsupported tasks with `notCompatibleWithConfigurationCache`
and does not add compatibility shims merely to silence warnings.

## Module-consolidation assessment

No module merge is justified.

| Module | Boundary retained |
| --- | --- |
| `delosdb-osgi-stub` | isolated compile-time OSGi compatibility surface |
| `delosdb-commons` | shared Derby utilities and published artifact |
| `delosdb-runtime-api` | inherited runtime contracts and generated catalog artifact split |
| `delosdb-annotations` | independently owned annotations artifact |
| `delosdb-spi` | public DelosDB SPI artifact |
| `delosdb-storage-io` | provider-neutral storage I/O artifact |
| `delosdb-storage-api` | public storage provider contracts and patch boundary |
| `delosdb-derby-store-api` | inherited Derby store signatures and patch boundary |
| `delosdb-storage-mvcc` | independent provider implementation and ServiceLoader artifact |
| `delosdb-storage-derby` | inherited Derby implementation patch artifact |
| `delosdb-storage-bridge` | Derby-facing MVCC adapter and patch artifact |
| `delosdb-engine` | Derby-compatible embedded engine artifact owner |
| `delosdb-client` | network client JPMS/published artifact |
| `delosdb-tools` | tools JPMS/published artifact and parser generation |
| `delosdb-runner` | executable launcher artifact |
| `delosdb-optionaltools` | optional deployment and patch-module compile boundary |
| `delosdb-server` | network server JPMS/published artifact |
| `delosdb-tests` | inherited integration-test ownership and test fixture generation |
| `delosdb-pptesting` | package-private inherited test island |
| `delosdb-storeless` | isolated inherited storeless compatibility prototype |
| `delosdb-demos` | demo/distribution source ownership |
| `delosdb-locales` | locale/distribution ownership |
| `delosdb-buildtools` | isolated build-time generators |

## Production dead-code conclusion

The final high-confidence sweep found no production class safe to remove without
changing a supported API, ServiceLoader path, durable behavior, reflection path,
or maintained test. The former candidates `MvccDurableIndexStore` and
`MvccVacuum` remain because focused durable-index and vacuum tests use them.

This closeout intentionally removes only proven dead build/report surface. It
does not turn Phase 6 into a production architecture refactor.

## Exit criteria

Phase 6 closes after the verification commands are green with:

```text
no confirmed dead production code
no unexplained dependency candidates
no duplicate generated-resource owner
no duplicate identical JAR implementation
no unjustified custom build-wiring edge
no stale one-shot cleanup script
no obsolete unconsumed task alias
no consumerless committed proof/state report
no package-owner collision
no output-directory classpath backdoor
configuration-cache reuse for the verified build surfaces
all 23 module boundaries justified
normal gates green
```
