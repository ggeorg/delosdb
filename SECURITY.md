# Security Policy

DelosDB has no production-ready release yet. Until a first release exists, security fixes target the default branch.

## Reporting a vulnerability

Report suspected vulnerabilities privately to the maintainer. Do not open a public issue for an unpatched vulnerability.

When the public repository is configured for GitHub private vulnerability reporting, this file should be updated with that process.

## Security-sensitive areas

- SQL parsing, binding, optimization, and execution.
- Authentication and network server behavior.
- Database file and raw-store handling.
- Stored procedures, functions, and SQL-visible metadata routines.
- Reflection, class loading, and optional tool integrations.
- Permission checks and system catalog metadata.
- Provider surfaces that influence physical storage, indexing, or cost decisions.

## Compatibility note

DelosDB preserves Apache Derby license and notice attribution, but DelosDB is not an Apache Software Foundation project and security reports for DelosDB should go to DelosDB maintainers.
