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

DelosDB separates untrusted external object boundaries from trusted heap persistence.

### DRDA and import UDT boundaries

Serialized UDT values received over DRDA or read from an import file reject every application class
by default. The default policy is resource-bounded and ends in a reject-all rule:

```text
maxdepth=32;maxrefs=100000;maxbytes=16777216;maxarray=100000;!*
```

Applications which intentionally exchange a UDT must configure an explicit JDK
`ObjectInputFilter` allow-list. Boundary-specific properties take precedence over the common
external fallback:

```text
delosdb.drda.objectDeserializationFilter
delosdb.import.objectDeserializationFilter
delosdb.objectDeserializationFilter
```

For example:

```text
delosdb.drda.objectDeserializationFilter=com.example.SafeValue;java.base/*;!*
```

### DRDA system-catalog boundary

The current engine exposes eleven `JAVA_OBJECT` columns across nine `SYS` catalog tables. These
values are not application UDT payloads. The client identifies them from the DRDA base-schema and
base-table metadata and applies a fixed, resource-bounded allow-list for Derby catalog descriptors,
catalog implementations, formatable bit sets and hashtable holders, built-in SQL value objects, and
required JDK classes.

Application UDT filter properties and external compatibility mode do not widen this internal
catalog policy. An application class stored in a catalog `JAVA_OBJECT` column remains rejected.
The server-side DRDA parameter boundary and normal application UDT result columns remain fail
closed unless explicitly configured.

### Import metadata boundary

The import implementation also serializes two engine-generated metadata shapes while constructing
its internal VTI: an `ArrayList<String>` of SQL type names and a `HashMap<String,String>` of UDT
class names. This is not application UDT data and therefore does not use the external import
allow-list.

It uses a fixed, resource-bounded allow-list containing only the two collection shapes, `String`,
and the JDK backing-array component types needed to deserialize them. Application filter properties
and external compatibility mode do not widen this internal metadata policy. Unexpected collection
implementations and application classes remain rejected.

### Replication boundary

Replication remains enabled by default but accepts only the fixed inherited protocol shapes:

```text
ReplicationMessage
Long
byte[]
String
String[]
```

The replication override is:

```text
delosdb.replication.objectDeserializationFilter
```

### Heap JAVA_OBJECT persistence

Heap `JAVA_OBJECT` values are a database persistence compatibility contract rather than an external
transport boundary. They retain the separate resource-bounded default:

```text
maxdepth=32;maxrefs=100000;maxbytes=16777216;maxarray=100000;*
```

Deployments can replace it with an application allow-list:

```text
delosdb.heap.objectDeserializationFilter
```

### Explicit compatibility modes

Inherited unfiltered external deserialization is available only through:

```text
delosdb.objectDeserializationCompatibilityMode=true
```

Inherited unfiltered heap `JAVA_OBJECT` reads use a separate switch:

```text
delosdb.heap.objectDeserializationCompatibilityMode=true
```

An explicit boundary or heap filter always takes precedence over its compatibility switch. The
external switch does not weaken heap policy, and the heap switch does not enable DRDA, import, or
replication deserialization. Compatibility modes should be temporary and limited to trusted legacy
data while explicit allow-lists are prepared.

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
