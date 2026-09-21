# contract-service

## Purpose

Owns the contract lifecycle and the original file. It is the only service that writes the
`contracts` table and the only one that writes to MinIO. It contains no AI code.

## Responsibilities

- Accept multipart uploads and store the file in MinIO
- Create and read `contracts` rows
- Publish `contract.uploaded` so the AI pipeline can start
- Consume `contract.indexed`, `contract.analyzed` and `contract.failed` to advance the status
- Validate JWTs independently of the gateway and enforce the role rules

## Technology

Java 21, Spring Boot 4.0.7, Spring Web MVC, Spring Data JPA, Spring Kafka, Spring Security
OAuth2 Resource Server, MinIO Java SDK 8.6.0, PostgreSQL, Lombok, springdoc-openapi.

It declares Flyway but ships **no migrations**: the schema it validates against is created by
ai-service's `V1__init_schema.sql`.

## Entry point

`src/main/java/com/procuremind/contract_service/ContractServiceApplication.java` is a plain
`@SpringBootApplication`. Component scanning picks up the controller, services, Kafka
listeners and security configuration. Port 8081, from `application.yaml`.

## Package structure

```
com/procuremind/contract_service/
├── controller/   REST surface, no business logic
├── service/      business logic and orchestration
│   └── kafka/    producer and listener
├── repository/   Spring Data interfaces
├── entity/       JPA entities
├── dto/          API response shapes
├── config/       MinIO client, Kafka error handling
└── security/     resource-server configuration
```

| Package | Belongs here | Does not belong here |
|---|---|---|
| `controller` | Request mapping, `@PreAuthorize`, DTO return values | Persistence, MinIO calls, Kafka |
| `service` | Transactions, orchestration, entity to DTO mapping | HTTP concerns |
| `service/kafka` | Publishing and consuming events | Business rules beyond status transitions |
| `repository` | Spring Data interfaces | Hand-written SQL unless justified |
| `entity` | JPA mappings only | Behaviour |
| `config` | Infrastructure beans | Business logic |
| `security` | Filter chains, JWT decoding, role mapping | Anything not security |

---

## Controller

### POST /api/contracts/upload

**Controller:** `ContractController`
**Method:** `uploadContract(MultipartFile file, String vendorName)`

**Request:** `multipart/form-data` with part `file` and optional param `vendorName`
(defaults to `"Unknown Vendor"`). The React client also sends `contractType` and `amount`,
which **this endpoint ignores**.

**Response:** `202 Accepted` with `ContractResponseDto`, or `400` with the literal body
`"Try again later."`

**Calls:** `ContractController` → `ContractService.processNewContract` → `StorageService`,
`ContractRepository`, `ContractEventProducer`

**Authorization:** `@PreAuthorize("hasAnyRole('ANALYST','ADMIN')")` on the method, on top of a
class-level `hasAnyRole('VIEWER','ANALYST','ADMIN')`. The gateway enforces the same rule.

**Important behaviour:** the method catches `Exception` and returns 400 with a fixed string,
so the real cause only appears in the logs. Multipart limits are 70MB for both file and
request.

### GET /api/contracts

Returns every contract. `ContractService.getAllContracts`. Any authenticated role.

### GET /api/contracts/{id}

One contract or `404`. `ContractService.getContractDetails`.

### GET /api/contracts/{id}/status

`{"status": "..."}` or `404`. `ContractService.getContractStatus`. Used by the React upload
modal after an upload.

---

## Classes

### ContractService

**Location:** `service/ContractService.java`
**Used by:** `ContractController`
**Depends on:** `StorageService`, `ContractRepository`, `ContractEventProducer`

| Method | Purpose | Important behaviour |
|---|---|---|
| `processNewContract(MultipartFile, String)` | Store file, persist row, announce | `@Transactional`. Uploads to MinIO **before** the row is saved, so a later rollback leaves an orphan object. Publishes through the producer, which defers to after commit |
| `getAllContracts()` | Read model for the contracts table | `@Transactional(readOnly = true)`, maps entities to DTOs |
| `getContractDetails(UUID)` | Single contract | Returns `Optional` |
| `getContractStatus(UUID)` | Status only | Returns `Optional<Map<String,String>>` |

### StorageService

**Location:** `service/StorageService.java`
**Depends on:** `MinioClient` from `config/MinioConfig`

