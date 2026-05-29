# Contributing to DelosDB

DelosDB is in early bootstrap mode. The current priority is safe modernization:

1. preserve existing Derby behavior,
2. add reproducible builds and smoke tests,
3. improve documentation,
4. modernize Java internals incrementally,
5. avoid large rewrites without benchmarks and compatibility tests.

## Local workflow

```bash
gradle build
gradle smoke
```

After the Gradle Wrapper is committed:

```bash
./gradlew build
./gradlew smoke
```

## Pull request rules

- Keep patches focused.
- Explain compatibility impact.
- Add or update tests when behavior changes.
- Do not remove existing license headers.
- Do not use Apache Derby branding for modified distributions.

## Code style

For now, preserve the existing source style unless a specific cleanup issue states otherwise. Mechanical style-only rewrites should wait until CI and compatibility tests are stronger.
