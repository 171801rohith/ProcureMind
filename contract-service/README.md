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
OAuth2 Resource Server, MinIO Java SDK 8.6.0, Apache Tika (upload content-type validation),
PostgreSQL, Lombok, springdoc-openapi.

It now ships its own Flyway migration, `db/migration/V1__contracts_table.sql`
(`CREATE TABLE IF NOT EXISTS contracts (...)`), recorded in its own history table,
`flyway_schema_history_contract` (`spring.flyway.table`), rather than the default
`flyway_schema_history` ai-service uses. Two independently-deployed services cannot safely
share one Flyway history table even against the same physical schema: Flyway's `validate()`
cross-checks the *entire* applied-migration history against what's locally resolvable, so each
service would permanently fail validation on the versions the other applied. ai-service's
historical `V1__init_schema.sql` still creates this table too, unchanged and left alone
(editing an already-applied migration breaks checksum validation everywhere it already ran) —
it is kept purely for compatibility with databases that already ran it. See
`ARCHITECTURE_REVIEW.md` finding #16 for the full reasoning, including why the original
shared-history-table plan was tried, found to fail live, and revised to this.

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

**Response:** `202 Accepted` with `ContractResponseDto`; `400` with the real, specific reason
(e.g. `"Unsupported file type 'image/png'; only PDF documents are accepted."`) when
`FileValidationService` rejects the upload; `400` with the literal body `"Try again later."`
for any other failure.

**Calls:** `ContractController` → `ContractService.processNewContract` →
`FileValidationService`, `StorageService`, `ContractRepository`, `ContractEventProducer`

**Authorization:** `@PreAuthorize("hasAnyRole('ANALYST','ADMIN')")` on the method, on top of a
class-level `hasAnyRole('VIEWER','ANALYST','ADMIN')`. The gateway enforces the same rule.

**Important behaviour:** `UnsupportedFileTypeException` (from `FileValidationService`) is
caught specifically and returns its real message; every other exception still falls back to
the generic 400 string, so an unexpected failure's real cause only appears in the logs.
Multipart limits are 70MB for both file and request.

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
**Depends on:** `FileValidationService`, `StorageService`, `ContractRepository`,
`ContractEventProducer`

| Method | Purpose | Important behaviour |
|---|---|---|
| `processNewContract(MultipartFile, String)` | Validate, store file, persist row, announce | `@Transactional`. Calls `FileValidationService.validatePdf` **first**, before the file ever reaches MinIO. Uploads to MinIO **before** the row is saved, so a later rollback (or a DB save failure) leaves an orphan object in MinIO with no compensating delete — confirmed and locked in by `ContractServiceTest.aFailedDbSaveOrphansTheAlreadyUploadedMinioObject`, deliberately not fixed (see Known gaps). Once the row is saved, restores the contract id into MDC (`CorrelationIds.MDC_KEY`) for the rest of the method, then publishes through the producer, which defers to after commit |
| `getAllContracts()` | Read model for the contracts table | `@Transactional(readOnly = true)`, maps entities to DTOs |
| `getContractDetails(UUID)` | Single contract | Returns `Optional` |
| `getContractStatus(UUID)` | Status only | Returns `Optional<Map<String,String>>` |

### FileValidationService

**Location:** `service/FileValidationService.java`
**Used by:** `ContractService.processNewContract`, as the first step

| Method | Purpose | Important behaviour |
|---|---|---|
| `validatePdf(MultipartFile)` | Reject anything that isn't really a PDF | Uses Apache Tika's `Tika.detect(InputStream, filename)` to sniff the file's actual magic bytes — **not** the client-supplied `Content-Type` header, which proves nothing about the bytes that follow it. Throws `UnsupportedFileTypeException` (mapped to 400 by the controller) for a non-`application/pdf` detection, an empty file, or an unreadable stream |

Proven against real Tika, not mocks: a PNG renamed to `.pdf`, and a PNG with a spoofed
`Content-Type: application/pdf` header, are both rejected by their actual magic bytes.

### StorageService

**Location:** `service/StorageService.java`
**Depends on:** `MinioClient` from `config/MinioConfig`

| Method | Purpose | Important behaviour |
|---|---|---|
| `uploadFile(MultipartFile, String sanitizedFilename)` | Put the file in MinIO, return the object name | Creates bucket `procuremind-contracts` if missing. Object name is `UUID + "_" + sanitizedFilename` (the filename is sanitized by the caller, `ContractService.sanitizeFilename`, before this method ever sees it). Streams with an unknown part size (`-1`). Throws on failure. Exposes **no delete/cleanup method** — see the orphan-on-rollback note above |

`BUCKET_NAME` is a private constant duplicated in ai-service's `PdfParsingService`.

### ContractEventProducer

**Location:** `service/kafka/ContractEventProducer.java`

| Method | Purpose | Important behaviour |
|---|---|---|
| `publishContractUploadEvent(UUID, String, String)` | Emit `contract.uploaded` | If a transaction is active the send is registered as an `afterCommit` synchronisation, so a rollback publishes nothing. The send future's outcome is logged; a broker failure after commit is logged, not retried |

Key is the contract id as a string; payload is `ContractUploadedEvent`; the record also carries
the contract id as an explicit header, `CorrelationIds.HEADER` (`X-Contract-Id`, defined in
`procuremind-common`), so a consumer can restore it into MDC without deserializing the payload
first.

