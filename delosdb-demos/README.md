# DelosDB Demos Module

`delosdb-demos` contains inherited Apache Derby demo assets. They are retained
for reference and compatibility research, but they are not currently DelosDB
product demos.

Current status:

- source and sample assets are preserved
- `:delosdb-demos:compileDemos`, part of root `check`, compiles each demo on its
  own against the assembled runtime jars; `scores` is left out because it needs
  Apache Commons Math, and `toursdb` is compiled and loaded by `delosdb-tests`
- running the demos is not verified
- legacy Ant files and path-sensitive sample layouts are not part of the
  supported DelosDB build workflow

Modern DelosDB product demonstrations live outside this inherited source tree. The first supported
example is `../examples/readable-engine.sql`, executed through the root `readableEngineDemo` Gradle
task and shipped in the binary distribution.
