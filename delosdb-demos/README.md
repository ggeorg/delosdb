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

Modern DelosDB product demonstrations live outside this inherited source tree. The first supported
example is `../examples/readable-engine.sql`, executed through the root `readableEngineDemo` Gradle
task and shipped in the binary distribution. The inherited demos should only be compiled once we
choose to maintain them as supported examples.