### ContractStatusUpdater

**Location:** `service/kafka/ContractStatusUpdater.java`
**Used by:** `ContractEventListener`, and `KafkaErrorHandlingConfig`'s dead-letter recoverer

| Method | Purpose | Important behaviour |
|---|---|---|
| `advance(UUID, String newStatus)` | Apply a lifecycle transition | `@Transactional`. Delegates to `shouldAdvance` |
| `markFailedByKey(String rawContractId)` | Mark FAILED from a raw Kafka record key | Tolerates a missing or non-UUID key by logging and returning, rather than throwing |
| `shouldAdvance(String current, String candidate)` | package-private, static | The transition rule |

`shouldAdvance` keeps the lifecycle monotonic across `UPLOADED -> INDEXED -> ANALYZED` so a
redelivered event cannot move a contract backwards. `FAILED` is reachable from any state, and
a contract at `FAILED` can be recovered by a later lifecycle success. This class used to be
private logic inside `ContractEventListener`; it was pulled out so
`KafkaErrorHandlingConfig`'s recoverer could call `markFailedByKey` too (see below and
`ARCHITECTURE_REVIEW.md` finding #4).

### ContractEventListener

**Location:** `service/kafka/ContractEventListener.java`
**Group:** `contract-processing-group`

| Method | Topic | Behaviour |
|---|---|---|
| `handleContractIndexed(PageIndexedEvent, byte[] correlationId)` | `contract.indexed` | Status to `INDEXED` |
| `handleContractAnalyzed(PageIndexedEvent, byte[] correlationId)` | `contract.analyzed` | Status to `ANALYZED` |
| `handleContractFailed(PageIndexedEvent, byte[] correlationId)` | `contract.failed` | Status to `FAILED` |

Each handler's second parameter is `@Header(value = CorrelationIds.HEADER, required = false)`:
when present it's restored into MDC (`CorrelationIds.MDC_KEY`, i.e. `contractId`) for the
duration of the call, cleared in a `finally`; when absent (a message published before this
header existed) it falls back to the event's own contract id. `logging.pattern.console` in
`application.yaml` includes `%X{contractId:-}` so this actually shows up in every log line.

Unknown contract ids are logged and ignored rather than thrown, so they do not loop through
retry and dead-lettering. All three handlers let exceptions propagate so the error handler can
retry and dead-letter (transactionality now lives in `ContractStatusUpdater.advance`, not on
these methods directly).

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
| `config/KafkaErrorHandlingConfig` | `DefaultErrorHandler` with `FixedBackOff(1s, 2)` (3 attempts). Its recoverer, `MarkFailedThenDeadLetterRecoverer`, calls `ContractStatusUpdater.markFailedByKey` **before** dead-lettering to `<topic>.DLT` (partition `-1`), so a message that exhausts its retries leaves the contract visibly `FAILED` instead of silently stuck at its prior status. Declares the three DLT topics |
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

65 tests, all green (`./mvnw -pl contract-service test`), against a real local
Postgres/Kafka/MinIO stack:

- `security/` — the security matrix in both enforcement modes as `@WebMvcTest` slices (no
  database needed), the JWKS configuration contract.
- `service/kafka/ContractStatusUpdaterTest` — `shouldAdvance` lifecycle rules, `advance`, and
  `markFailedByKey`'s edge cases (missing/non-UUID key).
- `service/kafka/ContractEventListenerTest` — delegation to `ContractStatusUpdater` and MDC
  restore/clear around each handler.
- `service/kafka/ContractEventProducerTest` — after-commit deferral, rollback publishes
  nothing, and the correlation header is present on every sent record.
- `config/KafkaErrorHandlingConfigTest` — the dead-letter recoverer marks the contract FAILED
  before dead-lettering, and tolerates a record with no key.
- `service/FileValidationServiceTest` — real Apache Tika, no mocking: a genuine PDF is
  accepted; a PNG renamed to `.pdf`, and a PNG with a spoofed `Content-Type: application/pdf`
  header, are both rejected by their actual bytes; an empty file is rejected.
- `service/StorageServiceTest` — bucket-creation ordering, and that upload arguments pass
  through correctly.
- `service/ContractServiceTest` — filename sanitization, MinIO-before-DB-row ordering, a
  failed upload touching neither the DB nor Kafka, and the confirmed MinIO-orphan-on-failed-DB-save
  gap (see Known gaps).
- `controller/ContractControllerTest` — the actual HTTP contract: 202 on success, 400 with the
  specific message for a rejected file type, 400 with the generic fallback for anything else.
- `ContractServiceApplicationTests` is a full context load and does need PostgreSQL, Kafka and
  MinIO; it's also what proves the service's own Flyway migration (`V1__contracts_table.sql`,
  its own `flyway_schema_history_contract` table) applies cleanly.

## Known gaps

- **MinIO-orphan-on-failed-DB-save.** `processNewContract` uploads to MinIO before saving the
  `Contract` row; if the save then fails, the uploaded object is orphaned with no compensating
  delete (`StorageService` exposes no delete method). Confirmed and locked in by
  `ContractServiceTest.aFailedDbSaveOrphansTheAlreadyUploadedMinioObject` rather than fixed —
  `ARCHITECTURE_REVIEW.md` finding #8 scoped this pass to verifying the behaviour, not adding
  compensating-transaction logic.
