# ProcureMind — Executive Dashboard UI (`procuremind-ui`)

**ProcureMind UI** is an enterprise-grade SaaS web application built with **Python 3.13**, **Streamlit**, **Pandas**, **Plotly**, and **python-dotenv**. It serves as the primary user interface for corporate procurement officers, legal teams, and contract managers to monitor ingested agreements, analyze real-time financial risk exposure, explore contract section trees (TOC), and converse with an agentic AI assistant.

---

## 1. System Architecture & BFF Integration

In the ProcureMind microservices topology, `procuremind-ui` operates as a **Backend-For-Frontend (BFF)** client layer. It reads microservice gateway configurations from environment variables (`.env`) and consumes RESTful read models from the **API Gateway** (`http://localhost:8080`).

```mermaid
flowchart TD
    User([👤 Procurement / Legal Officer])

    subgraph UI Layer ["procuremind-ui (Port 8501)"]
        ST["Streamlit Reactive Engine\n(main.py)"]
        CQRS["CQRS In-Memory Data Merger"]
    end

    subgraph Edge Layer ["API Gateway / Edge Router"]
        GW["API Gateway\n(Port 8080)"]
    end

    subgraph Microservices Layer ["Backend Services"]
        CS["Contract Service\n(Port 8081)\n/api/contracts"]
        AI["AI Intelligence Service\n(Port 8082)\n/api/analysis/*"]
    end

    User -->|Browser Session| ST
    ST <--> CQRS
    CQRS -->|GET /api/contracts| GW
    CQRS -->|GET /api/analysis/{id}| GW
    ST -->|POST /api/contracts/upload| GW
    ST -->|POST /api/analysis/chat| GW
    GW --> CS
    GW --> AI
```

---

## 2. Structure

```
procuremind-ui/
├── .env                      # Microservices gateway endpoint configuration
├── .python-version           # Python version pin (3.13)
├── Dockerfile                # Containerized UI image build definition
├── pyproject.toml            # Project metadata & Python dependencies
├── uv.lock                   # Reproducible lockfile for uv package manager
├── README.md                 # Module documentation
└── main.py                   # Streamlit application entry point & UI rendering logic
```

---

## 3. Core Modules & Features

### 1. Executive BI Dashboard
* **Executive KPI Cards**: Aggregates metrics (`/api/analysis/dashboard-metrics`): Total Ingested Contracts, High-Risk Contracts, and Average Risk Score.
* **Financial Exposure Scatter Plot**: Interactive Plotly bubble chart plotting Contracts by Risk Score (1-10) vs Amount ($) using `/api/analysis/financial-exposure`.
* **Risk Severity Donut Chart**: Severity breakdown (High = Red, Medium = Amber, Low = Emerald) using `/api/analysis/risks/distribution`.
* **Master Contracts Data Grid**: Sortable table merging `/api/contracts` metadata with `/api/analysis/{contractId}` intelligence.

### 2. Contract Ingestion Pipeline
* **Drag-and-Drop Uploader**: Accepts `.pdf` agreements.
* **Metadata Processing**: Inputs `vendorName`, Contract Type, and Contract Value ($).
* **State Machine Polling**: POSTs `multipart/form-data` to `/api/contracts/upload` and polls `/api/contracts/{id}/status` through lifecycle state transitions (`UPLOADED` ➔ `INDEXED` ➔ `ANALYZED`).

### 3. Document Intelligence Deep Dive
* **Hierarchical Structure (TOC)**: Collapsible section tree fetched from `/api/analysis/{contractId}/toc`.
* **Legal Reading Pane**: Displays exact legal text and AI summaries from `/api/analysis/node/{nodeId}`.
* **Identified Risks Panel**: Side panel listing flagged risks mapped from `/api/analysis/{contractId}/risks` with severity badges (`HIGH`, `MEDIUM`, `LOW`).

### 4. Vendor Risk Portfolio
* **Aggregated Exposure Matrix**: Groups contract commitments, average risk scores, and high-risk counts by vendor entity.

