# ProcureMind — Architecture Review: Findings, Decisions, and Implementation Plan

This document records the findings of a full-repository architecture review (security,
scalability, reliability, maintainability, and service boundaries), the proposed solution for
each, and the finalized decision on how to act on it. It closes with a phased implementation
checklist built from the accepted/revised items only.

Method: every service README and `docs/architecture.md` were read first (this repo's docs are
unusually candid and already self-document several known gaps), then the actual source was
inspected to verify those claims and find additional issues, each backed by a specific
file/line citation. No code has been changed as part of producing this document.

**Status legend:** `[ACCEPTED]` — will be implemented as scoped below. `[REVISED]` — the
original proposed solution was changed based on real constraints (usually the local-Ollama /
no-cloud-LLM budget). `[REJECTED]` — investigated and consciously not a flaw for this system;
documented so it isn't rediscovered as an oversight. `[DEFERRED]` — valid finding, not in
scope for the current implementation pass; kept in the backlog.

---

## P0 — Critical / production blockers

### 1. No multi-tenant or ownership isolation at all

**Flaw:** Any authenticated ANALYST or VIEWER can read every contract, clause, and risk
analysis in the system. `RetrievalService` and `ContractService` perform unscoped lookups by
bare id, and no `owner`/`org`/`team` column exists anywhere in the schema.

**Status: `[REJECTED]`** — This is a single shared workspace by design, not an oversight.
ProcureMind is used by one procurement team where every ANALYST/VIEWER/ADMIN is expected to
see every contract. The RBAC model (VIEWER/ANALYST/ADMIN) governs *what actions* a role can
take, not *which contracts* it can see. No code change. This decision is now the
authoritative answer if this question comes up again — do not add tenant scoping without a
new, explicit product requirement.

### 2. Pipeline has a concurrency ceiling of 1, and a hung LLM call can stall everything for ~90 minutes

**Flaw:** Every Kafka topic is single-partition, no `@KafkaListener` sets `concurrency`, and
there is no client-side timeout on the Ollama call — only a wall-clock check between batches.
With `max.poll.interval.ms` at 30 minutes and 3 delivery attempts, one hung LLM call can block
the entire pipeline for up to ~90 minutes.

**Status: `[REVISED]`** — Multi-partition Kafka concurrency and multi-instance/load-balanced
inference are explicitly **out of scope**: this is a known, accepted limitation of running a
single local Ollama instance with no cloud-LLM budget. In production, this would be revisited
with either load-balanced Ollama instances or a cloud model — not now.

What **is** in scope: add an explicit client-side timeout on the `ChatClient`/Ollama call,
set to **15–20 minutes** (large-contract analysis runs ~10 minutes in practice, so this gives
headroom without changing the existing 30-minute `max.poll.interval.ms` /
3-attempt retry envelope). This converts an indefinite hang into a bounded failure that flows
into the existing retry → dead-letter path, without touching partitioning or scaling.

### 3. No transactional outbox — a post-commit broker outage silently loses events

**Flaw:** Both `ContractEventProducer`s defer publish to `afterCommit`, which prevents "event
without a committed row" but not the reverse: if Postgres commits and Kafka is then
unreachable, the event is lost and the contract is stuck with nothing retrying.

**Status: `[DEFERRED]`** — Correctly scoped as a real gap in `docs/architecture.md` §8.1
already. Revisit only if time permits after Phases 1–4 below; not a showstopper for the
current deployment profile (single local broker, outage is rare and recoverable by
re-uploading). Placed in Backlog/Stretch.

---

## P1 — High impact

### 4. contract-service's dead-letter path never marks a contract FAILED

**Flaw:** ai-service's recoverer publishes `contract.failed` when retries are exhausted;
contract-service's equivalent class only dead-letters, with no status update.

**Status: `[ACCEPTED]`** — Mirror ai-service's recoverer: on DLT in contract-service, flip the
contract's status to `FAILED` so the failure is visible instead of the contract silently
freezing at its prior status. Scoped to Phase 2.

### 5. No login rate limiting or account lockout on auth-service

**Flaw:** No attempt counter, backoff, or lockout exists on `/login` or `/oauth2/token`.

