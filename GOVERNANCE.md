# Governance

DelosDB starts as a maintainer-led fork.

## Maintainer model

The initial maintainer owns releases, repository settings, security intake, and roadmap decisions.

As contributors appear, the project can move to a small maintainer group with documented review and release rules.

## Decision principles

1. Compatibility before novelty.
2. Benchmarks before performance claims.
3. Small patches before rewrites.
4. Clear attribution to Apache Derby.
5. No Apache branding for modified distributions.

## Release rule

No release should be cut until the project has:

- a reproducible build,
- CI on JDK 21 or newer,
- embedded JDBC smoke tests,
- network server smoke tests,
- license and notice review,
- documented migration notes.
