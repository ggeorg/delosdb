# Phase 6 build and module closeout

## Status

Phase 6 implementation is complete in the repository after this closeout slice.
The phase is closed only after the focused checks and normal JDK 25 gates for the
slice are green.

This closeout covers:

```text
production-code and build-edge review
Gradle duplication and task ownership
generated-resource ownership
compatibility aliases
custom source sets and patch configurations
report/static-gate ownership
Gradle 10 deprecation cleanup in touched resource tasks
configuration-cache risk review
module-consolidation assessment
curated dead-code candidate resolution
```

No module merge is justified by the current evidence.

## Repository evidence

The source dependency report records:

```text
23 modules
0 missing declared production dependencies
0 unresolved project imports
0 duplicate resource paths
0 package-owner collisions
0 cross-module output-directory backdoors
```

The remaining dependency declarations without ordinary source references are
intentional:

```text
delosdb-pptesting
    testImplementation -> delosdb-derby-store-api
    classfile-signature dependency through StringDataValue inherited Store* types


delosdb-engine
    derbyRuntimePatchArtifacts -> delosdb-storage-derby
    inherited org/apache/derby/impl/store/** classes are assembled into derby.jar


delosdb-optionaltools
    enginePatchModule -> delosdb-storage-api
    DataValueDescriptor signatures require inherited StoreOrderable/StoreDataValue types
```

The report remains evidence for human review. It is not an automatic dependency
removal policy.

## Generated-resource ownership

### Module-owned resources

The module that publishes a product artifact owns its ordinary resources and
product information:

| Artifact owner | Generated or copied resource owner |
| --- | --- |
| `delosdb-commons` | shared `info.properties` |
| `delosdb-engine` | engine `info.properties`, JDBC driver service descriptor, ordinary engine resources |
| `delosdb-client` | client `info.properties`, client JDBC service descriptor |
| `delosdb-tools` | tools `info.properties`, ordinary tools resources |
| `delosdb-server` | network-server `info.properties`, ordinary server resources |
| `delosdb-optionaltools` | optional-tools `info.properties` |
| `delosdb-storage-derby` | Derby-store ServiceLoader resources |
| `delosdb-storage-bridge` | Derby/MVCC bridge ServiceLoader resources |
| `delosdb-storage-mvcc` | MVCC provider ServiceLoader resource |

The root `processDerbyResources` task was removed because it wrote the engine's
ordinary resources, JDBC service descriptor, and product-information file into
the same output tree already owned by `:delosdb-engine:processResources`.

The root cross-module generators now depend directly on the engine-owned
resource task:

```text
splitEngineMessages
generateOdbcMetadata
ensureCatalogMetadataResources
```

### Root-owned cross-module generation

The root build continues to own generation that genuinely spans modules or uses
build-tool execution:

```text
split engine/client/locale messages
ODBC metadata generation
catalog metadata placement
ClassSizeCatalog generation
```

This is not ordinary module resource copying. Keeping it at the root makes the
cross-module inputs and outputs explicit.

### Product-information task duplication

The product-information task bodies are similar, but they remain local to their
artifact owners. A shared custom plugin or convention was not introduced because:

```text
the tasks are small
the output paths and lifecycle owners are module-specific
local ownership is immediately visible in each artifact build file
extraction would reduce lines without removing an ownership boundary
```

The duplicate root producer was removed; the small local producers remain.

The root-only `moduleSourceRoots`, `productInfoResources`, `externalModulePath`,
`javaccClasspath`, `delosdbConfigureJavaCompile`, `replaceInFile`, and
`writeInfoProperties` declarations were also removed. Their consumers had already
moved into the owning module build files, leaving the root declarations unused.
The unconsumed `delosdbPublicationProjectNames` metadata export and
`delosdbExtensionRuntimeJars` wrapper were removed for the same reason.

## Task and alias ownership

### Removed root forwarding tasks

Forty-one root-level `mvcc*` forwarding tasks and five unconsumed module compile aliases were removed. They only delegated
to canonical tasks in `:delosdb-storage-mvcc`, had no repository, documentation,
CI, packaging, or aggregate-task consumers, and did not represent a Derby
compatibility surface.

The removed module aliases were `compileDelosDbAnnotations`, `compileDelosDbSpi`, `compileOsgiStubs`, `compileDelosDbStorageMvcc`, and `compileDerbyTestsModule`; their standard `compileJava` tasks and artifact tasks remain.

