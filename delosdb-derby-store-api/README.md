# DelosDB Derby Store API

This module owns the inherited Derby store contracts under `org.apache.derby.iapi.store.*`.

It is a source-owner boundary, not a new public Delos storage provider API. The runtime Derby compatibility jar still exposes these classes through `derby.jar` while the codebase is being split into cleaner Gradle modules.

Expected users:

- `delosdb-storage-derby`: real inherited Derby heap/raw/access implementation.
- `delosdb-storeless`: no-op implementation of the same Derby store contract.
- `delosdb-engine`: Derby engine code which calls the store contract.
