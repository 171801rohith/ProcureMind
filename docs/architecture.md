# ProcureMind — System Architecture Specification

This document serves as the comprehensive architectural specification for **ProcureMind**, an enterprise AI-native procurement contract analysis and risk intelligence platform.

---

## 1. System Overview & Architectural Paradigm

ProcureMind automates the ingestion, hierarchical structure-aware indexing, compliance tracking, and risk scoring of corporate legal contracts (e.g., Master Services Agreements, Statements of Work, NDAs, Terms & Conditions).

The platform is designed around four core architectural principles:
1. **Domain-Driven Design (DDD)**: Strict isolation between contract lifecycle management and AI compute pipelines.
2. **Event-Driven Choreography**: Non-blocking asynchronous message passing powered by Apache Kafka (KRaft).
3. **Agentic AI with Deterministic Tool Calling**: LLM reasoning guided by Spring AI Function Calling to minimize token consumption and eliminate hallucinations.
4. **Command-Query Responsibility Segregation (CQRS) / BFF**: Fast, responsive executive dashboards merging contract operational data with AI risk insights.

```mermaid
flowchart TB
    subgraph Client Layer
        UI["ProcureMind UI\n(Streamlit BFF Dashboard)\n[Port 8501]"]
    end

    subgraph Edge Layer
        GW["API Gateway\n(Spring Cloud Gateway WebMVC)\n[Port 8080]"]
    end

    subgraph Service Mesh
        CS["Contract Service\n(Ingestion & State Machine)\n[Port 8081]"]
        AI["AI Service\n(Parsing, Indexing & Agents)\n[Port 8082]"]
        COMMON["ProcureMind Common\n(Shared Records & Contracts)"]
    end

    subgraph Message Broker
        KAFKA["Apache Kafka Broker (KRaft)\n[Port 9092]"]
        KAFKA_UI["Kafka UI Dashboard\n[Port 8085]"]
    end

    subgraph Data & Storage Layer
        PG[("PostgreSQL 15 Database\nprocuremind_db [Port 5433 / 5432]")]
        MINIO[("MinIO Object Storage\nprocuremind-contracts [Port 9000 / 9001]")]
    end

    subgraph AI Inference Runtime
        OLLAMA["Ollama (local)\nOpenAI-compatible API [Port 11434]"]
    end

    UI -->|HTTP / JSON| GW
    GW -->|Route /api/contracts/**| CS
    GW -->|Route /api/analysis/**| AI
    GW -->|Forward Health Checks| CS
    GW -->|Forward Health Checks| AI

    CS -->|Classpath Dependency| COMMON
    AI -->|Classpath Dependency| COMMON

    CS -->|Store Metadata| PG
    CS -->|Stream PDF Uploads| MINIO
    CS -->|Publish contract.uploaded| KAFKA

    KAFKA -->|Consume contract.uploaded| AI
    AI -->|Read PDF Stream| MINIO
    AI -->|Persist Document Nodes & Risks| PG
    AI -->|Prompt / Function Calling| OLLAMA
    AI -->|Publish contract.indexed| KAFKA
    AI -->|Publish contract.analyzed| KAFKA

    KAFKA -->|Consume contract.indexed / contract.analyzed| CS
    KAFKA_UI -.->|Monitor Topics| KAFKA
```

---

## 2. End-to-End Execution Sequence

The contract processing pipeline flows through three distinct stages: **Ingestion**, **Hierarchical Indexing**, and **Multi-Factor Risk Analysis**.