**Status: `[ACCEPTED]`** — Implement with **Bucket4j + Caffeine** as an in-memory cache
directly inside auth-service. **Do not introduce Redis** — a distributed store isn't
warranted for a single auth-service instance, and it would add an operational dependency for
no real benefit at this scale. Rate-limit per-IP and per-username on `/login` and
`/oauth2/token`. Scoped to Phase 3.

### 6. No file-type/content validation on contract upload

**Flaw:** `ContractController`/`StorageService` accept any multipart file with no
extension, magic-byte, or MIME allowlist.

**Status: `[ACCEPTED]`** — Add magic-byte/MIME validation using Apache Tika's content
detection before accepting an upload; reject non-PDF content at upload time. Scoped to
Phase 2.

### 7. No CI pipeline builds or tests the modules together

**Flaw:** `.github/` contains no workflow building the reactor, `api-gateway`, and both
frontends together.

**Status: `[DEFERRED]`** — Priority is getting a real local test suite (JUnit / Spring Test)
in place first (see #8) before automating it in CI. Wiring GitHub Actions is comparatively
low-effort once tests exist, so it's kept in the backlog to revisit opportunistically rather
than blocking the phased plan below.

### 8. contract-service's core business logic has effectively no test coverage

**Flaw:** No test exists for `ContractService`, `StorageService`, or `ContractController` —
the only write path in the system.

**Status: `[ACCEPTED]`** — Add JUnit + Spring Test coverage for `processNewContract`
(including its MinIO-before-DB-row ordering and orphan-on-rollback behavior, currently
unverified) and `StorageService.uploadFile`. Scoped to Phase 3.

### 9. No trace/correlation ID propagation across the pipeline

**Flaw:** No `MDC`, trace id, or distributed tracing exists anywhere; debugging one contract's
journey requires manually grepping four services' logs by UUID.

**Status: `[ACCEPTED]`** — Add MDC propagation of the contract UUID at the point each service
picks up work, and propagate it as a Kafka message header across `contract.uploaded` →
`contract.indexed` → `contract.analyzed`/`contract.failed` so every consumer can restore it
into MDC on receipt. Scoped to Phase 2 (this is what makes the rest of Phase 2 debuggable).

### 10. Non-persistent JWK signing key silently becomes a production footgun

**Flaw:** `JwkKeyConfig` generates an in-memory RSA key when no keystore path is configured;
every restart invalidates every issued token.

**Status: `[ACCEPTED]`** — No compromise on this class of flaw. Add a startup check that
fails fast (or, at minimum, logs a prominent, impossible-to-miss warning) when
`auth.jwk.keystore-path` is unset outside a recognized dev profile, and wire the persistent
PKCS12 keystore (`scripts/gen-auth-keystore.sh` already exists for this) as the deployment
default. Scoped to Phase 1.

---

## P2 — Medium impact

### 11. Frontend renders fields the backend never returns

**Flaw:** `client.js`/`ExposureChart.jsx`/`VendorPortfolio.jsx` read DTO fields
(`amount`, `contractType`, `summary`, `highRiskCount`, `vendorName`, `fileName`) that don't
exist on `AnalysisResponseDto`, `FinancialExposureDto`, or `ContractResponseDto`.

**Status: `[ACCEPTED]`** — Align backend DTOs with what the frontend actually expects (the
underlying data already exists in `contract_metadata`, so this is mostly a projection change),
implemented in the AI-driven frontend/backend pass. Scoped to Phase 4.

### 12. No foreign key constraints across the shared logical schema

**Flaw:** `contract_analysis.contract_id`, `contract_metadata.contract_id`,
`page_index_nodes.document_id` have no FK to `contracts.id`.

**Status: `[DEFERRED]`** — No delete endpoint exists today, so nothing can become orphaned in
practice; all data is retained indefinitely by design. Revisit if/when a delete/archive
feature is ever built (see Additional Features).

### 13. Unsized HikariCP pools and no resource limits under a shared Postgres

**Flaw:** No service configures `spring.datasource.hikari.*`; no `deploy.resources` limits
exist anywhere in Compose.

**Status: `[ACCEPTED]`** — Set explicit, sized connection pools per service and add
memory/CPU limits in `docker-compose.yaml`. Scoped to Phase 1/3 cleanup (bundled with the
schema-boundary work since both touch service configuration).

### 14. Gateway depends on backends via `service_started`, and three services have no healthcheck at all

**Flaw:** No `healthcheck:` block exists for contract-service/ai-service/auth-service;
dependents use `service_started` instead of `service_healthy`.

**Status: `[ACCEPTED]`** — Add `/actuator/health`-based `healthcheck:` blocks to the three
Spring services and switch Compose dependents to `service_healthy`. Scoped to Phase 1.

### 15. Missing indexes on tree-walk and risk-lookup columns

**Flaw:** `page_index_nodes.parent_node_id` and `analysis_risks.analysis_id` have no index.

**Status: `[ACCEPTED]`** — Add a Flyway migration indexing both columns. Scoped to Phase 1.

### 16. ai-service's migration owns contract-service's table

**Flaw:** ai-service's `V1__init_schema.sql` creates the `contracts` table that
contract-service exclusively writes to.

**Status: `[ACCEPTED]`** — Move the `contracts` table DDL into contract-service's own Flyway
migration history so each service owns the tables it writes. Requires care on existing
databases (the migration must be idempotent / use a baseline so it doesn't collide with the
table ai-service already created) — scoped to Phase 3 alongside the other schema-boundary
work.

### 17. No versioning or backup for source-of-truth PDFs in MinIO

**Status: `[DEFERRED]`** — Not necessary at the current deployment scale.

### 18. Unsanitized filename used directly in the MinIO object key

**Flaw:** The object name is built as `UUID + "_" + file.getOriginalFilename()` with no
sanitization.

**Status: `[ACCEPTED]`** — Add a one-line sanitizer on upload:
`filename.replaceAll("[^a-zA-Z0-9._-]", "_")`. Small, low-risk, scoped to Phase 1.

### 19. Contract status is a duplicated magic string with no shared type

**Status: `[DEFERRED]`** — Not a showstopper; lowest priority. Revisit opportunistically, not
part of the phased plan.

---

## P3 — Low impact / polish

### 20. Section-heading regex silently mis-parses flat numbering

**Flaw:** `SECTION_PATTERN` requires at least one dot, so plain `1. Definitions` style
numbering never matches ARTICLE or SECTION and silently falls into the previous node's body
text.

**Status: `[ACCEPTED]`** — Add a fallback pattern for single-level numbered headings (e.g.
`1. Scope`) so this class of contract is no longer silently mis-indexed, plus fixture tests
covering it. Scoped to Phase 2.

### 21–24. Docker non-root users, access-token revocation, duplicated config classes, missing global exception handlers

**Status: `[DEFERRED]`** — All low-priority polish items; can be done last, opportunistically,
after the phased plan below is complete. Not scheduled into a phase.

---

## Additional features worth considering

### Export/reporting (PDF/CSV)

**Status: `[ACCEPTED]`** — Implement in `procuremind-react` (AI-driven frontend build).
Scoped to Phase 4, alongside the DTO alignment work, since both are frontend-facing.

### Notifications (contract.analyzed → email/webhook)

**Status: `[ACCEPTED — lowest priority]`** — Reuse the existing `contract.analyzed` Kafka
topic to fire a simple notification. Explicitly sequenced **after** Phases 1–4 are complete;
placed in Backlog/Stretch.

### Contract lifecycle (delete/archive/re-upload), pipeline visibility dashboard, configurable risk threshold, audit log, chat memory persistence

**Status: `[DEFERRED]`** — All deferred to a future roadmap, out of scope for the current
implementation pass.

---

## Phased Implementation Checklist

Only `[ACCEPTED]`/`[REVISED]` items appear here, grouped as decided. Each item references its
finding number above for full context.

### Phase 1 — Fast Integrity Wins ✅ complete
- [x] **#10** — Persistent JWK keystore as the deployment default; fail-fast/loud warning when
      `auth.jwk.keystore-path` is unset outside dev.
- [x] **#14** — `/actuator/health` healthchecks on contract-service, ai-service, auth-service;
      switch Compose dependents to `service_healthy`.
- [x] **#18** — One-line filename sanitizer on upload (`replaceAll("[^a-zA-Z0-9._-]", "_")`).
- [x] **#15** — Flyway migration indexing `page_index_nodes.parent_node_id` and
      `analysis_risks.analysis_id`.
- [x] **#13** — Explicit HikariCP pool sizes and Compose resource limits (bundled here as
      config-only, low-risk work).

