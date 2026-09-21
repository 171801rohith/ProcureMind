# procuremind-common

## Purpose

Carries the two Kafka event records shared by contract-service and ai-service. Nothing else.

## What is in it

```
com/procuremind/
├── Main.java                      no-op class, not used by any service
└── common/dto/
    ├── ContractUploadedEvent.java
    └── PageIndexedEvent.java
```

```java
public record ContractUploadedEvent(UUID contractId, String filename, String minioObjName) {}
public record PageIndexedEvent(UUID contractId, String status) {}
```

| Record | Topic | Produced by | Consumed by |
|---|---|---|---|
| `ContractUploadedEvent` | `contract.uploaded` | contract-service | ai-service |
| `PageIndexedEvent` | `contract.indexed` | ai-service | ai-service, contract-service |
| `PageIndexedEvent` | `contract.analyzed` | ai-service | contract-service |
| `PageIndexedEvent` | `contract.failed` | ai-service error recoverer | contract-service |

`PageIndexedEvent.status` carries a string that differs by topic: `"INDEXED"` for
`contract.indexed`, `"ANALYSIS_COMPLETED"` for `contract.analyzed`, `"FAILED"` for
`contract.failed`. Consumers do not branch on it; they derive the new status from the topic
the listener is bound to.

## Why it has no dependencies

The POM declares **no parent and no dependencies**. That is deliberate. contract-service runs
on Spring Boot 4.0.7 and ai-service on 3.5.14, so anything Spring-flavoured added here would
force one version onto both. Keep it plain Java.

This is also why `JwtRolesConverter` is duplicated in each service instead of living here.

## Changing these records is a breaking change

Both services deserialise these types from Kafka JSON, gated by
`spring.json.trusted.packages: "com.procuremind.*"`. Renaming a field, changing a type or
moving the package breaks deserialisation in both services at once, and any messages already
on a topic become unreadable. There is no schema registry and no versioning strategy.

## Build

A module of the root reactor, built before contract-service and ai-service.
`src/test` exists but is empty.
