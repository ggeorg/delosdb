# DelosDB Roadmap

## Milestone 0 — Bootstrap

- Add GitHub-ready README, governance, security, and contribution files.
- Add a Gradle build entry point over the inherited Ant build.
- Remove the need to manually pass SVN-era `changenumber` values.
- Add embedded JDBC smoke test.
- Add CI for JDK 21+.

## Milestone 1 — Build stabilization

- Generate and commit the Gradle Wrapper.
- Add CI matrix for supported JDKs.
- Add network-server smoke test.
- Add artifact checks for engine, client, tools, server, and runner jars.
- Document developer build commands.

## Milestone 2 — Legal and release hygiene

- Review LICENSE and NOTICE inheritance.
- Remove stale Apache release/deployment instructions from the active fork workflow.
- Add fork migration notes.
- Define package and artifact naming policy.
- Prepare first non-production preview release.

## Milestone 3 — Java modernization audit

- Audit SecurityManager-era code paths.
- Audit `finalize()` cleanup paths.
- Audit reflective loading and classloader-sensitive behavior.
- Add `jdeps` reporting.
- Add focused issues for safe replacements.

## Milestone 4 — Compatibility and benchmarks

- Add embedded JDBC compatibility tests.
- Add startup-time and simple transaction benchmarks.
- Add batch insert and indexed lookup benchmarks.
- Publish baseline results before optimization work.

## Milestone 5 — Modern API layer

- Keep JDBC as the core compatibility layer.
- Add a small modern embedded convenience API.
- Add examples for plain Java, Spring Boot, and desktop apps.