### Phase 2 — Pipeline Stability & Observability ✅ complete
- [x] **#2 (revised)** — Explicit 15–20 minute client-side timeout on the `ChatClient`/Ollama
      call (no partitioning/scaling changes).
- [x] **#4** — contract-service DLT recoverer flips contract status to `FAILED`.
- [x] **#6** — Apache Tika magic-byte/MIME validation on contract upload.
- [x] **#9** — MDC + Kafka header propagation of the contract UUID across all four services.
- [x] **#20** — Fallback regex for single-level numbered section headings (`1. Scope`), with
      fixture tests.

### Phase 3 — Auth & Schema Boundaries ✅ complete
- [x] **#5** — Bucket4j + Caffeine (in-memory, no Redis) rate limiter on `/login` and
      `/oauth2/token` in auth-service.
- [x] **#16** — Move `contracts` table DDL ownership into contract-service's own Flyway
      migration history.
- [x] **#8** — JUnit + Spring Test coverage for `ContractService`, `StorageService`, and the
      upload write path.

### Phase 4 — Frontend & Reporting ✅ complete
- [x] **#11** — Align backend DTOs with frontend expectations (dashboard/exposure/vendor
      fields).
- [x] Export/reporting — PDF/CSV export in `procuremind-react`.

### Backlog / Stretch (not scheduled)
- [ ] Notifications — `contract.analyzed` → email/webhook, only after Phases 1–4 ship.
- [ ] **#3** — Transactional outbox, revisit only if time permits.
- [ ] **#7** — CI pipeline (build/test all modules), once local test suites (#8) exist.
- [ ] **#12** — FK constraints, if/when a delete/archive feature is built.
- [ ] **#17** — MinIO versioning/backup.
- [ ] **#19** — Shared `ContractStatus` enum in `procuremind-common`.
- [ ] **#21–24** — Docker non-root users, token revocation, config de-duplication, global
      exception handlers.
