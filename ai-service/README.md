# ai-service

## Purpose

The document intelligence bounded context. It turns a stored PDF into a hierarchical clause
index, writes an AI summary for every section, produces a risk analysis for the whole
contract, and serves the read models the dashboards use.

It never receives file uploads over HTTP. Work arrives over Kafka.

## Responsibilities

- Extract text from the stored PDF and build a `page_index_nodes` tree
- Summarise every node with the LLM
- Screen every section for risk and produce a `ContractAnalysisResultDto`
- Persist `contract_analysis`, `analysis_risks` and `contract_metadata`
- Publish `contract.indexed`, `contract.analyzed` and `contract.failed`
- Serve analysis, table of contents, clause and dashboard read models
- Host the conversational assistant

## Technology

Java 21, **Spring Boot 3.5.14** (the only module not on Boot 4), Spring AI 1.1.0 with
`spring-ai-starter-model-openai`, Apache Tika, Spring Data JPA, Spring Kafka, Spring Security
OAuth2 Resource Server, MinIO SDK, PostgreSQL, Flyway, Lombok.

The LLM is a local **Ollama** server reached through its OpenAI-compatible API. Configuration
comes from `AI_BASE_URL`, `AI_API_KEY` and `AI_MODEL`, none of which have defaults, so the
service will not start without them.

## Entry point

`src/main/java/com/procuremind/ai_service/AiServiceApplication.java`, a plain
`@SpringBootApplication`. Port 8082. On start, Flyway applies
`db/migration/V1__init_schema.sql` and `V2__page_index_node_indexes.sql` to `procuremind_db`.
**V1 also creates the `contracts` table that contract-service owns**, so ai-service must
migrate before either service can pass `ddl-auto: validate`.

## Package structure

```
com/procuremind/ai_service/
├── agent/          LLM-facing classes, one per job
│   └── tools/      @Tool methods exposed to the chat model
├── service/        pipeline stages and read models
│   └── kafka/      producer and listeners
├── Repository/     Spring Data interfaces (note the capital R)
├── controller/     REST read surface and chat
├── entity/         JPA entities
├── dto/            API and LLM payload shapes
├── config/         MinIO client, Kafka error handling
└── security/       resource-server configuration
```

`Repository` is capitalised, unlike contract-service's `repository`. That is existing
inconsistency, not a convention.

| Package | Belongs here | Does not belong here |
|---|---|---|
| `agent` | Prompt construction, `ChatClient` calls, output parsing | Persistence, Kafka |
| `agent/tools` | `@Tool` methods, thin delegation to `RetrievalService` | Business rules |
| `service` | Pipeline orchestration, transactions, queries | HTTP concerns |
| `service/kafka` | Event publishing and consumption | Analysis logic |
| `controller` | Read endpoints and chat | Any write to the pipeline |

---

## The pipeline

```
contract.uploaded
   │
   ├─ PdfParsingService.parseAndIndexPdf   MinIO → Tika → page_index_nodes tree
   └─ IndexingService.indexContractNodes   IndexingAgent.summarize per node
                                           → contract.indexed
contract.indexed
   └─ AnalysisService.processContract
        └─ AnalysisAgent.execute
             stage 1  SectionBatches + SectionScreener   (every section, no tools)
             stage 2  raw text of the most severe findings, straight from the repository
             stage 3  one tool-free LLM call → ContractAnalysisResultDto
        → contract_analysis + analysis_risks + contract_metadata
        → contract.analyzed
```

---

## Classes

### PdfParsingService

**Location:** `service/PdfParsingService.java`
**Used by:** `ContractEventListener.handleContractUploaded`
**Depends on:** `MinioClient`, `PageIndexNodeRepository`, Apache Tika

| Method | Purpose | Important behaviour |
|---|---|---|
| `parseAndIndexPdf(UUID documentId, String minioObjName)` | Build the section tree | `@Transactional`. Idempotency guard: returns immediately if `existsByDocumentId`. Streams the object from bucket `procuremind-contracts`, extracts with `tika.parseToString` (`maxStringLength(-1)`), then walks the text line by line. Wraps any failure in `RuntimeException("PDF Parsing failed")` |

Structure detection is pure regex over lines shorter than 150 characters:

| Pattern | Produces |
|---|---|
| `ARTICLE_PATTERN` `^ARTICLE\s+([IVXLCDM]+|\d+)...` | `ARTICLE` node at level 2, parented to ROOT |
| `SECTION_PATTERN` `^(\d+(?:\.\d+)+)...` | `SECTION` node at level `dots + 1`, parented to the nearest node one level up |
| `NOISE_PATTERN` page numbers | Dropped |

Everything that is not a heading is buffered into the current node's `rawContext`. A ROOT node
is always created first with title `"Document Root"`.

**Consequence worth knowing:** a document whose headings match neither pattern collapses into a
single ROOT node holding the entire text. That is a real case in this repository, not a
hypothetical.

### IndexingService

**Location:** `service/IndexingService.java`

