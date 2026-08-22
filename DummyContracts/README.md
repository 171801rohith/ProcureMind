# ProcureMind — Dummy Contracts & Test Fixtures (`DummyContracts`)

The `DummyContracts` directory contains sample legal procurement agreements (PDF documents) and exported database/analysis fixtures (JSON datasets) used for platform demonstration, integration testing, and local development.

---

## 1. Purpose & Overview

This directory provides:
1. **Real-world Legal PDFs**: Used for testing the end-to-end ingestion, Apache Tika extraction, hierarchical section splitting, and LLM risk assessment pipeline.
2. **Exported Database Fixtures**: Used for offline validation, mock testing, and verifying JSON serialization formats against PostgreSQL schema tables.

---

## 2. Directory Contents

### Sample Contract PDF Files

| Filename | Document Type | Typical Clauses Tested |
| :--- | :--- | :--- |
| `Boxoffice-LLC-Master-Services-Agreement-2024.pdf` | Master Services Agreement (MSA) | Term, Payment, Intellectual Property, Liability Caps |
| `MSA-with-Schedules_January-20-2025.pdf` | Enterprise MSA with Schedules | Multi-schedule structure, Indemnification, SLA penalties |
| `MSA_3c7d18f7-25f8-466b-adf61694603255182_managerprocurement1.pdf` | Procurement MSA | Vendor compliance, Audit rights, Termination for cause |
| `862892796562325-terms-and-conditions-2024-05-02-1445.pdf` | Standard Terms & Conditions | Dispute Resolution, Warranty Disclaimers, Governing Law |
| `FTC.pdf` | Regulatory & Compliance Agreement | Statutory obligations, Federal Trade Commission compliance |
| `FoucsVision.pdf` | SaaS / Service Agreement | Data privacy, Security standards, Service availability |
| `Nysrga.pdf` | Association Agreement | Membership rules, Liability exclusions, Regulatory filings |

---

### Database & Analysis JSON Fixtures

| Filename | Schema Table / Entity | Description |
| :--- | :--- | :--- |
| `page_index_nodes.json` | `page_index_nodes` | Exported hierarchical document tree records (`ROOT`, `ARTICLE`, `SECTION`) including raw text, AI titles, and summaries. |
| `procuremind_db_public_page_index_nodes.json` | `page_index_nodes` | Raw PostgreSQL export of indexed nodes for database verification. |
| `contract_analysis.json` | `contract_analysis` | Exported analysis records containing overall `risk_score`, `recommendation`, and `status`. |
| `analysis_risks.json` | `analysis_risks` | Exported granular risk records with `severity` (`HIGH`, `MEDIUM`, `LOW`) and descriptions. |

---

## 3. How to Use

### 1. Uploading Sample Contracts via UI
1. Launch the platform (API Gateway on port 8080 and UI on port 8501).
2. Click **"➕ Upload Contract"** in the UI dashboard.
3. Select any PDF from `DummyContracts/` (e.g. `Boxoffice-LLC-Master-Services-Agreement-2024.pdf`).
4. Enter vendor name (e.g. `Boxoffice LLC`) and submit.
5. Monitor real-time status transitions: `UPLOADED` ➔ `INDEXED` ➔ `ANALYZED`.

### 2. Uploading via REST API (cURL)
```bash
curl -X POST http://localhost:8080/api/contracts/upload \
  -F "file=@DummyContracts/Boxoffice-LLC-Master-Services-Agreement-2024.pdf" \
  -F "vendorName=Boxoffice LLC"
```

### 3. Database Seeding & Mock Verification
The JSON fixtures can be used to seed local PostgreSQL tables or validate DTO mapping logic in unit and integration tests without needing to invoke Ollama LLM inference.