- [ ] Contract lifecycle (delete/archive/re-upload), pipeline visibility dashboard,
      configurable risk threshold, audit log, chat memory persistence.

---

## Implementation Log

This section records what was actually built for Phases 1–4, verified against the current
code rather than against this document's earlier plan (the plan above was written before any
of these phases landed, so it describes intent — this log describes outcome). Every item below
was proven with a real, currently-passing automated test, not just asserted; the final combined
test run is reported at the end of this section.

### Phase 1 — Fast Integrity Wins

Landed earlier, in commit `bce3538` ("feat: land Phase 1 fast integrity wins (#10, #13, #14,
#15, #18)"), and independently re-verified against the current working tree while producing
this log (per the standing rule of never trusting a prior report without checking the live
code):

- **#10** — `auth-service/src/main/java/com/procuremind/auth/config/JwkKeyConfig.java` now
  refuses to start without a persistent PKCS12 keystore unless the `dev` profile is active,
  instead of silently generating a non-persistent RSA key. `scripts/gen-auth-keystore.sh`
  provisions the deployment keystore.
- **#14** — `docker-compose.yaml` gives contract-service, ai-service, and auth-service
  `/actuator/health`-based `healthcheck:` blocks; their dependents wait on `service_healthy`
  instead of `service_started`.
- **#18** — `ContractService.sanitizeFilename` strips everything outside
  `[a-zA-Z0-9._-]` before the filename ever reaches the MinIO object key, the DB row, or the
  Kafka event.
- **#15** — `ai-service/src/main/resources/db/migration/V3__index_parent_node_and_analysis_fk.sql`
  indexes `page_index_nodes.parent_node_id` and `analysis_risks.analysis_id`.
- **#13** — Explicit `spring.datasource.hikari.*` pools per service and `deploy.resources`
  limits in `docker-compose.yaml`, replacing unbounded defaults.

Verified (2026-09-21, this session): `git show --stat bce3538` confirmed every file the commit
message claims; the fail-fast keystore check, the sanitizer, the V3 migration, and the Hikari
config are all present and unchanged in the current tree.

### Phase 2 — Pipeline Stability & Observability

- **#2 (revised) — bounded Ollama timeout.** `ai-service/src/main/resources/application.yaml`
  sets `spring.http.client.connect-timeout: 10s` / `read-timeout: 18m`. This is not a new Java
  class: Spring AI's `OpenAiChatAutoConfiguration` builds its `OpenAiApi` from
  `restClientBuilderProvider.getIfAvailable(RestClient::builder)`, which resolves to Boot's own
  auto-configured `RestClient.Builder` bean — the same bean these properties configure — so no
  code change was needed to reach the actual LLM call. A second, less obvious fix was required
  alongside it: Spring AI's own retry layer (`spring.ai.retry`, default `max-attempts: 10` with
  exponential backoff up to 3 minutes) retries exactly the exception a read-timeout throws
  (`ResourceAccessException`), which would have silently multiplied the 18-minute bound by up to
  10x. `spring.ai.retry.max-attempts: 1` closes that off, leaving Kafka's own 3-attempt/backoff
  envelope as the only retry layer, as originally intended. Proven by
  `ai-service/src/test/java/com/procuremind/ai_service/config/OllamaHttpTimeoutTest.java` (3
  tests): one drives a real `RestClient` against a `ServerSocket` that accepts a connection and
  never responds, asserting the call fails in ~2s under a 2s test-scoped timeout rather than
  hanging; the other two bind against the actual shipped `application.yaml` and assert the real
  18-minute/1-attempt values are what's configured.