```mermaid
sequenceDiagram
    autonumber
    actor User as Procurement Officer
    participant UI as ProcureMind UI (8501)
    participant GW as API Gateway (8080)
    participant CS as Contract Service (8081)
    participant MinIO as MinIO Storage (9000)
    participant Kafka as Kafka Broker (9092)
    participant AI as AI Service (8082)
    participant Ollama as Ollama LLM (11434)
    participant DB as PostgreSQL (5433)

    %% STAGE 1: INGESTION
    Note over User,Kafka: Stage 1: Contract Ingestion & Storage
    User->>UI: Upload Contract PDF + Vendor Name
    UI->>GW: POST /api/contracts/upload (multipart/form-data)
    GW->>CS: Route to /api/contracts/upload
    CS->>MinIO: Stream PDF to bucket "procuremind-contracts"
    CS->>DB: INSERT contracts (status: UPLOADED)
    CS->>Kafka: Publish ContractUploadedEvent -> "contract.uploaded"
    CS-->>GW: HTTP 202 Accepted (ContractResponseDto)
    GW-->>UI: Response with contract ID & UPLOADED status

    %% STAGE 2: PARSING & INDEXING
    Note over Kafka,DB: Stage 2: Apache Tika Parsing & Section Indexing
    Kafka->>AI: ContractEventListener consumes "contract.uploaded"
    AI->>MinIO: Stream PDF via PdfParsingService
    AI->>AI: Apache Tika extracts text & parses regex hierarchy
    AI->>DB: Save raw PageIndexNode tree (ROOT, ARTICLE, SECTION)
    
    loop Index Each Section Node
        AI->>Ollama: IndexingAgent prompt with raw section text
        Ollama-->>AI: Returns JSON (title + executive summary)
        AI->>DB: Update PageIndexNode with title & summary
    end
    AI->>Kafka: Publish PageIndexedEvent -> "contract.indexed"

    %% STATE UPDATE 1
    Kafka->>CS: ContractEventListener updates Contract status to "INDEXED"

    %% STAGE 3: AGENTIC RISK ANALYSIS
    Note over Kafka,DB: Stage 3: Tool-Calling Risk Assessment
    Kafka->>AI: ContractEventListener consumes "contract.indexed"
    AI->>Ollama: AnalysisAgent invokes LLM with ContractAnalysisTools
    
    Note over AI,Ollama: Agent Step 1: getContractSummary (Table of Contents)
    Ollama->>AI: Tool Call: getContractSummary(documentId)
    AI->>DB: Query PageIndexNodes
    AI-->>Ollama: Return structured Table of Contents

    Note over AI,Ollama: Agent Step 2: getClauseContent (Targeted Deep Read)
    Ollama->>AI: Tool Call: getClauseContent(nodeId)
    AI->>DB: Query specific clause text
    AI-->>Ollama: Return raw legal clause

    Ollama-->>AI: Returns ContractAnalysisResultDto (riskScore, recommendation, risks)
    AI->>DB: INSERT contract_analysis, contract_metadata, analysis_risks
    AI->>Kafka: Publish PageIndexedEvent -> "contract.analyzed"

    %% STATE UPDATE 2
    Kafka->>CS: ContractEventListener updates Contract status to "ANALYZED"

    %% STAGE 4: DASHBOARD CONSUMPTION
    Note over User,DB: Stage 4: Dashboard Aggregation & Analytics
    User->>UI: View Executive Dashboard
    UI->>GW: GET /api/contracts & GET /api/analysis/{id}
    GW-->>UI: Return aggregated read models for interactive display
```

---

## 3. Bounded Contexts & Domain Architecture

ProcureMind strictly separates business domains into two primary microservices and one shared contract module:

```
ProcureMind/
├── procuremind-common/     # Immutable Event Contracts & Shared DTOs
├── contract-service/       # Contract Lifecycle & Object Storage Bounded Context
├── ai-service/             # Document Intelligence & Agentic AI Bounded Context
├── api-gateway/            # Edge Routing & Health Reverse Proxy
└── procuremind-ui/         # Streamlit BFF Executive Interface
```

### Domain Isolation Rules
1. **Contract Ingestion Isolation**: `contract-service` manages physical files, contract metadata, and upload timestamps. It has **zero AI dependencies** and never performs LLM operations.
2. **AI & Parsing Isolation**: `ai-service` receives asynchronous event triggers containing object keys, streams files directly from MinIO, and persists analysis results. It exposes analytical read APIs and has **zero direct dependency on `contract-service` code**.
3. **Shared Contracts**: Microservices communicate schemas solely through `procuremind-common` Java records.

---

## 4. Asynchronous Event Topology (Kafka)

All cross-service state transitions are orchestrated via Apache Kafka topics using KRaft consensus:

| Kafka Topic | Producer Service | Consumer Service(s) | Payload DTO | Consumer Group | Trigger / Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `contract.uploaded` | `contract-service` | `ai-service` | `ContractUploadedEvent` | `ai-processing-group` | Emitted when a PDF file is saved to MinIO. Triggers Apache Tika parsing and node indexing. |
| `contract.indexed` | `ai-service` | `contract-service`, `ai-service` | `PageIndexedEvent` | `contract-processing-group`, `ai-processing-group` | Emitted when all section nodes have titles and summaries. Triggers `contract-service` status update and `ai-service` risk analysis. |
| `contract.analyzed` | `ai-service` | `contract-service` | `PageIndexedEvent` | `contract-processing-group` | Emitted when `AnalysisAgent` completes risk scoring. Updates contract status to `ANALYZED`. |

### Event Schema Specifications

#### `ContractUploadedEvent` (`com.procuremind.common.dto`)
```java
public record ContractUploadedEvent(
    UUID contractId,
    String filename,
    String minioObjName
) {}
```

#### `PageIndexedEvent` (`com.procuremind.common.dto`)
```java
public record PageIndexedEvent(
    UUID contractId,
    String status
) {}
```

---

## 5. Storage & Database Schema Architecture

ProcureMind utilizes PostgreSQL (`procuremind_db`) managed via Flyway migrations (`V1__init_schema.sql`) and MinIO Object Storage (`procuremind-contracts` bucket).

```mermaid
erDiagram
    contracts ||--o| contract_analysis : "analyzed by"
    contracts ||--o| contract_metadata : "categorized by"
    contracts ||--o{ page_index_nodes : "hierarchical sections"
    contract_analysis ||--o{ analysis_risks : "contains"

    contracts {
        UUID id PK
        VARCHAR filename
        VARCHAR minio_object_name
        VARCHAR vendor_name
        VARCHAR status
        TIMESTAMP uploaded_at
    }

    contract_analysis {
        UUID id PK
        UUID contract_id FK, UK
        DOUBLE risk_score
        VARCHAR recommendation
        VARCHAR status
        TIMESTAMP created_at
    }

    contract_metadata {
        UUID id PK
        UUID contract_id FK, UK
        VARCHAR contract_type
        DOUBLE amount
    }

    analysis_risks {
        UUID id PK
        UUID analysis_id FK
        VARCHAR severity
        TEXT description
    }

    page_index_nodes {
        UUID id PK
        UUID document_id FK
        UUID parent_node_id
        INTEGER level
        INTEGER node_order
        VARCHAR node_type
        TEXT title
        TEXT summary
        TEXT raw_context
    }
```

### Table Definitions & Roles

1. **`contracts`**: Managed by `contract-service`. Tracks uploaded documents, MinIO storage keys, vendor names, and lifecycle states (`UPLOADED`, `INDEXED`, `ANALYZED`).
2. **`page_index_nodes`**: Managed by `ai-service`. Stores the parsed document hierarchy:
   - `level 1`: `ROOT` (Document root).
   - `level 2`: `ARTICLE` (Major article/clause heading).
   - `level 3+`: `SECTION` (Numbered sub-clauses, e.g., `2.1`, `3.4.1`).
   - `raw_context`: Exact legal text parsed from the PDF.
   - `title` & `summary`: AI-generated summaries of the section text.
3. **`contract_analysis`**: Managed by `ai-service`. Stores overall risk score (1.0 to 10.0), executive recommendation, and processing status.
4. **`contract_metadata`**: Extracted contract categorization (e.g., `MSA`, `SOW`, `NDA`) and total monetary commitment amount ($).
5. **`analysis_risks`**: Individual flagged risk entries with severity levels (`HIGH`, `MEDIUM`, `LOW`) and descriptions.

---

## 6. AI Subsystem & Agent Architecture

The AI intelligence subsystem in `ai-service` is built on **Spring AI 1.1.0** and communicates with a **local Ollama server** (`hermes3:8b` by default) via Spring AI's OpenAI-compatible model starter with temperature `0.0`.

