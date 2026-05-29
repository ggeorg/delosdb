# Security Policy

DelosDB is currently in early fork/bootstrap mode.

## Supported versions

No production-ready DelosDB release has been published yet. Until a first release exists, security fixes should target the default branch.

## Reporting a vulnerability

Please report suspected vulnerabilities privately to the project maintainer. Do not open a public issue for an unpatched vulnerability.

Once the repository is created, replace this section with the maintainer's preferred private contact method or GitHub private vulnerability reporting.

## Scope

Security-sensitive areas include:

- SQL parsing and execution,
- authentication and network server behavior,
- database file handling,
- class loading,
- stored procedures and user-defined functions,
- deserialization or reflection paths,
- permissions and filesystem access.