### 5. Agentic Chat Assistant
* **Session Management**: Automatically provisions a persistent UUID `conversationId` per user session.
* **Conversational AI API**: POSTs to `/api/analysis/chat` with `{ userMessage, conversationId }`.
* **Transparent Agent Trace**: Renders markdown responses and an expandable `agentTrace` accordion detailing internal reasoning steps.

---

## 4. API Endpoint Reference Matrix

| Feature Module | Endpoint Path | HTTP Verb | Forwarded Target | Description |
| :--- | :--- | :---: | :--- | :--- |
| **Contracts Master** | `/api/contracts` | `GET` | Contract Service (`:8081`) | Retrieves all ingested contract metadata records |
| **Dashboard Metrics** | `/api/analysis/dashboard-metrics` | `GET` | AI Service (`:8082`) | Returns KPI counts (`totalAnalyzed`, `highRiskCount`, `averageRiskScore`) |
| **Financial Exposure** | `/api/analysis/financial-exposure` | `GET` | AI Service (`:8082`) | Retrieves risk score vs financial commitment plot points |
| **Risk Distribution** | `/api/analysis/risks/distribution` | `GET` | AI Service (`:8082`) | Severity distribution count breakdown |
| **Contract Type Dist.**| `/api/analysis/contracts/type-distribution`| `GET` | AI Service (`:8082`) | Distribution of contracts by type (MSA, SOW, etc.) |
| **Single Analysis** | `/api/analysis/{contractId}` | `GET` | AI Service (`:8082`) | Fetches AI risk score, summary, and recommendation |
| **Contract TOC** | `/api/analysis/{contractId}/toc` | `GET` | AI Service (`:8082`) | Hierarchical Table of Contents tree nodes |
| **Node Detail** | `/api/analysis/node/{nodeId}` | `GET` | AI Service (`:8082`) | Exact legal text and AI clause summary |
| **Identified Risks** | `/api/analysis/{contractId}/risks` | `GET` | AI Service (`:8082`) | Categorized risk flags for a specific contract |
| **Contract Upload** | `/api/contracts/upload` | `POST` | Contract Service (`:8081`) | Ingests PDF file with vendor metadata (`multipart/form-data`) |
| **Status Polling** | `/api/contracts/{id}/status` | `GET` | Contract Service (`:8081`) | Checks ingestion status (`UPLOADED`, `INDEXED`, `ANALYZED`) |
| **Agentic Chat** | `/api/analysis/chat` | `POST` | AI Service (`:8082`) | Interactive assistant query returning answer and `agentTrace` |

---

## 5. Environment Configuration (`.env`)

Create a `.env` file in `procuremind-ui/`:

```env
# API Gateway Base URL
GATEWAY_URL=http://localhost:8080
API_TIMEOUT=120
```

---

## 6. Setup & Execution Guide

### Prerequisites
* **Python 3.13+** installed.
* **uv** (Recommended) or standard `pip` / `venv`.
* Backend microservices running via `docker-compose` or local Java processes.

### Option A: Running with `uv` (Recommended)

```bash
cd procuremind-ui

# Install dependencies and sync environment
uv sync

# Launch ProcureMind Streamlit UI
uv run streamlit run main.py
```

### Option B: Running with standard `pip` & `venv`

```bash
cd procuremind-ui

# Create virtual environment
python -m venv .venv

# Activate environment (Windows PowerShell)
.venv\Scripts\Activate.ps1

# Activate environment (Linux / macOS)
source .venv/bin/activate

# Install dependencies
pip install -e .

# Run Streamlit
streamlit run main.py
```

### Accessing the Dashboard
Open your browser and navigate to `http://localhost:8501`.

---

## 7. Troubleshooting

1. **"Unable to connect to API Gateway" warnings**:
   - Verify the API Gateway is running on `http://localhost:8080`.
   - Check that `GATEWAY_URL` in `.env` matches the active gateway host and port.
2. **Chat request timeout**:
   - For complex LLM queries on CPU-only machines, increase `API_TIMEOUT` in `.env` (e.g. `API_TIMEOUT=180`).