```mermaid
flowchart TD
    subgraph Spring AI Subsystem
        direction TB

        subgraph Indexing Engine
            IA["IndexingAgent\n(Structured JSON Extraction)"]
        end

        subgraph Risk Analysis Engine
            AA["AnalysisAgent\n(Function Calling Agent)"]
            CAT["ContractAnalysisTools\n- getContractSummary\n- getClauseContent"]
            AA <--> CAT
        end

        subgraph Conversational Assistant
            CA["ChatAgent\n(MessageWindowChatMemory: 20 msgs)"]
            CT["ChatTools\n- discoverContracts\n- getCachedAnalysis\n- getContractSummary\n- getClauseContent"]
            CA <--> CT
        end

        RET["RetrievalService\n(DB Node & Cache Access)"]
        CAT --> RET
        CT --> RET
    end

    OLLAMA["Ollama (local)\n(hermes3:8b)\n[Port 11434]"]
    IA <--> OLLAMA
    AA <--> OLLAMA
    CA <--> OLLAMA
```

### 1. `IndexingAgent`
- **Purpose**: Iterates over unindexed `PageIndexNode` entries and extracts a concise title (3-5 words) and executive summary (1-2 sentences).
- **Mechanism**: Utilizes Spring AI `ChatClient.entity(NodeSummary.class)` to enforce strict JSON output without markdown fences.

### 2. `AnalysisAgent`
- **Purpose**: Acts as a Senior Enterprise Procurement Officer evaluating financial, operational, and legal liability risks.
- **Workflow**:
  1. Calls `getContractSummary(documentId)` to inspect the Table of Contents.
  2. Calls `getClauseContent(nodeId)` for high-risk clauses (Liability, Indemnification, Termination, Insurance, Governing Law).
  3. Synthesizes findings into `ContractAnalysisResultDto` with an overall score (1.0 to 10.0), recommendation, and granular risk list.
- **Prompt**: `src/main/resources/prompts/contract-analysis.st`
- **Context Efficiency**: Inspecting the Table of Contents before retrieving full clause text reduces token context usage by up to 80% compared to dumping entire contracts into the prompt.

### 3. `ChatAgent`
- **Purpose**: Interactive conversational assistant for procurement officers to query contracts, compare terms, and investigate risk flags.
- **Mechanism**: Configured with `MessageWindowChatMemory` (maintaining 20 conversational turns per `conversationId`) and four deterministic tools:
  - `discoverContracts`: Filters contracts by vendor, document type, minimum risk score.
  - `getCachedAnalysis`: Returns pre-computed risk score and risk list instantly without LLM re-computation.
  - `getContractSummary`: Fetches the hierarchical Table of Contents.
  - `getClauseContent`: Fetches the exact legal clause text for deep reading.
- **Prompt**: `src/main/resources/prompts/chat-assistant.st`

---

### 6.1 Retrieval model — structure-aware, not vector-based

There is no vector store, no embedding model and no similarity search anywhere in this
system, and none is planned. Earlier wording in this repository described the retrieval
layer as "Vectorless RAG" or "Agentic Vector RAG"; both were misleading and have been
replaced by **hierarchical, structure-aware retrieval**.

What actually happens is:

1. `PdfParsingService` turns the extracted text into a tree of `page_index_nodes` using the
   document's own numbering (`ARTICLE ...` at level 2, dotted `1.2.3` sections deeper).
2. `IndexingService` gives every node an LLM-written title and one- or two-sentence summary.
3. `RetrievalService` exposes two deterministic JPA lookups to the model as tools:
   `getContractSummary(documentId)` returns the whole table of contents, and
   `getClauseContent(nodeId)` returns one node's raw text.
4. The model reads the table of contents, chooses the node identifiers it needs, and asks
   for exactly those clauses.

Selection is therefore done by the model over an explicit structure, not by cosine
similarity over an opaque index. For contracts this is a real advantage rather than a
shortcut: retrieval is exact and auditable (a citation is a node id, not a chunk offset),
clause boundaries are respected rather than cut at an arbitrary token count, and there is no
index to rebuild or keep in sync. The cost is that the whole table of contents goes into the
prompt, so the approach is bounded by context window rather than by corpus size. A vector
index would only become necessary for cross-contract search over a corpus too large to
enumerate, which is not a use case this system has.