- **#4 — contract-service DLT marks the contract FAILED.**
  `contract-service/src/main/java/com/procuremind/contract_service/service/kafka/ContractStatusUpdater.java`
  (new) centralizes the lifecycle-advancement logic (moved out of `ContractEventListener`) and
  adds `markFailedByKey`, tolerant of a missing/non-UUID Kafka record key.
  `KafkaErrorHandlingConfig.MarkFailedThenDeadLetterRecoverer` calls it before dead-lettering, so
  a message that exhausts its retries now leaves the contract visibly `FAILED` instead of stuck
  at its prior status. Proven by `KafkaErrorHandlingConfigTest` (2 tests) and
  `ContractStatusUpdaterTest` (19 tests covering the full lifecycle state machine plus
  `markFailedByKey`'s edge cases).
- **#6 — Apache Tika upload validation.** New
  `contract-service/src/main/java/com/procuremind/contract_service/service/FileValidationService.java`
  sniffs the real magic bytes of an uploaded file via `Tika.detect(...)` (not the
  client-supplied `Content-Type` header, which proves nothing) and rejects anything that isn't
  `application/pdf` with a new `UnsupportedFileTypeException`, wired into
  `ContractService.processNewContract` as the first step (before the file ever reaches MinIO)
  and into `ContractController` as a specific 400 response with the real reason, ahead of the
  generic fallback. Proven by `FileValidationServiceTest` (4 tests, real Tika — no mocking):
  a genuine PDF is accepted; a PNG renamed to `.pdf` is rejected by its magic bytes; a spoofed
  `Content-Type: application/pdf` header on PNG bytes still gets rejected; an empty file is
  rejected.
- **#9 — MDC + Kafka header correlation-id propagation.** New
  `procuremind-common/src/main/java/com/procuremind/common/tracing/CorrelationIds.java` defines
  the shared header name (`X-Contract-Id`) and MDC key (`contractId`). Both services'
  `ContractEventProducer`s now attach the contract UUID as an explicit Kafka header (previously
  only present as the message key); both services' `ContractEventListener`s extract it via
  `@Header(..., required = false)` (falling back to the event's own id for messages published
  before this header existed) and restore it into MDC for the duration of processing, clearing
  it in a `finally`. `ContractService.processNewContract` does the same on the upload path.
  Both services' `application.yaml` gained a `logging.pattern.console` with `%X{contractId:-}`
  so the id actually appears in every log line, not just ones that manually interpolate it.
  Proven by MDC-assertion tests in both services' `ContractEventListenerTest` classes and header
  assertions in both `ContractEventProducerTest` classes.
- **#20 — flat section-numbering fallback.**
  `ai-service/src/main/java/com/procuremind/ai_service/service/PdfParsingService.java` gained
  `FLAT_SECTION_PATTERN`, tried only when the existing dotted `SECTION_PATTERN` fails to match,
  so headings like `"1. Definitions"` (no sub-level) are indexed as their own section instead of
  silently falling into the previous node's body text. Proven by a new fixture test in
  `PdfParsingServiceTest` asserting both a flat-numbered heading and its sibling are correctly
  indexed as level-2 `SECTION` nodes with the right title and body.

### Phase 3 — Auth & Schema Boundaries

- **#5 — Bucket4j + Caffeine rate limiting.** New `auth-service` package
  `security/ratelimit/` (`RateLimiterService`, in-memory `Cache<String, Bucket>` via Caffeine;
  `RateLimitFilter`, an `OncePerRequestFilter` matching `POST /login` and `POST /oauth2/token`)
  rate-limits both per-IP (20/minute, a coarse flood backstop) and per-identity (5/minute per
  username or OAuth2 client id, the primary brute-force defense), configurable via
  `auth.rate-limit.*` with env-var overrides. Wired into both `DefaultSecurityConfig` (owns
  `/login`) and `AuthorizationServerConfig` (owns `/oauth2/token`) as the first filter in each
  chain, sharing one filter instance and one set of Caffeine caches. Deliberately does **not**
  trust `X-Forwarded-For`: `api-gateway` doesn't proxy `/login` or `/oauth2/token`, so trusting a
  client-supplied header here would let an attacker spoof a fresh IP per request and bypass the
  limit entirely — `request.getRemoteAddr()` is used instead, with the tradeoff documented in
  the filter's Javadoc for revisiting if a reverse proxy is ever placed in front of auth-service
  itself. No Redis, per the review's explicit decision. Proven by `RateLimiterServiceTest` (5
  unit tests, no Spring context) and `RateLimitFilterIntegrationTest` (6 MockMvc tests against a
  real Spring context: the identity limit trips independently per username, the IP limit trips
  across usernames sharing one IP, IP-rotation can't bypass the per-identity limit, and
  under-limit traffic is unaffected).
