# Contributing to DelosDB

DelosDB is in a provider-hardening phase. The current rule is simple: finish and verify existing seams before opening new ones.

## Supported local workflow

Use the checked-in Gradle Wrapper from the repository root:

```bash
./gradlew build
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

For the broader gate:

```bash
./gradlew fullVerification
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
```

If a Derby test run was interrupted, run `./gradlew clean` before retrying.

## Contribution rules

- Keep changes focused and source-backed.
- Do not start a new provider family while existing seams are still being finished.
- Add or update a smoke/proof when behavior changes.
- Update documentation only after the code proof is green.
- Do not add stale checkpoint documents; update the existing roadmap/status docs instead.
- Do not remove Apache license headers or attribution.
- Do not use Apache Derby branding for modified DelosDB distributions.

## Workspace metadata

Developer workspaces may contain `.git/`, `.gradle/`, and `.idea/`. Cleanup scripts must not delete them. Assistant overlay ZIPs must not include them.

## Style

Preserve inherited Derby style unless the cleanup is deliberate and behavior-preserving. Prefer small verified changes over mechanical rewrites.