Canonical focused verification remains available through project-qualified task
names, for example:

```text
:delosdb-storage-mvcc:runMvccCoreModelTest
:delosdb-storage-mvcc:runMvccRecoveryReplayEngineTest
:delosdb-storage-mvcc:runDelosMvccLifecycleProofs
:delosdb-storage-mvcc:check
```

### Retained aliases

The following alias categories remain because they have live ownership:

```text
module compile aliases
    consumed by root bytecode verification or cross-module generation

runtime jar tasks
    consumed by gradle/delosdb-runtime-artifacts.gradle and publication

copyEngineServiceDescriptors
    consumed by the storeless compatibility module

compileClassSizeCatalog
    connects the engine artifact to the runtime-api generated catalog

root Derby verification/distribution tasks
    documented and/or called by CI
```

Aliases were not removed merely because a direct source import did not reference
them.

## Custom source sets and configurations

The custom source sets remain justified:

```text
delosdb-buildtools
    isolated build-time generators executed from a dedicated output tree


delosdb-pptesting
    package-private inherited test island compiled on the classpath


delosdb-tests
    inherited Derby test module and consumable test-classes output


delosdb-storeless
    inherited classpath-only storeless prototype that reaches engine internals


delosdb-demos and delosdb-locales
    non-production source/distribution ownership rather than runtime Java modules
```

The custom patch/runtime configurations also remain justified:

```text
enginePatch
    patches inherited store API contracts into the engine module

derbyRuntimePatchArtifacts
    assembles inherited Derby store implementation and bridge resources into derby.jar

enginePatchModule
    compiles server/optional tools against engine patch-module signatures

derbyRuntimePatchElements
    exposes exact patch artifacts from storage-derby and storage-bridge

derbyEngineClasses
    exposes engine classes to compile-only compatibility consumers

derbyTestsClasses
    exposes inherited test classes to package-private tests
```

They encode artifact or JPMS boundaries and are not ordinary source dependencies.

## Runtime artifact and legal-file ownership

`gradle/delosdb-runtime-artifacts.gradle` remains the single source of truth for:

```text
assembled runtime jars
support jars
runtime classpaths
root jars task dependencies
storage-provider discovery verification
```

Individual artifact tasks continue to own:

```text
archive name
module class assembly
legal files under META-INF
artifact-specific manifest title
artifact-specific generated resources
```

The superficially similar JAR blocks were not replaced by one generic JAR task
because they produce different Derby-compatible artifacts and, in the engine's
case, patch and merge additional storage content.

## Static gates, reports, and cleanup scripts

The stable S0 gates remain unchanged in purpose. Phase-specific implementation
gates remain opt-in and are not S0 dependencies.

Generated reports remain below `build/reports`; no generated report is committed
as source. Each retained report has an owning task, and advisory reports remain
explicitly advisory.

Documentation task examples were checked against the registered build surface. The
stale `networkServerSmoke` and `verifyReleaseDistribution` commands were removed;
current documented commands resolve to live tasks. The old release-readiness plan
is explicitly marked historical rather than presented as the active phase.

The cleanup-script static check now recognizes both historical naming forms:

```text
cleanup-overlay-*.sh
cleanup-*-overlay.sh
```

The stale Phase 5 one-shot cleanup script is removed by the Phase 6 cleanup
script. The Phase 6 cleanup script removes itself after use so no new one-shot
script remains in the repository.

`scripts/module-dependency-tree.py` remains because it is a reusable maintained
tool consumed by `delosModuleDependencyReport`.

## Gradle and configuration-cache review

The existing `ProcessResources` output assignments remain expressed with the
property supported by the repository's Gradle 9.5.1 task type:

```groovy
destinationDir = outputDirectory.get().asFile
```

`destinationDirectory` is valid for task types such as `JavaCompile` and archive
tasks, but it is not exposed by this build's `ProcessResources` task. The Phase 6
cleanup therefore does not attempt a cosmetic property migration for resource
processing.

No custom plugin was added. No task was converted merely for style.

The root build revision is obtained through `DelosDbGitRevisionValueSource`
instead of calling `ExecOperations.exec` from a plain provider during project
configuration. The value source preserves the existing short Git revision and
`local` fallback semantics. Its returned value is a configuration input, so a
Git revision change invalidates the stored configuration rather than reusing
stale manifest and product-information metadata.

