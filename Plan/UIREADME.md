# ProcureMind — Dashboard UI (`procuremind-ui`)

**ProcureMind UI** is an interactive web-based dashboard built with **Python 3.13**, **Streamlit**, **Pandas**, and **Plotly**. It serves as the primary user interface for corporate procurement officers, legal teams, and contract managers to monitor ingested contracts, view real-time risk scores, analyze clause distributions, and track processing pipelines.

---

## Architectural Role

In the ProcureMind microservices topology, `procuremind-ui` operates as an external client application. It consumes aggregated read models exclusively through the **API Gateway** (`http://localhost:8080`), ensuring complete decoupling from backend service locations and database instances.

```mermaid
flowchart LR
    User([Procurement Officer])
    
    subgraph UI Layer
        ST["ProcureMind Streamlit UI\n(main.py - Port 8501)"]
    end

    subgraph Edge Layer
        GW["API Gateway\n(Port 8080)"]
    end

    subgraph Backend Microservices
        CS["Contract Service\n/api/contracts"]
        AI["AI Service\n/api/analysis/{id}"]
    end

    User -->|Browser HTTP| ST
    ST -->|GET /api/contracts| GW
    ST -->|GET /api/analysis/{id}| GW
    GW --> CS
    GW --> AI
```

---

## Features & Capabilities

* **Key Performance Indicator (KPI) Cards**: Displays real-time counts for Total Contracts, Pending Reviews, High-Risk Contracts (Risk Score $\ge$ 7.0), and Average Risk Score across all analyzed vendor agreements.
* **Risk Severity Distribution Chart**: Visualizes high, medium, and low severity risk breakdown using interactive Plotly pie charts.
* **CQRS Read Model Aggregation**: Merges contract lifecycle status from `contract-service` with AI risk analysis outputs from `ai-service` seamlessly in memory.
* **Performance Caching**: Employs `@st.cache_data(ttl=10)` to optimize API Gateway throughput and prevent request flooding while keeping UI state fresh.
* **Responsive Sidebar Navigation**: Quick access to Dashboard, Contracts, Vendors, and Comparisons modules.

---

## Folder Structure & Core Files

```
procuremind-ui/
├── pyproject.toml     # Project metadata and dependency definitions
├── uv.lock            # Lockfile for reproducible environment resolution
├── README.md          # UI documentation
└── main.py            # Streamlit application entry point & rendering logic
```

### Key Python Dependencies (`pyproject.toml`)
* `streamlit>=1.60.0`: Reactive web UI framework.
* `pandas>=3.0.5`: Data tabular manipulation and CQRS record merging.
* `plotly>=6.9.0`: Interactive charts and risk breakdown visualizer.
* `requests>=2.34.2`: Synchronous HTTP client calling API Gateway endpoints.

---

## Application Code Overview (`main.py`)

### Core Components

1. **Configuration & Gateway Binding**:
   ```python
   GATEWAY_URL = "http://localhost:8080"
   ```

2. **Cached API Integration**:
   * `fetch_contracts()`: Calls `GET http://localhost:8080/api/contracts` to retrieve uploaded contract metadata.
   * `fetch_analysis(contract_id)`: Calls `GET http://localhost:8080/api/analysis/{contract_id}` to fetch AI risk scores, recommendations, and granular risks.

3. **CQRS Data Merger (`load_dashboard_data`)**:
   Joins results per contract into a unified Pandas `DataFrame` containing contract IDs, vendor names, processing statuses, upload dates, risk scores, and nested risk arrays.

4. **Rendering Components**:
   * `render_kpi_cards(df)`: Computes and renders metric widgets.
   * `render_main_content(df)`: Displays styled contract data tables alongside Plotly risk distribution charts.

---

## Setup & Local Execution Guide

### Prerequisites
* **Python 3.13+** installed on your system.
* **ProcureMind API Gateway** running on `http://localhost:8080`.

### Option A: Running with `uv` (Recommended)

`uv` is an extremely fast Python package installer and virtual environment manager.

```bash
cd procuremind-ui

# Sync dependencies and create virtual environment
uv sync

# Run the Streamlit application
uv run streamlit run main.py
```

### Option B: Running with Standard `pip` and `venv`

```bash
cd procuremind-ui

# Create virtual environment
python -m venv .venv

# Activate virtual environment
# On Windows (PowerShell):
.venv\Scripts\Activate.ps1
# On Linux/macOS:
source .venv/bin/activate

# Install dependencies
pip install -r pyproject.toml

# Run the Streamlit application
streamlit run main.py
```

---

## Accessing the Dashboard

Once started, Streamlit will output local URLs:
* **Local Web Interface**: `http://localhost:8501`
* **Network Interface**: `http://<your-ip>:8501`

Open your web browser and navigate to `http://localhost:8501`.

---

## Environment & Customization

To point the UI to a remote or containerized API Gateway, modify `GATEWAY_URL` in `main.py` or set an environment override:

```python
import os
GATEWAY_URL = os.getenv("GATEWAY_URL", "http://localhost:8080")
```