| Method | Purpose | Important behaviour |
|---|---|---|
| `indexContractNodes(UUID documentId)` | Summarise every unsummarised node | Deliberately **not** `@Transactional`: a single transaction across dozens of LLM calls would hold a connection for minutes and lose all progress on failure. Each node is saved individually, so a retry resumes from `findByDocumentIdAndSummaryIsNull` |

Bounds and failure rules:

- Wall-clock budget `app.indexing.max-duration` (default 8m). Exceeding it throws
  `IndexingIncompleteException`, which is retryable and resumes.
- `app.indexing.max-node-chars` (default 12000) caps the text sent for one node.
- A single failing node is logged and skipped. If **every** node fails, it throws rather than
  publishing an empty index.
- Publishes `contract.indexed` on success, and also when there was nothing left to do.

Progress logs use the `[INDEXING]` and `[SUMMARIZING]` prefixes.

### AnalysisAgent

**Location:** `agent/AnalysisAgent.java`
**Used by:** `AnalysisService`
**Depends on:** `ChatClient.Builder`, `PageIndexNodeRepository`, `SectionScreener`,
`BeanOutputConverter<ContractAnalysisResultDto>`

| Method | Purpose | Important behaviour |
|---|---|---|
| `execute(UUID contractId)` | Produce the analysis | Three stages, described below. Throws `InvalidAnalysisOutputException` or `AnalysisIncompleteException` |
| `requireJsonObject(UUID, String)` | Enforce the output contract | Normalises a markdown-fenced object; rejects anything that is not a JSON object before parsing is attempted |

Stage 1 screens **every** section. `SectionBatches.byCharacterBudget` splits the index by
`app.analysis.screening-batch-chars` (default 4000), so the number of model calls is
`ceil(index size / budget) + 1`, known before the first call. A batch whose reply cannot be
read keeps its summaries as evidence, so an unreadable reply costs detail but never coverage.
Exceeding `app.analysis.max-duration` throws rather than scoring a partially read document.

Stage 2 reads raw text for the highest-severity findings, capped by
`max-verified-sections` (8) and `max-verified-section-chars` (4000), within
`max-evidence-chars` (16000). No model call.

Stage 3 is a single call on a `ChatClient` built **with no tools at all**. That is structural:
Spring AI 1.1.0 recurses in `OpenAiChatModel.internalCall` for as long as the model emits tool
calls and offers no iteration limit, so a tool-free call cannot loop. It also removes the
failure where the model continued the retrieved clause text instead of answering.

A parsed result with a null `riskScore` is rejected.

### SectionScreener

**Location:** `agent/SectionScreener.java`

| Method | Purpose | Important behaviour |
|---|---|---|
| `screen(UUID, List<PageIndexNode>, int number, int total)` | Judge one batch | Returns `Optional.empty()` when the reply could not be read, which the caller treats differently from "no risk found" |

It asks for **lines**, not JSON: `<id> | <HIGH|MEDIUM|LOW> | <sentence>`. The local 8B model
would not reliably produce a JSON list for this pass. Lines are extracted with a regex even
when the model wraps them in prose, findings naming ids outside the batch are dropped, and an
explicit `NONE` is a valid "nothing risky here" answer.

### AnalysisService

**Location:** `service/AnalysisService.java`

| Method | Purpose | Important behaviour |
|---|---|---|
| `processContract(UUID contractId)` | Orchestrate analysis and persistence | `@Transactional`. Idempotency guard on `existsByContractId`; when analysis already exists it **re-publishes** `contract.analyzed` rather than returning silently, so a contract stuck at FAILED can still converge. Saves metadata and analysis, with `AnalysisRisk` cascaded from `ContractAnalysis` |

### RetrievalService

**Location:** `service/RetrievalService.java`
**Used by:** `ChatTools` (the chat assistant)

| Method | Purpose | Important behaviour |
|---|---|---|
| `getContractSummary(String documentId)` | Table of contents: id, title, summary per node | Validates the id and returns guidance instead of throwing; explicit message when the document has no nodes; bounded by `app.retrieval.max-tool-response-chars` (12000), truncating on a line boundary so a partial node id is never offered |
| `getClauseContent(String nodeId)` | Raw text of one node | Same id validation; explicit message when the section has no text or is not found; bounded the same way |
| `getCachedAnalysis(String contractId)` | Stored risk score, recommendation and risks as text | Id-validated |
| `discoverContracts(SearchCriteria)` | Metadata search across contracts | Null-safe on contract type and risk score; a contract with no analysis is listed as "not analysed yet" |

Invalid ids return a corrective sentence rather than throwing. Throwing turned into a tool
error that the model answered by repeating the same call.

### AnalysisQueryService

**Location:** `service/AnalysisQueryService.java`. The CQRS read side behind
`AnalysisController`.

| Method | Serves |
|---|---|
| `getAnalysis(UUID)` | `GET /api/analysis/{contractId}` |
| `getRisks(UUID)` | `GET /api/analysis/{contractId}/risks` |
| `getTableOfContent(UUID)` | `GET /api/analysis/{contractId}/toc` |
| `getClauseContent(UUID)` | `GET /api/analysis/node/{nodeId}` |
| `getDashboardMetrics()` | `GET /api/analysis/dashboard-metrics` |
| `getTopRiskyClauses()` | `GET /api/analysis/risks/top` |
| `getFinancialExposure()` | `GET /api/analysis/financial-exposure` |
| `getRiskDistribution()` | `GET /api/analysis/risks/distribution` |
| `getContractTypeDistribution()` | `GET /api/analysis/contracts/type-distribution` |
| `compareContracts(List<UUID>)` | `GET /api/analysis/compare` |

