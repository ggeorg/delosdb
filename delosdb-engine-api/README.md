# delosdb-engine-api

Source-owner module for shared inherited Derby engine API contracts.

This module is not the Delos provider SPI and is not the Derby storage
implementation. It exists to pull shared `org.apache.derby.iapi.*` service and
shared contracts out of `delosdb-engine` so
implementation modules can depend on contracts without depending on a runtime
implementation owner.