## 7. Edge Gateway & Network Routing

The **API Gateway** (`api-gateway`) is a Spring Cloud Gateway WebMVC reverse proxy exposing port `8080`.

### Route Mapping Specification

| Inbound Path Predicate | Target Service | Forwarded URL | Filter / Modification | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `/api/contracts`, `/api/contracts/**` | `contract-service` | `http://contract-service:8081` | Direct Proxy | Contract upload, list, status, and details |
| `/api/analysis`, `/api/analysis/**` | `ai-service` | `http://ai-service:8082` | Direct Proxy | Risk analysis, TOC, metrics, chat, comparisons |
| `/health/contract` | `contract-service` | `http://contract-service:8081` | `SetPath=/actuator/health` | Proxied Actuator health probe |
| `/health/ai` | `ai-service` | `http://ai-service:8082` | `SetPath=/actuator/health` | Proxied Actuator health probe |
| `/actuator/health` | `api-gateway` | Local Actuator | Local Handler | Gateway's own health status |

---

## 8. Failure Handling & Idempotency Guarantees

1. **Duplicate Event Handling**: `AnalysisService.processContract` verifies `analysisRepository.existsByContractId(contractId)` before initiating LLM analysis. Duplicate Kafka events do not trigger redundant AI inference.
2. **MinIO Auto-Provisioning**: `StorageService` verifies bucket existence and provisions `procuremind-contracts` on startup if absent.
3. **Resilient Parsing**: `PdfParsingService` cleans noise headers and page numbers using regex matching, handling non-standard PDF formats cleanly.
4. **Database Migration Safety**: Schema tables and unique constraints (`uc_contract_analysis_contractid`, `uc_contract_metadata_contractid`) are managed via version-controlled Flyway scripts (`V1__init_schema.sql`).
5. **Resumable Indexing**: `IndexingService` commits each node's summary as it is produced and works under a wall-clock budget (`app.indexing.max-duration`). A timeout or redelivery resumes from the nodes that still have a null summary instead of restarting the contract, and the budget keeps the consumer thread inside the broker's `max.poll.interval.ms`.
6. **Retry and Dead-Lettering**: Listeners propagate exceptions rather than swallowing them, so `KafkaErrorHandlingConfig`'s `DefaultErrorHandler` applies three attempts with a five-second backoff and then routes the record to `<topic>.DLT`. The same recoverer publishes `contract.failed`, which moves the contract to `FAILED` so a stuck document is visible rather than pinned at `UPLOADED` forever.
7. **Monotonic Status**: `ContractEventListener.shouldAdvance` only ever moves a contract forward through `UPLOADED -> INDEXED -> ANALYZED`, and treats `FAILED` as terminal. A redelivered `contract.indexed` cannot drag an already-analysed contract backwards.
8. **After-Commit Publishing**: Both `ContractEventProducer`s defer the Kafka send to `afterCommit` when a transaction is active, and log the send future's outcome. A rolled-back write therefore publishes nothing, and a failed publish is no longer silent.

### 8.1 Known gap: no transactional outbox

After-commit publishing closes one direction of the dual write (an event for a row that was
never committed) but not the other. If the database commits and the broker is then
unreachable, the event is lost: the contract stays at `UPLOADED` or `INDEXED` and nothing
retries, because the failure happens after the listener has already returned.

Closing that gap properly means a **transactional outbox**: each producer writes the event
into an `outbox_events` table inside the same transaction as the state change, and a
separate relay (a scheduled poller, or Debezium reading the write-ahead log) publishes rows
from that table to Kafka and marks them sent. Consumers already tolerate duplicates through
their `existsBy...` guards and the monotonic status rule, so at-least-once relay delivery is
safe here.

This is deliberately **not implemented yet**. It adds a table, a migration, a relay
component and its own failure modes to two services, and the current exposure is narrow: a
single-broker local deployment where a post-commit broker outage is both rare and
recoverable by replaying the upload. It is recorded here as the next reliability item rather
than half-built. The producer classes point at this section so the gap stays visible at the
code that causes it.
