# ProcureMind — Executive Dashboard UI (`procuremind-ui`)

![Python Version](https://img.shields.io/badge/python-3.13%2B-blue?style=for-the-badge&logo=python)
![Framework](https://img.shields.io/badge/framework-Streamlit%201.40%2B-red?style=for-the-badge&logo=streamlit)
![Architecture](https://img.shields.io/badge/architecture-BFF%20%2F%20CQRS-purple?style=for-the-badge)
![License](https://img.shields.io/badge/license-Enterprise%20SaaS-emerald?style=for-the-badge)

**ProcureMind UI** is an enterprise-grade SaaS web application built with **Python 3.13**, **Streamlit**, **Pandas**, **Plotly**, and **python-dotenv**. It serves as the primary user interface for corporate procurement officers, legal teams, and contract managers to monitor ingested agreements, analyze real-time financial risk exposure, explore contract section trees (TOC), and converse with a transparent AI agent.

---

## 🏗️ System Architecture & BFF Integration

In the ProcureMind microservices topology, `procuremind-ui` operates as a **Backend-For-Frontend (BFF)** client layer. It reads microservice configurations from environment variables (`.env`) and consumes RESTful read models from the **API Gateway**, **Contract Service**, and **AI Service**.

```mermaid
flowchart TD
    User([👤 Procurement / Legal Officer])

    subgraph UI Layer ["procuremind-ui (Port 8501)"]
        ST["Streamlit Reactive Engine\n(main.py)"]
        Cache["@st.cache_data CQRS Merger"]
    end

    subgraph Edge Layer ["API Gateway / Edge Router"]
        GW["API Gateway\n(Port 8080)"]
    end

    subgraph Microservices Layer ["Backend Services"]
        CS["Contract Service\n(Port 8081)\n/api/contracts"]
        AI["AI Intelligence Service\n(Port 8082)\n/api/analysis/*"]
    end

    User -->|Browser Session| ST
    ST <--> Cache
    Cache -->|GET /api/contracts| GW
    Cache -->|GET /api/analysis/{id}| GW
    ST -->|POST /api/contracts/upload| CS
    ST -->|POST /api/analysis/chat| AI
    GW --> CS
    GW --> AI
```

---

## ⚡ Core Modules & Features

### 1. 📊 Executive BI Dashboard (`/`)
* **Executive KPI Cards**: Aggregates metrics (`/api/analysis/dashboard-metrics`): Total Ingested Contracts, High-Risk Contracts, Average Risk Score, and Total Portfolio Exposure.
* **Financial Exposure Scatter Plot**: Interactive Plotly bubble chart plotting Contracts by Risk Score (1-10) vs Amount ($) using `/api/analysis/financial-exposure`.
* **Risk Severity Donut Chart**: Severity breakdown (High = Red, Medium = Amber, Low = Emerald) using `/api/analysis/risks/distribution`.
* **Master Contracts Data Grid**: Sortable table merging `/api/contracts` metadata with `/api/analysis/{contractId}` intelligence.

### 2. 📤 Contract Ingestion Pipeline
* **Drag-and-Drop Uploader**: Accepts `.pdf` agreements.
* **Metadata Processing**: Inputs `vendorName` (Default: "Unknown Vendor"), Contract Type, and Contract Value ($).
* **State Machine Polling**: POSTs `multipart/form-data` to `/api/contracts/upload` and polls `/api/contracts/{id}/status` through lifecycle state transitions (`UPLOADED` ➔ `INDEXED` ➔ `ANALYZED`).

### 3. 🔍 Document Intelligence Deep Dive
* **Hierarchical Structure (TOC)**: Collapsible section tree fetched from `/api/analysis/{contractId}/toc`.
* **Legal Reading Pane**: Displays exact legal text and AI summaries from `/api/analysis/node/{nodeId}`.
* **Identified Risks Panel**: Side panel listing flagged risks mapped from `/api/analysis/{contractId}/risks` with severity badges and AI mitigation suggestions.

### 4. 🏢 Vendor Risk Portfolio
* **Aggregated Exposure Matrix**: Groups contract commitments, average risk scores, and high risk counts by vendor entity.

### 5. 🤖 Agentic Chat Assistant
* **Session Management**: Automatically provisions a persistent UUID `conversationId` per user session.
* **Conversational AI API**: POSTs to `/api/analysis/chat` with `{ userMessage, conversationId }`.
* **Transparent Agent Trace**: Renders markdown responses and an expandable `agentTrace` accordion detailing internal reasoning steps (e.g. `["Executed getCachedAnalysis", "Scanned vector DB"]`).

---

## 📡 API Endpoint Reference Matrix

| Feature Module | Endpoint Path | HTTP Verb | Service Target | Description |
| :--- | :--- | :---: | :--- | :--- |
| **Contracts Master** | `/api/contracts` | `GET` | API Gateway (`:8080`) | Retrieves all ingested contract metadata records |
| **Dashboard Metrics** | `/api/analysis/dashboard-metrics` | `GET` | AI Service (`:8082`) | Returns KPI counts (`totalAnalyzed`, `highRiskCount`, `averageRiskScore`) |
| **Financial Exposure** | `/api/analysis/financial-exposure` | `GET` | AI Service (`:8082`) | Retrieves risk score vs financial commitment plot points |
| **Risk Distribution** | `/api/analysis/risks/distribution` | `GET` | AI Service (`:8082`) | Severity distribution count breakdown |
| **Single Analysis** | `/api/analysis/{contractId}` | `GET` | API Gateway (`:8080`) | Fetches AI risk score, summary, and recommendation |
| **Contract TOC** | `/api/analysis/{contractId}/toc` | `GET` | AI Service (`:8082`) | Hierarchical Table of Contents tree nodes |
| **Node Detail** | `/api/analysis/node/{nodeId}` | `GET` | AI Service (`:8082`) | Exact legal text, AI clause summary, and node risks |
| **Identified Risks** | `/api/analysis/{contractId}/risks` | `GET` | AI Service (`:8082`) | Categorized risk flags for a specific contract |
| **Contract Upload** | `/api/contracts/upload` | `POST` | Contract Service (`:8081`) | Ingests PDF file with vendor metadata (`multipart/form-data`) |
| **Status Polling** | `/api/contracts/{id}/status` | `GET` | Contract Service (`:8081`) | Checks ingestion status (`UPLOADED`, `INDEXED`, `ANALYZED`) |
| **Agentic Chat** | `/api/analysis/chat` | `POST` | AI Service (`:8082`) | Interactive assistant query returning answer and `agentTrace` |

---

## ⚙️ Environment Configuration (`.env`)

The application loads configuration parameters using `python-dotenv`. Create a `.env` file in the project root:

```env
# ProcureMind Microservices Backend Endpoints
GATEWAY_URL=http://localhost:8080
CONTRACT_SERVICE_URL=http://localhost:8081
AI_SERVICE_URL=http://localhost:8082
```

> **Note**: If backend services are unreachable, the UI seamlessly falls back to high-fidelity mock datasets, ensuring zero runtime UI errors during demonstration or offline testing.

---

## 📁 Repository Structure

```
procuremind-ui/
├── .env                # Microservices gateway configuration
├── pyproject.toml      # Project metadata & Python dependencies
├── uv.lock             # Reproducible lockfile for uv package manager
├── README.md           # Production documentation
└── main.py             # Streamlit application entry point & UI rendering logic
```

---

## 🚀 Setup & Execution Guide

### Prerequisites
* **Python 3.13+** installed.
* **uv** (Recommended) or standard `pip`.

### Option A: Running with `uv` (Recommended)

`uv` provides extremely fast environment resolution and execution.

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

# Activate environment
# PowerShell:
.venv\Scripts\Activate.ps1
# Linux / macOS:
source .venv/bin/activate

# Install dependencies
pip install -e .

# Run Streamlit
streamlit run main.py
```

### 🌐 Accessing the Dashboard
Open your browser and navigate to:
- **Local Application Interface**: `http://localhost:8501`

---

## 🎨 Design Language & Color Palette

ProcureMind utilizes a modern **Enterprise Dark Mode** palette:

* **Background Base**: Slate 950 (`#0b0f19`) & Slate 900 (`#0f172a`)
* **Card Borders**: Dark Slate (`#334155`) with Indigo glow on hover (`#3b82f6`)
* **High Risk Badge**: Crimson Red (`#ef4444`, `rgba(239, 68, 68, 0.15)`)
* **Medium Risk Badge**: Amber Gold (`#f59e0b`, `rgba(245, 158, 11, 0.15)`)
* **Low Risk Badge**: Emerald Green (`#10b981`, `rgba(16, 185, 129, 0.15)`)

---

## 🛡️ License & Operational Security

Built for enterprise procurement and legal compliance environments. All incoming contract uploads undergo server-side validation and isolated vector embedding indexing.