### ChatAgent and ConversationService

`ChatAgent.execute(String userMessage, String conversationId)` calls a `ChatClient` built with
the `prompts/chat-assistant.st` system prompt, `ChatTools` as tools, and a
`MessageWindowChatMemory` of 20 messages keyed by `conversationId`.

**The memory is in-process.** History is lost on restart and is not shared across instances.

`ConversationService.handleChat` wraps it for the controller.

### ContractEventListener and ContractEventProducer

**Location:** `service/kafka/`
**Group:** `ai-processing-group`

| Method | Topic | Behaviour |
|---|---|---|
| `handleContractUploaded(ContractUploadedEvent)` | `contract.uploaded` | Parse then index. Logs and rethrows |
| `handleContractIndexed(PageIndexedEvent)` | `contract.indexed` | Analyse. Logs and rethrows |
| `publishPageIndexed(UUID)` | `contract.indexed` | Deferred to after commit when in a transaction |
| `publishAnalysisCompleted(UUID)` | `contract.analyzed` | Same |
| `publishProcessingFailed(UUID)` | `contract.failed` | Called by the DLT recoverer |

Listeners log with the `[KAFKA]` prefix and rethrow so the error handler can act.

---

## Repositories

| Repository | Entity | Notable queries |
|---|---|---|
| `PageIndexNodeRepository` | `PageIndexNode` | `findByDocumentIdOrderByNodeOrderAsc`, `findByDocumentIdAndSummaryIsNull` (drives resumable indexing), `existsByDocumentId` (parsing idempotency) |
| `ContractAnalysisRepository` | `ContractAnalysis` | `findByContractId`, `existsByContractId`, `countHighRiskContracts()` (`riskScore >= 6.0`, a hard-coded threshold), `getAverageRiskScore()`, a financial-exposure projection joining metadata |
| `AnalysisRiskRepository` | `AnalysisRisk` | Risk frequency grouping, `getRiskSeverityDistribution()` grouped by severity |
| `ContractMetadataRepository` | `ContractMetadata` | `getContractTypeDistribution()` |

---

## Entities

| Entity | Table | Notes |
|---|---|---|
| `PageIndexNode` | `page_index_nodes` | `documentId`, `parentNodeId`, `level`, `nodeOrder`, `nodeType`, `title`, `summary`, `rawContext`. The hierarchy is expressed by `parentNodeId`, not a JPA relationship |
| `ContractAnalysis` | `contract_analysis` | `contractId` unique, `riskScore`, `recommendation`, `status`, `@OneToMany` to `AnalysisRisk` with cascade ALL and orphan removal |
| `AnalysisRisk` | `analysis_risks` | `severity` and `description` only, `@ManyToOne` back to the analysis. **No category, clause link or per-risk score** |
| `ContractMetadata` | `contract_metadata` | `contractId` unique, `contractType`, `amount` |
| `NodeType` | enum | `ROOT`, `ARTICLE`, `SECTION` |

---

## Configuration

| Class or file | What it enables |
|---|---|
| `config/MinioConfig` | `MinioClient` bean |
| `config/KafkaErrorHandlingConfig` | `DefaultErrorHandler` with `FixedBackOff(5s, 2)` (3 attempts); recoverer dead-letters to `<topic>.DLT` **and** publishes `contract.failed` so the contract is visibly failed. Declares `contract.failed` and the two DLT topics |
| `security/SecurityConfig` | Two chains under `app.security.enforce`; `/actuator/**` is ADMIN only |
| `security/MethodSecurityConfig` | `@EnableMethodSecurity` behind the same property |
| `security/JwtDecoderConfig` | JWKS decoder plus issuer validation |
| `prompts/contract-analysis.st` | System prompt for stage 3. States that evidence must never be echoed back |
| `prompts/chat-assistant.st` | System prompt for `ChatAgent` |

Consumer tuning in `application.yaml`: `max.poll.interval.ms: 1800000` (30 minutes) and
`max.poll.records: 1`, because the pipeline runs LLM calls on the consumer thread. The
indexing and analysis time budgets are sized so three attempts stay inside that window.

---

## Endpoints

All of `/api/analysis/**` carries a class-level
`@PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")`. `POST /api/analysis/chat` on
`ChatController` additionally requires `hasAnyRole('ANALYST','ADMIN')`.

---

## Known gaps

- `ConversationService` returns a hard-coded `agentTrace` and an always-empty `citations` list.
- `ClauseContentDto` and `EnrichedClauseDto`-style DTOs are not all consumed by the frontends.
- The React `RiskSidePanel` renders `risk.category` and `risk.recommendation`, which
  `AnalysisRisk` does not have.
- Chat memory does not survive a restart.
