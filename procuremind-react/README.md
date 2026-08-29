# ProcureMind React — Production SaaS Frontend (`procuremind-react`)

## Project Overview

**ProcureMind React** is an enterprise-grade SaaS web application built with **React**, **Vite**, and **Tailwind CSS**. It represents a modernized, production-ready rebuild of the original Streamlit dashboard (`procuremind-ui`).

The platform acts as a Backend-For-Frontend (BFF) interface for corporate procurement officers, legal teams, and risk analysts. It merges raw contract ingestion metadata with AI-generated risk scoring, financial exposure visualizations, section-level Table of Contents (TOC) clause inspection, and transparent conversational AI.

---

## What Was Changed & Improved

1. **Enterprise Architecture & Performance**: Replaced the Python Streamlit page-rerun execution model with a client-side React single-page application (SPA), eliminating full-page refreshes.
2. **Dedicated Agentic AI Assistant**: Built a standalone full-page AI Chat workspace (`/chat`) with session UUID tracking, quick suggestion chips, message history, and expandable `agentTrace` execution steps.
3. **Data Visualization (Recharts)**: Upgraded Plotly figures to responsive, interactive Recharts visualizations:
   - **Scatter/Bubble Plot**: Risk Score vs Financial Commitment ($).
   - **Donut Chart**: Risk Severity Breakdown (High, Medium, Low).
   - **Bar Chart**: Vendor Portfolio Commitment matrix.
4. **Interactive Document Intelligence View**:
   - Collapsible TOC clause tree navigation.
   - Legal Reading Pane displaying exact legal text (`rawContent`) and AI summaries.
   - Identified Risks Panel with severity badges (`HIGH`, `MEDIUM`, `LOW`) and actionable mitigation suggestions.

---

## Pages & User Workflows

### 1. 📊 BI Executive Dashboard
- **KPI Metric Strip**: Displays Total Contracts Ingested, High-Risk Agreements, Average Risk Score (out of 10), and Total Portfolio Exposure ($).
- **Interactive Charts**: Responsive scatter plots and risk distribution donut charts.
- **Recent Contracts Data Grid**: Features column sorting (Vendor, Amount, Risk Score), search bar debouncing, and multi-field status/risk filters.

### 2. 🔍 Document Intelligence & Deep Clause Analysis
- **Contract Selector**: Select any active contract from your portfolio.
- **Structure TOC**: Inspect section tree nodes.
- **Legal Reading Pane**: Displays exact legal wording and concise AI clause summaries.
- **Identified Risks**: Flagged risk triggers mapped directly from AI microservice analysis.

### 3. 🏢 Vendors Portfolio
- **Vendor Risk Matrix**: Consolidates active contract counts, cumulative financial commitments, and average risk scores per vendor entity.

### 4. 📤 Contract Ingestion Pipeline (Upload Modal)
- **Drag-and-Drop Uploader**: Accepts `.pdf` files.
- **Metadata Inputs**: Vendor Name, Contract Type, and Value ($).
- **Status Polling**: Visualizes state transitions (`UPLOADED` ➔ `INDEXED` ➔ `ANALYZED`) via API polling.

### 5. 🤖 Agentic AI Chat Assistant
- **Conversational RAG**: Ask questions about portfolio exposure or vendor terms.
- **Transparent Reasoning Accordion**: View exact agent execution steps (`agentTrace`).

---

## Architecture & Data Flow

```
procuremind-react/src/
├── api/
│   ├── client.js           # Central API client with fetch timeouts & fallback data
│   └── mockData.js         # Realistic fallback dataset for offline/preview mode
├── components/
│   ├── ui/                 # Reusable UI elements (Button, Card, Badge, Modal, Skeleton)
│   ├── layout/             # Header navbar & left sidebar navigation
│   ├── dashboard/          # KPI cards, scatter & donut charts, data grid
│   ├── intelligence/       # TOC tree, reading pane, risk side panel
│   ├── vendors/            # Vendor portfolio matrix & bar chart
│   ├── upload/             # Drag-and-drop upload modal with polling
│   └── chat/               # Dedicated AI chat workspace
├── context/
│   └── AppContext.jsx      # Global state provider (Active tab, contracts, chat session)
├── App.jsx                 # Application layout shell & tab router
└── main.jsx                # React application entry point
```

---

## Data & API Integration

- **API Gateway Connection**: Consumes RESTful APIs at `VITE_GATEWAY_URL` (Default: `http://localhost:8080`).
- **BFF Microservices**:
  - `GET /api/contracts`
  - `GET /api/analysis/dashboard-metrics`
  - `GET /api/analysis/financial-exposure`
  - `GET /api/analysis/risks/distribution`
  - `GET /api/analysis/{contractId}`
  - `GET /api/analysis/{contractId}/toc`
  - `GET /api/analysis/node/{nodeId}`
  - `GET /api/analysis/{contractId}/risks`
  - `POST /api/contracts/upload`
  - `GET /api/contracts/{id}/status`
  - `POST /api/analysis/chat`
- **Graceful Fallbacks**: If backend microservices are offline, the client automatically loads structured mock data so the application remains 100% interactive without crashing.

---

## User-Safety & Production Guardrails

1. **Spam & Double-Submit Protection**: Submit buttons enter a loading spinner state and are disabled during in-flight network operations to prevent duplicate API submissions.
2. **Input Debouncing**: Search inputs are debounced to avoid flooding the backend with network requests on every keystroke.
3. **Timeout & Request Cancellation**: Network calls enforce a 120-second timeout using `AbortController`.
4. **Skeleton Loading States**: Skeleton loaders replace cards, charts, and tables during data fetching, avoiding jarring layout shifts.
5. **Session UUID Management**: Chat sessions maintain a persistent `conversationId` per browser session.

---

## How To Run

### 1. Install Dependencies
```bash
cd procuremind-react
npm install
```

### 2. Run Development Server
```bash
npm run dev
```
Open `http://localhost:5173` in your web browser.

### 3. Build for Production
```bash
npm run build
```

---

## Environment Configuration

Create a `.env` file in `procuremind-react/`:

```env
VITE_GATEWAY_URL=http://localhost:8080
VITE_API_TIMEOUT=120000
```
