# ProcureMind

Event-driven platform that ingests procurement contracts, breaks them into a hierarchical
clause index, and produces an AI risk assessment for each one.

This README is written for a developer joining the project today. It describes what the code
actually does. Where the implementation is unclear it says so rather than guessing.

---

## 1. What is ProcureMind?

A user uploads a contract. The system stores the file, extracts its text, splits it into a
tree of sections, writes a short AI summary for every section, then screens the whole
document for risk and produces a single risk score with a list of findings. The result is
served back to two frontends: a React SPA and a Streamlit dashboard.

The pipeline is asynchronous. The upload returns `202 Accepted` immediately and the rest
happens over Kafka.

**Retrieval is structure-aware, not vector-based.** There is no embedding model, no vector
database and no similarity search anywhere in this repository. Retrieval works by walking a
relational tree of contract sections (`page_index_nodes`) and reading clauses by id. See
section 10.

---

## 2. High-level architecture

```
        Browser                         Browser
   procuremind-react                procuremind-ui
      (nginx :5173)                  (Streamlit :8501)
            |                               |
            |  Bearer JWT                   |  Bearer JWT
            +---------------+---------------+
                            |
                            v
                   api-gateway (:8080)
             Spring Cloud Gateway Server WebMVC
             OAuth2 resource server + RBAC
                            |
        +-------------------+-------------------+
        |                   |                   |
        v                   v                   v
 contract-service      ai-service         auth-service (:8083)
    (:8081)             (:8082)        Spring Authorization Server
        |                   |            OIDC provider + /api/users
        |                   |
        |                   +--> Ollama (host :11434, OpenAI-compatible API)
        |                   |
        v                   v
     MinIO (:9000)     PostgreSQL (:5433)
        |                   |
        +------- Kafka (:9092) ---------+
                     |
                     v
          contract.uploaded -> contract.indexed -> contract.analyzed
                                                 \-> contract.failed
```

Every one of api-gateway, contract-service and ai-service validates the JWT itself against
auth-service's JWKS endpoint. No service trusts an `X-User-*` header; none is sent.

| Service | Responsibility |
|---|---|
| `api-gateway` | Single ingress. Routes `/api/**`, enforces coarse-grained roles, owns CORS |
| `contract-service` | Contract lifecycle, file storage, `contracts` table, upload event |
| `ai-service` | PDF parsing, hierarchy building, summarisation, risk analysis, read models, chat |
| `auth-service` | OIDC provider (login, tokens, JWKS) and ADMIN-only user administration |
| `procuremind-common` | The two Kafka event records shared by contract-service and ai-service |
| `procuremind-react` | React SPA, the primary UI |
| `procuremind-ui` | Streamlit dashboard, a second UI over the same gateway API |

---

## 3. Technology stack

