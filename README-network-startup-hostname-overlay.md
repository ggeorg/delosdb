# Network startup hostname resolution overlay

## Purpose

This overlay fixes a Derby network-server startup fragility seen during the full language suite:

```text
Timed out waiting for network server to start (localhost:27050)
```

The thread dump showed `derby.NetworkServerStarter` blocked in:

```text
InetAddress.getLocalHost()
NetworkServerControlImpl.buildLocalAddressList(...)
NetworkServerControlImpl.createServerSocket(...)
NetworkServerControlImpl.blockingStart(...)
```

On macOS and other developer machines, `InetAddress.getLocalHost()` can block on hostname/DNS resolution long enough for the Derby test harness to time out.

## Change

Updates:

```text
delosdb-server/src/main/java/org/apache/derby/impl/drda/NetworkServerControlImpl.java
```

`buildLocalAddressList()` no longer calls `InetAddress.getLocalHost()` during server startup.

Instead it builds the local admin-address list from:

```text
- the bind address
- InetAddress.getLoopbackAddress()
- localhost
- NetworkInterface.getNetworkInterfaces() addresses
```

This avoids hostname lookup while still collecting local addresses for admin-command validation.

## Apply

From repo root:

```sh
unzip -oq ~/Downloads/delosdb-network-startup-hostname-overlay.zip
```

No cleanup script is required.

## Verify

Run the full suite again:

```sh
./gradlew clean fullVerification :delosdb-storage-mvcc:check
```

Optional focused check:

```sh
./gradlew :delosdb-server:compileJava :delosdb-tests:runDerbyLangSuite
```

## Commit comment

```text
Avoid hostname lookup during network server startup
```
