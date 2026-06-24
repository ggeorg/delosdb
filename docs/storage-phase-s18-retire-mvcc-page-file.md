# Storage Phase S18 — Retire old MVCC page-file classes

S18 removes the stale `delosdb-storage-mvcc` page-file primitive implementation now that the storage I/O layer owns page primitives and file-backed page volumes.

Removed source files are explicit and narrow:

- `MvccPageFile.java`
- `MvccPage.java`
- `MvccPageId.java`
- `MvccPageIo.java`

The existing MVCC page-file and version-record codec tests are kept, but they now exercise the storage I/O implementation directly through `DelosPage`, `DelosPageId`, `DelosPageVolume`, and `FileChannelPageVolume`.

This step does not change page format, MVCC recovery, heap behavior, provider behavior, or Gradle task structure.