The engine artifact path is also free of execution-time references to Gradle
script model objects. In particular:

```text
splitEngineMessages
    captures declared files, directories, and classpaths during configuration
    and executes build tools through injected ExecOperations

generateOdbcMetadata
    captures its work directory, source resources, output file, and classpath
    and uses JVM file copying rather than project.copy during task execution

ensureCatalogMetadataResources
    is a declarative Copy task rather than an ad-hoc project.copy action

generateClassSizeCatalog
    executes from declared FileCollection inputs through injected ExecOperations

:delosdb-engine:compileJava
    captures the engine patch path as a FileCollection instead of looking up a
    Configuration from its doFirst action

:delosdb-engine:generateSqlParser
    uses injected FileSystemOperations and JVM file APIs rather than project
    delete/helper closures during execution
```

The engine and shared product-information generators capture their version and
build-revision values as explicit task inputs. This both avoids project-model
access from task actions and causes the files to be regenerated when the Git
revision or DelosDB version changes.

Configuration-cache verification must be performed with the repository's JDK 25
and Gradle wrapper. The required focused commands are recorded with the overlay
that delivers this document.

## Module-consolidation decision

| Module | Boundary that justifies keeping it separate |
| --- | --- |
| `delosdb-annotations` | neutral published annotation API and JPMS module |
| `delosdb-buildtools` | build-time generators; not runtime code |
| `delosdb-client` | Derby-compatible `derbyclient.jar` and JPMS module |
| `delosdb-commons` | Derby-compatible shared API/runtime artifact and JPMS module |
| `delosdb-demos` | demo/distribution source ownership |
| `delosdb-derby-store-api` | inherited store contracts and patch-module boundary |
| `delosdb-engine` | Derby-compatible `derby.jar`, engine JPMS module, patch assembly owner |
| `delosdb-locales` | locale distribution ownership |
| `delosdb-optionaltools` | Derby-compatible optional-tools artifact and optional dependencies |
| `delosdb-osgi-stub` | isolated OSGi compile/runtime compatibility stub |
| `delosdb-pptesting` | package-private test isolation |
| `delosdb-runner` | Derby-compatible runner artifact and JPMS module |
| `delosdb-runtime-api` | extracted inherited runtime contracts and JPMS boundary |
| `delosdb-server` | DRDA server artifact and JPMS module |
| `delosdb-spi` | public DelosDB SPI and JPMS module |
| `delosdb-storage-api` | provider-neutral storage contract boundary |
| `delosdb-storage-bridge` | Derby-facing MVCC adapter and patch artifact |
| `delosdb-storage-derby` | inherited Derby store implementation and derby.jar patch artifact |
| `delosdb-storage-io` | low-level Delos-native page/volume contracts and JPMS module |
| `delosdb-storage-mvcc` | independently discoverable storage provider runtime artifact |
| `delosdb-storeless` | isolated inherited storeless compatibility prototype |
| `delosdb-tests` | inherited compatibility/integration test artifact and task ownership |
| `delosdb-tools` | Derby-compatible `derbytools.jar` and JPMS module |

Consolidating any of these modules would currently erase a real compatibility,
artifact, JPMS, ServiceLoader, test-isolation, or build-tool boundary. The Phase 6
conclusion is therefore:

```text
No module merge is justified.
```

## Production dead-code review

The two curated candidates were rechecked:

```text
MvccDurableIndexStore
    retained: compatibility facade used by durable-index proof tests

MvccVacuum
    retained: coordinator used by durable and concurrent vacuum tests
```

They are not dead code. Their active candidate rows were removed so the report no
longer presents resolved items as open deletion hypotheses.

No other production deletion was made without evidence for source use,
classfile-signature use, reflection, ServiceLoader, durable-format ownership,
packaging, tests, and compatibility.

## Phase 6 exit decision

After this slice passes its JDK 25 focused checks and normal gates, Phase 6 meets
its closeout criteria:

```text
no confirmed dead production code
no unexplained dependency candidates
no unjustified custom build-wiring edges
no stale one-shot cleanup scripts
no obsolete root MVCC forwarding or unconsumed module compile aliases
no generated reports without task ownership
no package ownership collisions
no output-directory classpath backdoors
no module merge without a real boundary benefit
module boundaries documented and justified
```

The next phase should be selected from a concrete correctness, compatibility,
performance, or release-readiness need rather than extending cleanup for its own
sake.
