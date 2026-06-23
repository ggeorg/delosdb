# Closed Phase Guard Cleanup

Phase F is closed, so the active storage verification surface no longer needs
the historical C1-C37 and F0-F8 text-token report guards.

The cleanup keeps executable coverage and permanent architectural checks:

- `storage-derby-boundary-guards.gradle` keeps Derby/store boundary rules.
- `storage-spi-truth-map.gradle` keeps the no-third-SPI-island truth map.
- `storagePhaseC7StabilizationSmoke` remains available as a closed-phase
  regression smoke.
- `storage-phase-f-native-execution.gradle` keeps the Phase F native execution
  smokes and public verify task names.

The retired files were step-completion scaffolding. Their source-string checks
were useful during implementation, but after closeout they are brittle and add
configuration noise without improving behavioral coverage.
