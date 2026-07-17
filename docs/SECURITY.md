# DelosDB security defaults

DelosDB preserves Derby-compatible interfaces while making security boundaries explicit. This
document describes current behavior; it is not a deployment-specific security policy.

## DRDA TLS modes

The inherited property and connection attribute names remain compatible:

| Mode | Current meaning |
|---|---|
| `off` | Clear-text DRDA transport. |
| `basic` | TLS encryption only. The client accepts the presented server certificate, so this mode does not authenticate server identity. The server does not require a client certificate. |
| `peerAuthentication` | Certificate-authenticated TLS. Normal JSSE trust validation is used and the server requires a client certificate. |

`basic` must not be described as authenticated TLS. Use `peerAuthentication` with correctly managed
keystore and truststore material when peer identity matters.

JSSE keystore properties are copied independently, so a configured keystore with no password no
longer causes a null-property failure. Keystore input streams are closed immediately after loading.
Passwords and key material are not written to DelosDB diagnostics.

## Object deserialization

Inherited Derby paths still deserialize Java objects for heap `JAVA_OBJECT` values and a small
number of protocol/import/replication leaf sites. DelosDB installs this compatibility-preserving
resource-limit filter by default:

```text
maxdepth=32;maxrefs=100000;maxbytes=16777216;maxarray=100000;*
```

The class wildcard preserves existing serializable classes, while the limits bound graph depth,
reference count, input bytes, and array size.

Deployments can replace the defaults with standard JDK `ObjectInputFilter` patterns:

```text
delosdb.objectDeserializationFilter
delosdb.heap.objectDeserializationFilter
```

The inherited unbounded behavior is available only through the explicit compatibility switch:

```text
delosdb.objectDeserializationCompatibilityMode=true
```

A configured general or heap filter takes precedence over compatibility mode. Compatibility mode
should be temporary and limited to trusted legacy data while an explicit allow-list or bounded
filter is prepared.

MVCC tables reject `JAVA_OBJECT` and Derby UDT columns before table creation with SQLState `0A000`.

## XML processing

SQL/XML, optimizer XML traces, and PlanExporter transformations use DelosDB's centralized secure XML
factories. Secure processing is enabled and external DTD and stylesheet access is disabled on
transformation paths. PlanExporter closes stylesheet and output streams deterministically.

## Verification

```bash
./gradlew :delosdb-tests:runDelosSecurityTruthTest
./gradlew delosSecurityTruthStaticAnalysis
./gradlew delosHeapObjectDeserializationFilterStaticAnalysis
```
