# delosdb-runtime-api

Source-owner module for shared inherited Derby runtime API.

This module is not the Delos provider SPI and is not the Derby storage
implementation. It exists to pull shared `org.apache.derby.iapi.*` service and
shared low-level runtime contracts out of `delosdb-engine` so
implementation modules can depend on runtime/service contracts without depending
on the embedded SQL engine implementation.
