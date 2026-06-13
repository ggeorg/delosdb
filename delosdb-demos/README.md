# DelosDB Demos Module

`delosdb-demos` contains inherited Apache Derby demo assets. They are retained
for reference and compatibility research, but they are not currently DelosDB
product demos.

Current status:

- source and sample assets are preserved
- `:delosdb-demos:verifyInheritedDemosStatus` checks that the retained assets
  are still present and explicitly documented
- compilation and execution are intentionally deferred
- legacy Ant files and path-sensitive sample layouts are not part of the
  supported DelosDB build workflow

Future DelosDB demos should be created separately as modern, Gradle-owned,
small executable examples. The inherited demos should only be compiled once we
choose to maintain them as supported examples.