- **#16 — `contracts` table ownership.** ai-service's `V1__init_schema.sql` is
  checksum-locked and was **not edited** — editing an already-applied Flyway migration breaks
  validation on every database that already ran it. Instead, contract-service gained its own
  independent Flyway history: `spring.flyway.table: flyway_schema_history_contract` (a
  dedicated history table, separate from ai-service's default `flyway_schema_history`) plus
  `baseline-on-migrate: true` / `baseline-version: 0`, and a new
  `contract-service/src/main/resources/db/migration/V1__contracts_table.sql` using
  `CREATE TABLE IF NOT EXISTS`. This was a deliberate revision of the original plan (which
  assumed one shared history table with version-number avoidance and `out-of-order: true`):
  that approach was implemented, run against the real local Postgres, and found to fail —
  Flyway's `validate()` cross-checks the *entire* applied-migration history against what's
  locally resolvable, so a shared history table makes each service permanently fail validation
  on the versions the *other* service applied (`FlywayValidateException: Detected applied
  migration not resolved locally: 1, 2, 3`), independent of ordering. A dedicated table per
  service removes the coupling entirely: no shared state, no version-numbering tricks, no
  startup-ordering dependency between the two services. Proven live: the fix was validated by
  actually running `ContractServiceApplicationTests` against the real local Postgres (which
  already had ai-service's V1–V3 applied from prior use) and confirming a clean context load.
- **#8 — test coverage for the upload write path.** New
  `contract-service/src/test/java/.../service/StorageServiceTest.java` (4 tests: bucket created
  only when missing, and only before `putObject`; the returned object key is
  `<uuid>_<sanitizedFilename>`; upload arguments pass through correctly).
  `ContractServiceTest` gained 3 tests proving `processNewContract`'s MinIO-before-DB-row
  ordering, that a failed upload touches neither the DB nor Kafka, and — the one genuinely
  interesting finding — that **a DB save failure after a successful upload orphans the MinIO
  object with no compensating cleanup**, because `StorageService` exposes no delete method.
  This is confirmed as a real, currently-unfixed gap and is now locked in by a passing test
  (`aFailedDbSaveOrphansTheAlreadyUploadedMinioObject`) rather than fixed outright: this finding
  was explicitly scoped as "verify", and a compensating delete is new, unscoped work — it's
  flagged here for a future pass. New `ContractControllerTest` (3 tests,
  `@WebMvcTest`/`@MockitoBean`) covers the actual HTTP contract: 202 with the DTO body on
  success, 400 with the specific validation message for a rejected file type, 400 with the
  generic fallback message for any other failure.

### Phase 4 — Frontend & Reporting

- **#11 — DTO alignment.** `FinancialExposureDto` (ai-service) gained `vendorName`/`fileName`,
  sourced from a genuine same-database join: a new read-only
  `ai-service/src/main/java/com/procuremind/ai_service/entity/ContractRef.java` maps the
  `contracts` table's `id`/`filename`/`vendor_name` columns (no Flyway migration of its own — the
  table already exists; no write path anywhere in ai-service touches it, since contract-service
  remains the sole writer per this document's own #16 decision), joined ad hoc in
  `ContractAnalysisRepository.getFinancialExposureData()` via a `LEFT JOIN` (so a missing
  `contracts` row doesn't drop the record). `AnalysisResponseDto` gained `contractType`/`amount`
  (real, from ai-service's own `ContractMetadata`) and `summary`/`highRiskCount`. **`summary` is
  a derived/computed field, not a stored column** — no summary text exists anywhere in the
  schema beyond `recommendation` (already exposed separately), so it's computed at read time
  from the risk score and most severe finding (`"Risk score X.X/10. Top risk (SEVERITY):
  <description>"`); this is a real limitation worth keeping in mind if a genuinely
  model-authored summary is wanted later; it would need a stored column and a prompt change,
  not a DTO change. Proven by `AnalysisQueryServiceTest` (6 unit tests) and
  `ContractAnalysisRepositoryFinancialExposureTest` (2 tests against the real local Postgres,
  seeding a `contracts` row via raw JDBC — never through `ContractRefRepository`, keeping the
  read-only invariant even in tests — proving the join both works and tolerates a missing row).
- **Export/reporting.** New `procuremind-react/src/utils/exportUtils.js`:
  `exportContractsToCsv` (hand-rolled RFC-4180 escaping, no new dependency, wired into
  `RecentContractsTable.jsx` as an "Export CSV" button exporting the currently filtered rows)
  and `exportContractAnalysisToPdf` (via `jspdf`/`jspdf-autotable`, wired into
  `IntelligenceScreen.jsx` as an "Export PDF" button producing a one-page report — vendor,
  file, type, status, value, risk score, recommendation, summary, and a risks table — for the
  currently selected contract). Both buttons disable themselves with an explanatory tooltip
  when there's nothing to export. Verified end to end, not just read: a real Vite dev server was
  driven with headless Chromium (Playwright) against mocked API fixtures shaped exactly like
  `ApiClient`'s documented responses (the real backend wasn't running in that session); both
  buttons were actually clicked, the downloaded CSV and PDF files were read back and checked
  (correct RFC-4180 quoting including an embedded comma; the PDF's report layout and risk table
  rendered correctly), the empty-state/disabled-button paths were exercised, and zero browser
  console errors were observed throughout. Because `contractType`/`amount`/`summary` depend on
  the backend fields #11 aligns above, both export paths use `?? fallback` defaults so they
  degrade gracefully rather than breaking regardless of backend rollout order.

### Final verification

All three Spring Boot modules were built and tested together in a single Maven invocation
(`./mvnw -pl contract-service,ai-service,auth-service test`) against a real local
Postgres/Kafka/MinIO stack, confirming the four independent workstreams above integrate
cleanly:

```
contract-service:  65 tests, 0 failures, 0 errors
ai-service:        97 tests, 0 failures, 0 errors
auth-service:      46 tests, 0 failures, 0 errors
-----------------------------------------------
Total:            208 tests, 0 failures, 0 errors — BUILD SUCCESS
```

Known, deliberately-not-fixed gaps carried forward from this pass (none silently dropped):
the MinIO-orphan-on-failed-save behavior in `ContractService` (#8, above), and the derived
(not stored) `summary` field (#11, above). Both are backed by a test that documents the actual
current behavior rather than an assumption about it.
