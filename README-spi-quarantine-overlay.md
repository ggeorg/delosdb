# DelosDB SPI quarantine overlay

This overlay executes the phase-1 SPI cleanup:

- adds `delosdb-annotations` for stability/visibility annotations;
- moves `io.github.ggeorg.delosdb.spi.storage.versioned.*` into `delosdb-versioned-storage-spi`;
- keeps `delosdb-spi` focused on function/index/storage/type provider metadata;
- removes the production engine JPMS `uses VersionedStorageProvider` declaration;
- changes SQL provider validation to recognize reserved versioned provider names without ServiceLoader discovery;
- leaves legacy engine versioned execution classes compiling against the quarantined module for now.

After extracting the overlay, run:

```sh
./scripts/cleanup-overlay-spi-quarantine.sh
```

Then run the normal full verification suite for this repository.
