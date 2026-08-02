# DelosDB Storeless Prototype

`delosdb-storeless` is an inherited Derby prototype retained as an experimental
DelosDB research seed. It boots compiler/planner-facing database services
without a physical store.

Current status:

- compile-gated by `:delosdb-storeless:compileDerbyStoreless`
- resource-gated by `:delosdb-storeless:processStorelessResources`
- smoke-gated by `:delosdb-storeless:storelessPrototypeSmoke`
- not a public SPI
- not connected to a physical storage access method
- not packaged as a runtime module

The intended direction is no-store compiler/planner research and, later, a
possible bridge toward experimental storage research. Until that bridge is
designed, this module must remain explicitly experimental and isolated.