| Method | Purpose | Important behaviour |
|---|---|---|
| `uploadFile(MultipartFile)` | Put the file in MinIO, return the object name | Creates bucket `procuremind-contracts` if missing. Object name is `UUID + "_" + originalFilename`. Streams with an unknown part size (`-1`). Throws on failure |

`BUCKET_NAME` is a private constant duplicated in ai-service's `PdfParsingService`.

### ContractEventProducer

**Location:** `service/kafka/ContractEventProducer.java`

| Method | Purpose | Important behaviour |
|---|---|---|
| `publishContractUploadEvent(UUID, String, String)` | Emit `contract.uploaded` | If a transaction is active the send is registered as an `afterCommit` synchronisation, so a rollback publishes nothing. The send future's outcome is logged; a broker failure after commit is logged, not retried |

Key is the contract id as a string; payload is `ContractUploadedEvent`.

### ContractEventListener

**Location:** `service/kafka/ContractEventListener.java`
**Group:** `contract-processing-group`

| Method | Topic | Behaviour |
|---|---|---|
| `handleContractIndexed(PageIndexedEvent)` | `contract.indexed` | Status to `INDEXED` |
| `handleContractAnalyzed(PageIndexedEvent)` | `contract.analyzed` | Status to `ANALYZED` |
| `handleContractFailed(PageIndexedEvent)` | `contract.failed` | Status to `FAILED` |
| `shouldAdvance(String current, String candidate)` | package-private, static | The transition rule |

`shouldAdvance` keeps the lifecycle monotonic across `UPLOADED -> INDEXED -> ANALYZED` so a
redelivered event cannot move a contract backwards. `FAILED` is reachable from any state, and
a contract at `FAILED` can be recovered by a later lifecycle success. Unknown contract ids are
logged and ignored rather than thrown, so they do not loop through retry and dead-lettering.

All three handlers are `@Transactional` and let exceptions propagate so the error handler can
retry and dead-letter.

---

## Repository and entity

### ContractRepository

`JpaRepository<Contract, UUID>` with no custom queries. Everything used comes from the base
interface: `findAll`, `findById`, `save`.

### Contract

**Table:** `contracts`

| Field | Notes |
|---|---|
| `id` | UUID, generated |
| `filename` | Original upload name |
| `minioObjectName` | Key in the `procuremind-contracts` bucket |
| `vendorName` | From the request parameter |
| `status` | Free-text string: `UPLOADED`, `INDEXED`, `ANALYZED`, `FAILED` |
| `uploadedAt` | Set in `processNewContract` |

No JPA relationships. The link to `page_index_nodes.document_id` and
`contract_analysis.contract_id` is by convention only, across a service boundary.

---

## Configuration

| Class | What it enables |
|---|---|
| `config/MinioConfig` | Builds the `MinioClient` from `minio.endpoint`, `access-key`, `secret-key` |
| `config/KafkaErrorHandlingConfig` | `DefaultErrorHandler` with `FixedBackOff(1s, 2)` (3 attempts) and a `DeadLetterPublishingRecoverer` to `<topic>.DLT`, partition `-1`. Declares the three DLT topics |
| `security/SecurityConfig` | `@EnableWebSecurity`. Two mutually exclusive chains selected by `app.security.enforce` |
| `security/MethodSecurityConfig` | Carries `@EnableMethodSecurity` behind the same property, so the kill switch disables `@PreAuthorize` too |
| `security/JwtDecoderConfig` | `NimbusJwtDecoder.withJwkSetUri(...)` plus `JwtIssuerValidator` for `app.security.issuer-uri` |
| `security/JwtRolesConverter` | Maps the `roles` claim to `ROLE_*` authorities |

Enforcing chain: `/actuator/health*`, `/v3/api-docs/**`, `/swagger-ui*` are public,
`/actuator/**` requires ADMIN, everything else requires authentication. Stateless, CSRF
disabled, no CORS (the gateway owns CORS).

`AUTH_JWK_SET_URI` defaults to `http://localhost:8083/oauth2/jwks` so an IDE run works
unchanged; Compose overrides it with the service name.

---

## Testing

`src/test/java/.../` covers the security matrix in both enforcement modes as `@WebMvcTest`
slices (no database needed), the JWKS configuration contract, `shouldAdvance` lifecycle rules
and the producer's after-commit behaviour. `ContractServiceApplicationTests` is a full context
load and does need PostgreSQL, Kafka and MinIO.
