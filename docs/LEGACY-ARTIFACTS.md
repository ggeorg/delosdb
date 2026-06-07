# Legacy artifact directories

The DelosDB repository inherits several Derby-era root directories that can look like active build outputs.

They are **not** produced by the supported Gradle workflow and should not be treated as the DelosDB build output surface.

## Supported Gradle outputs

The supported runtime outputs are produced under:

```text
build/libs/
build/distributions/
build/release/
```

Use these tasks to inspect or verify supported outputs:

```bash
./gradlew jars
./gradlew verifyJars
./gradlew dist
./gradlew verifyArtifactInventory
./gradlew fullVerification
```

## Inherited Derby-era directories

These directories may appear in old snapshots, local working trees, or inherited source drops:

```text
classes/
classes.pptesting/
generated/
jars/
jars/sane/
```

They are Ant-era or local generated-output locations from the inherited Derby tree. They are ignored by `.gitignore` and are not the source of truth for DelosDB runtime artifacts.

## Contributor rule

When working on DelosDB:

- use Gradle tasks from the repository root;
- inspect `build/libs/` for jars;
- inspect `build/distributions/` for archives;
- do not copy files from `classes/`, `generated/`, or `jars/` into the Gradle build;
- do not reintroduce Ant as the supported workflow.