| Area | Technology | Where |
|---|---|---|
| Language | Java 21 | all backend modules |
| Framework | Spring Boot 4.0.7 | api-gateway, contract-service, auth-service |
| Framework | Spring Boot 3.5.14 | ai-service (deliberately not aligned; see below) |
| Gateway | Spring Cloud Gateway Server WebMVC, Spring Cloud 2025.1.2 | api-gateway |
| Security | Spring Security, OAuth2 Resource Server | gateway, contract-service, ai-service |
| Identity | Spring Authorization Server | auth-service |
| AI | Spring AI 1.1.0, `spring-ai-starter-model-openai` | ai-service |
| LLM runtime | Ollama on the host, OpenAI-compatible API, default model `hermes3:8b` | ai-service |
| Text extraction | Apache Tika | ai-service |
| Upload validation | Apache Tika content-type detection (magic bytes, not the client's `Content-Type` header) | contract-service |
| Rate limiting | Bucket4j + Caffeine, in-memory (no Redis) | auth-service |
| Database | PostgreSQL 15, Flyway migrations | contract-service, ai-service, auth-service |
| Messaging | Apache Kafka (KRaft, single broker) | contract-service, ai-service |
| Object storage | MinIO, Java SDK 8.6.0 | contract-service, ai-service |
| SPA | React 19, Vite, Tailwind, Recharts, `react-oidc-context` / `oidc-client-ts`, `jspdf` (CSV/PDF export) | procuremind-react |
| Dashboard | Python 3.13, Streamlit, Pandas, Plotly, `uv` | procuremind-ui |
| Templating | Thymeleaf (login page only) | auth-service |
| Containers | Docker Compose | repository root |

The Spring Boot split is intentional: RS256 plus JWKS is version-neutral, so a token minted
by auth-service is validated identically by a Security 7 service and a Security 6 one.

---

## 4. Repository structure

| Path | What it is |
|---|---|
| `pom.xml` | Aggregator for `procuremind-common`, `contract-service`, `ai-service`, `auth-service`. **`api-gateway` is not a module**; build it with `-f api-gateway/pom.xml` |
| `api-gateway/` | Edge service. See `api-gateway/README.md` |
| `contract-service/` | Contract lifecycle. See `contract-service/README.md` |
| `ai-service/` | Document intelligence. See `ai-service/README.md` |
| `auth-service/` | Identity provider. See `auth-service/README.md` |
| `procuremind-common/` | Shared Kafka event records. See `procuremind-common/README.md` |
| `procuremind-react/` | React SPA. See `procuremind-react/README.md` |
| `procuremind-ui/` | Streamlit app. See `procuremind-ui/README.md` |
| `docker-compose.yaml` | Ten services: four Spring apps, two frontends, Postgres, Kafka, Kafka UI, MinIO |
| `docs/architecture.md` | Longer architecture specification |
| `docs/AUTH_IMPLEMENTATION_PLAN.md` | The auth blueprint, phases 0 to 8 |
| `scripts/init-auth-db.sql` | Creates the `procuremind_auth` database on a fresh Postgres volume |
| `scripts/gen-auth-keystore.sh` | Generates a persistent RSA signing keystore for auth-service |
| `DummyContracts/` | Sample contract files for manual testing |

---

## 5. Running the project

Prerequisites: Docker, and [Ollama](https://ollama.com/) running on the host with the model
pulled (`ollama pull hermes3:8b`). Ollama is **not** a Compose service; ai-service reaches it
through `host.docker.internal`.

```bash
cp .env.example .env      # then set AUTH_ADMIN_PASSWORD and STREAMLIT_OIDC_CLIENT_SECRET
docker compose up -d --build
```

Use `--build` after any code change. Compose reuses an existing image otherwise, which
silently runs stale code while every container still reports healthy.

For local (non-Docker) runs the checked-in defaults point at `localhost`, so
`./mvnw spring-boot:run` works per module once Postgres, Kafka and MinIO are up. ai-service
additionally requires `AI_BASE_URL`, `AI_API_KEY` and `AI_MODEL`, which have no defaults.

The bootstrap admin user is only seeded when `AUTH_ADMIN_PASSWORD` is set. ANALYST and VIEWER
accounts are created afterwards from the Admin Dashboard in the React app.

---

## 6. Ports

| Component | Port | Purpose |
|---|---|---|
| procuremind-react | 5173 | React SPA served by nginx. Fixed: OIDC redirect URIs are registered against it |
| procuremind-ui | 8501 | Streamlit dashboard |
| api-gateway | 8080 | Single API ingress |
| contract-service | 8081 | Bound to `127.0.0.1` in Compose; the gateway is the ingress |
| ai-service | 8082 | Bound to `127.0.0.1` in Compose |
| auth-service | 8083 | OIDC issuer, login page, JWKS, `/api/users` |
| PostgreSQL | 5433 | Host port, maps to container 5432 |
| MinIO API | 9000 | Object storage |
| MinIO console | 9001 | Web console |
| Kafka | 9092 | Host listener |
| Kafka UI | 8085 | Container port 8080 |
| Ollama | 11434 | On the host, not in Compose |

---

## 7. Request and data flow

### Login
```
Browser -> React LoginScreen -> auth.signinRedirect()
  -> auth-service /oauth2/authorize (Authorization Code + PKCE)
  -> LoginPageController GET /login -> Thymeleaf login.html
  -> Spring Security form login -> JpaUserDetailsService.loadUserByUsername
  -> redirect back to http://localhost:5173/?code=...
  -> oidc-client-ts exchanges the code at /oauth2/token
  -> access token + id token stored in sessionStorage
```

### Contract upload
```
UploadModal.handleSubmit -> ApiClient.uploadContract
  -> POST {gateway}/api/contracts/upload (multipart, Bearer token)
  -> api-gateway SecurityConfig: POST /api/contracts/upload requires ANALYST or ADMIN
  -> contract-service ContractController.uploadContract (@PreAuthorize ANALYST/ADMIN)
  -> ContractService.processNewContract
       -> FileValidationService.validatePdf -> Apache Tika magic-byte check, before storage
       -> StorageService.uploadFile  -> MinIO, object name "{uuid}_{sanitized filename}"
       -> ContractRepository.save    -> contracts row, status UPLOADED
       -> MDC.put(contractId)        -> every log line for this contract now carries its id
       -> ContractEventProducer.publishContractUploadEvent -> contract.uploaded (after commit,
          carrying the contract id as both the Kafka key and an X-Contract-Id header)
  -> 202 Accepted with ContractResponseDto, or 400 with the real reason for a rejected file type
```

### Indexing
```
ai-service ContractEventListener.handleContractUploaded (topic contract.uploaded)
  -> PdfParsingService.parseAndIndexPdf   : MinIO -> Tika -> page_index_nodes tree
  -> IndexingService.indexContractNodes   : IndexingAgent.summarize per node, saved one by one
  -> ContractEventProducer.publishPageIndexed -> contract.indexed
contract-service ContractEventListener.handleContractIndexed -> status INDEXED
```

### Analysis
```
ai-service ContractEventListener.handleContractIndexed (topic contract.indexed)
  -> AnalysisService.processContract
       -> AnalysisAgent.execute
            stage 1 screen : SectionBatches.byCharacterBudget + SectionScreener.screen per batch
            stage 2 verify : raw text of the most severe findings, read directly from the repository
            stage 3 analyse: one tool-free LLM call -> ContractAnalysisResultDto JSON
       -> ContractMetadataRepository.save, ContractAnalysisRepository.save (cascades AnalysisRisk)
       -> ContractEventProducer.publishAnalysisCompleted -> contract.analyzed
contract-service ContractEventListener.handleContractAnalyzed -> status ANALYZED
```

### Viewing contracts and analysis
```
AppContext.loadData -> ApiClient.getMergedContracts
  -> GET /api/contracts            (contract-service, ContractService.getAllContracts)
  -> GET /api/analysis/{id}        (ai-service, AnalysisQueryService.getAnalysis)
  merged client-side into one table
Dashboard also calls /api/analysis/dashboard-metrics, /financial-exposure, /risks/distribution
Intelligence screen calls /api/analysis/{id}/toc, /{id}/risks, /api/analysis/node/{nodeId}
```

---

## 8. Authentication architecture

**auth-service is the authorization server. api-gateway, contract-service and ai-service are
resource servers.** Nimbus JOSE + JWT is used only as the library underneath Spring
Authorization Server and Spring Security's `NimbusJwtDecoder`. There is no hand-rolled token
issuance anywhere in the repository.

- **Grant**: Authorization Code + PKCE. `procuremind-react` is a public client
  (`ClientAuthenticationMethod.NONE`); `procuremind-streamlit` is confidential
  (`client_secret_basic`). Both require PKCE.
- **Tokens**: RS256 JWT access tokens and OIDC id tokens. Both carry a `roles` claim, added by
  `AuthorizationServerConfig.rolesTokenCustomizer` from the principal's `ROLE_*` authorities.
- **Refresh tokens**: none are issued to the React client. Spring Authorization Server's
  `OAuth2RefreshTokenGenerator` returns `null` for the authorization-code grant when the client
  authenticates with `none`. The SPA renews via a `prompt=none` iframe instead, landing on
  `/silent-renew.html`.
- **Validation**: each resource server builds a `NimbusJwtDecoder` from `jwk-set-uri` and adds a
  `JwtIssuerValidator` for `app.security.issuer-uri`. The two differ on purpose: the key set is
  fetched over the Docker network while the issuer stays the browser-facing value.
- **Roles**: `ADMIN`, `ANALYST`, `VIEWER`, seeded by Flyway `V2__seed_roles.sql`.
  `JwtRolesConverter` maps the `roles` claim to `ROLE_*` authorities in every service.

| Capability | VIEWER | ANALYST | ADMIN |
|---|---|---|---|
| `GET /api/contracts/**`, `GET /api/analysis/**` | yes | yes | yes |
| `POST /api/contracts/upload` | no | yes | yes |
| `POST /api/analysis/chat` | no | yes | yes |
| `/api/users/**`, `/actuator/**` beyond health | no | no | yes |

**Enforcement is on by default.** `app.security.enforce` binds to `${AUTH_ENABLED:true}` in
all three resource servers. Setting `AUTH_ENABLED=false` swaps in a permissive filter chain
and also disables `MethodSecurityConfig`, so the controllers' `@PreAuthorize` rules go inert
too. It is a documented emergency switch, not a normal mode; a presented token is still
validated even then.

**`/login` and `/oauth2/token` are rate-limited**, per-IP (20/minute) and per-identity
(username or OAuth2 client id, 5/minute), via an in-memory Bucket4j + Caffeine filter in
auth-service (`RateLimitFilter`, `auth.rate-limit.*`, no Redis). See `auth-service/README.md`.

Public paths: gateway `/`, `/api`, `/actuator/health*`, `/health/**`; services
`/actuator/health*`, `/v3/api-docs/**`, `/swagger-ui*`.

---

## 9. Async architecture

| Topic | Producer | Consumer | Event type | Purpose |
|---|---|---|---|---|
| `contract.uploaded` | contract-service `ContractEventProducer` | ai-service `ContractEventListener` | `ContractUploadedEvent` | Start parsing and indexing |
| `contract.indexed` | ai-service `ContractEventProducer` | ai-service and contract-service listeners | `PageIndexedEvent` | Trigger analysis; advance status to INDEXED |
| `contract.analyzed` | ai-service `ContractEventProducer` | contract-service `ContractEventListener` | `PageIndexedEvent` | Advance status to ANALYZED |
| `contract.failed` | ai-service `KafkaErrorHandlingConfig` recoverer | contract-service `ContractEventListener` | `PageIndexedEvent` | Mark the contract FAILED after retries are exhausted |
| `contract.uploaded.DLT`, `contract.indexed.DLT`, `contract.analyzed.DLT`, `contract.failed.DLT` | `DeadLetterPublishingRecoverer` | none | original record | Parking for poisoned messages |

Consumer groups: `ai-processing-group` (ai-service), `contract-processing-group`
(contract-service). Group ids are hard-coded in the `@KafkaListener` annotations.

Reliability behaviour worth knowing:

- Listeners **do not** catch exceptions. They log and rethrow so `DefaultErrorHandler` can
  retry (3 attempts) and then dead-letter.
- **Both** services' dead-letter recoverers mark the contract `FAILED` before dead-lettering,
  not just ai-service's. contract-service's `KafkaErrorHandlingConfig` previously only
  dead-lettered a poison message from its own listeners, leaving the contract silently stuck
  at its prior status; its `MarkFailedThenDeadLetterRecoverer` now calls
  `ContractStatusUpdater.markFailedByKey` first (finding #4).
- Both producers defer the send to `afterCommit` when a transaction is active, so a rolled
  back write publishes nothing. Send failures are logged from the future's callback.
- Every producer also stamps the contract id onto the record as an explicit header
  (`X-Contract-Id`, `CorrelationIds.HEADER` in `procuremind-common`), in addition to it already
  being the record key. Every listener restores it into MDC (`contractId`) for the duration of
  processing, falling back to the event's own id if the header is absent (older messages).
  Both services' `logging.pattern.console` includes `%X{contractId:-}`, so one contract's
  entire journey can now be grepped by id across all four services' logs (finding #9).
- `ContractStatusUpdater.shouldAdvance` in contract-service (moved out of
  `ContractEventListener`, which now only delegates) keeps status monotonic
  (`UPLOADED -> INDEXED -> ANALYZED`), with one exception: a FAILED contract can be recovered
  by a later success.
- ai-service's Ollama call now has an explicit 18-minute client-side timeout
  (`spring.http.client.read-timeout`) with Spring AI's own retry layer capped at 1 attempt
  (`spring.ai.retry.max-attempts`, otherwise its default 10x exponential-backoff retry would
  have silently multiplied that timeout) — a hung call now fails within a bounded time instead
  of blocking the consumer thread for up to ~90 minutes (finding #2, revised).
- There is **no transactional outbox**. A commit followed by a broker outage still loses the
  event. Documented as a known gap in `docs/architecture.md`.

---

## 10. Document processing and AI architecture

```
PDF in MinIO
   |  PdfParsingService.parseAndIndexPdf
   v
Apache Tika text extraction (maxStringLength -1)
   |  regex line walk: ARTICLE_PATTERN, SECTION_PATTERN, NOISE_PATTERN
   v
page_index_nodes tree   ROOT (level 1) / ARTICLE (level 2) / SECTION (level 3+)
   |  IndexingService.indexContractNodes -> IndexingAgent.summarize per node
   v
every node gains a title and a 1-2 sentence summary   ---> contract.indexed
   |  AnalysisAgent.execute
   v
stage 1  screen   : all nodes batched by SectionBatches, SectionScreener per batch
stage 2  verify   : raw text of the most severe findings, read from the repository
stage 3  analyse  : one tool-free LLM call -> ContractAnalysisResultDto
   v
contract_analysis + analysis_risks + contract_metadata  ---> contract.analyzed
```

Key properties of the analysis design:

- **Coverage is arithmetic, not a model decision.** `SectionBatches.byCharacterBudget` puts
  every section into exactly one batch, so the number of model calls is
  `ceil(index size / app.analysis.screening-batch-chars) + 1`, known before the first call.
- **No tools in the analysis path.** Spring AI's tool loop in `OpenAiChatModel.internalCall`
  recurses for as long as the model emits tool calls and the framework offers no iteration
  limit, so the analysis path attaches no tools at all.
- **The screener reads lines, not JSON.** `SectionScreener` asks for
  `<id> | <HIGH|MEDIUM|LOW> | <sentence>` because the local 8B model would not reliably return
  a JSON list. Structured JSON is still used for the final `ContractAnalysisResultDto`.
- **The chat assistant is separate** and still uses agentic tool calling through `ChatTools`
  and `ChatAgent`.

---

## 11. MinIO and file storage

| Concern | Detail |
|---|---|
| Bucket | `procuremind-contracts`, a hard-coded constant in **both** `StorageService` and `PdfParsingService` |
| Creation | `StorageService.uploadFile` creates the bucket if it does not exist |
| Object name | `{random UUID}_{original filename}` |
| Writer | contract-service `StorageService.uploadFile` |
| Reader | ai-service `PdfParsingService.parseAndIndexPdf` via `minioClient.getObject` |
| Link to the database | `contracts.minio_object_name` holds the object name, carried to ai-service in `ContractUploadedEvent.minioObjName` |

Changing the bucket name or the key scheme requires changing both services together.

---

## 12. Database overview

Two logical databases in one PostgreSQL instance.

**`procuremind_db`** (owned by ai-service's Flyway migration, written by both services):

```
contracts (contract-service writes)
   id, filename, minio_object_name, vendor_name, status, uploaded_at
      |
      | contracts.id == page_index_nodes.document_id  (by convention, no FK)
      v
page_index_nodes (ai-service)
   id, document_id, parent_node_id, level, node_order, node_type, title, summary, raw_context

contract_analysis (ai-service)          contract_metadata (ai-service)
   id, contract_id (unique), risk_score,   id, contract_id (unique),
   recommendation, status, created_at      contract_type, amount
      |
      | @OneToMany cascade ALL, orphanRemoval
      v
analysis_risks
   id, analysis_id (FK), severity, description
```

`ai-service` owns `V1__init_schema.sql`, which historically also created the `contracts`
table that contract-service writes — that file is checksum-locked and was **not** edited (an
already-applied migration can't be changed without breaking Flyway validation everywhere it
already ran). **contract-service now owns the `contracts` table's DDL going forward**
(`ARCHITECTURE_REVIEW.md` finding #16): it ships its own `V1__contracts_table.sql`
(`CREATE TABLE IF NOT EXISTS`), recorded in its own history table
(`spring.flyway.table: flyway_schema_history_contract`), separate from ai-service's default
`flyway_schema_history`. This wasn't optional — two independently-deployed services sharing
one Flyway history table permanently fail `validate()` on each other's versions, which was
confirmed live against this project's own Postgres before landing the dedicated-table fix.
Both services run `ddl-auto: validate`.

**`procuremind_auth`** (owned by auth-service): `users`, `roles`, `user_roles` (many-to-many,
`@ManyToMany(fetch = EAGER)` on `User.roles`), plus the three Spring Authorization Server
tables from `V3__oauth2_authorization_server_schema.sql`.

---

## 13. How to find things

| I want to... | Start here |
|---|---|
| Add an API endpoint | The relevant controller, then add a matcher in that service's `SecurityConfig` and, if it must be reachable externally, a route in `api-gateway/src/main/resources/application.yaml` |
| Change contract upload | `ContractController.uploadContract` -> `ContractService.processNewContract` -> `StorageService.uploadFile` |
| Change how PDFs are parsed or the hierarchy is built | `PdfParsingService.parseAndIndexPdf`, in particular `ARTICLE_PATTERN`, `SECTION_PATTERN`, `NOISE_PATTERN` |
| Change node summarisation | `IndexingService.indexContractNodes` and `IndexingAgent.summarize` |
| Change how sections are screened for risk | `SectionScreener` and `SectionBatches.byCharacterBudget` |
| Change the final risk analysis or its schema | `AnalysisAgent.execute`, `prompts/contract-analysis.st`, `ContractAnalysisResultDto` |
| Change clause retrieval used by chat | `RetrievalService` and `ChatTools` |
| Change dashboards or read models | `AnalysisQueryService` and `AnalysisController` |
| Change authentication | `auth-service/config/AuthorizationServerConfig` and `DefaultSecurityConfig` |
| Change the login page appearance | `auth-service/src/main/resources/templates/login.html` and `static/css/auth.css` |
| Add or change a role | `auth-service` `V2__seed_roles.sql`, then the matchers in each `SecurityConfig` and the `@PreAuthorize` annotations |
| Change who may call an endpoint | Gateway `SecurityConfig` for the coarse rule, the controller's `@PreAuthorize` for the service-level rule |
| Change the Kafka flow | `ContractEventProducer` and `ContractEventListener` in both services, plus `KafkaErrorHandlingConfig` |
| Change retry or dead-letter behaviour | `KafkaErrorHandlingConfig` in the service that consumes the topic; contract-service's also calls `ContractStatusUpdater.markFailedByKey` before dead-lettering |
| Change correlation-id/MDC propagation | `CorrelationIds` in `procuremind-common`, then each service's `ContractEventProducer` (header) and `ContractEventListener` (MDC restore) |
| Change upload file-type validation | `FileValidationService` (contract-service) |
| Change file storage | `StorageService` (write) and `PdfParsingService` (read). Both hold the bucket constant |
| Change login/token rate limits | `auth-service` `RateLimiterService`, `RateLimitFilter`, `AuthProperties.RateLimit` |
| Change contract/analysis CSV or PDF export | `procuremind-react/src/utils/exportUtils.js` |
| Change the React UI | `procuremind-react/src`, entry `main.jsx` -> `App.jsx` |
| Change the Streamlit UI | `procuremind-ui/main.py` |
| Change user administration | `auth-service` `UserAdminController` / `UserAdminService`, React `components/admin/UserManagement.jsx` |

---

## 14. Known gaps and rough edges

These are properties of the current implementation, recorded so nobody rediscovers them.

- **No transactional outbox.** A database commit followed by a broker failure loses the event.
- **MinIO-orphan-on-failed-DB-save.** `ContractService.processNewContract` uploads to MinIO
  before saving the `Contract` row; if the save then fails, the uploaded object is orphaned
  with no compensating delete (`StorageService` exposes no delete method). Confirmed and
  locked in by a test (`ContractServiceTest.aFailedDbSaveOrphansTheAlreadyUploadedMinioObject`)
  rather than fixed — deliberately scoped as "verify, don't remediate" in this pass.
- **`AnalysisResponseDto.summary` is a derived, computed field, not a stored column.** No
  summary text exists anywhere in the schema beyond `recommendation` (exposed separately), so
  it's computed at read time from the risk score and most severe finding. A genuinely
  model-authored summary would need a stored column and a prompt change.
- **Chat memory is per-process.** `ChatAgent` uses an in-memory 20-message window, so chat
  history is lost on restart and is not shared across instances.
- **`api-gateway` is outside the Maven reactor** and must be built separately.
- **Analysis quality depends on a local 8B model.** Coverage is deterministic; the judgement
  inside each screening batch is not.
- **`procuremind-react/src/api/mockData.js`** exists but its role in the running application is
  not clearly established from the current implementation.

Resolved since the last pass over this document: cross-service schema ownership of the
`contracts` table (contract-service now has its own Flyway migration and history table, see
section 12); no login rate limiting; no upload file-type validation; no correlation-id
propagation; the unbounded Ollama call; the flat-numbered section-heading mis-parse; missing
test coverage on contract-service's write path; the frontend/backend DTO field mismatch; no
export/reporting. Full detail on each, with file references and test evidence, is in
`ARCHITECTURE_REVIEW.md`'s Implementation Log.
