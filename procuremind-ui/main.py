import os
import uuid
import time
import json
import requests
import pandas as pd
import plotly.express as px
import plotly.graph_objects as go
from datetime import datetime
import streamlit as st
from dotenv import load_dotenv

import auth as oidc_auth

# --- 1. ENVIRONMENT CONFIGURATION & SETUP ---
load_dotenv()
GATEWAY_URL = os.getenv("GATEWAY_URL", "http://localhost:8080")
API_TIMEOUT = int(os.getenv("API_TIMEOUT", "12000"))

st.set_page_config(
    page_title="ProcureMind - AI Contract Intelligence",
    page_icon="🛡️",
    layout="wide",
    initial_sidebar_state="expanded"
)

# --- 2. EXECUTIVE DARK MODE CSS ---
CUSTOM_CSS = """
<style>
    /* Dark Slate Body & Backgrounds */
    .stApp {
        background-color: #080c14;
        color: #f1f5f9;
        font-family: 'Inter', system-ui, -apple-system, sans-serif;
    }
    
    /* Header Styling */
    header[data-testid="stHeader"] {
        background-color: #080c14;
    }
    
    /* Sidebar Styling */
    section[data-testid="stSidebar"] {
        background-color: #0f172a;
        border-right: 1px solid #1e293b;
    }

    /* Executive Glass Card Styles */
    .glass-card {
        background: linear-gradient(135deg, rgba(30, 41, 59, 0.7) 0%, rgba(15, 23, 42, 0.8) 100%);
        border: 1px solid #334155;
        border-radius: 12px;
        padding: 20px;
        box-shadow: 0 4px 20px -2px rgba(0, 0, 0, 0.4);
        backdrop-filter: blur(8px);
        transition: transform 0.2s, border-color 0.2s;
    }
    
    /* Metric Card Styles */
    .metric-card {
        background: linear-gradient(135deg, #1e293b 0%, #0f172a 100%);
        border: 1px solid #334155;
        border-radius: 12px;
        padding: 20px;
        box-shadow: 0 4px 20px -2px rgba(0, 0, 0, 0.4);
        transition: transform 0.2s, border-color 0.2s;
    }
    .metric-card:hover {
        border-color: #3b82f6;
        transform: translateY(-2px);
    }
    .metric-title {
        color: #94a3b8;
        font-size: 0.82rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
    }
    .metric-value {
        color: #f8fafc;
        font-size: 1.85rem;
        font-weight: 700;
        margin-top: 6px;
    }
    .metric-subtitle {
        font-size: 0.8rem;
        margin-top: 4px;
        font-weight: 500;
    }

    /* Risk Badge Styles */
    .badge-high {
        background-color: rgba(239, 68, 68, 0.15);
        color: #ef4444;
        border: 1px solid rgba(239, 68, 68, 0.4);
        padding: 3px 10px;
        border-radius: 9999px;
        font-weight: 600;
        font-size: 0.78rem;
    }
    .badge-medium {
        background-color: rgba(245, 158, 11, 0.15);
        color: #f59e0b;
        border: 1px solid rgba(245, 158, 11, 0.4);
        padding: 3px 10px;
        border-radius: 9999px;
        font-weight: 600;
        font-size: 0.78rem;
    }
    .badge-low {
        background-color: rgba(16, 185, 129, 0.15);
        color: #10b981;
        border: 1px solid rgba(16, 185, 129, 0.4);
        padding: 3px 10px;
        border-radius: 9999px;
        font-weight: 600;
        font-size: 0.78rem;
    }

    /* Standalone Chat UI Components */
    .chat-container-header {
        background: linear-gradient(90deg, #0f172a 0%, #1e293b 100%);
        border: 1px solid #1e3a8a;
        border-radius: 12px;
        padding: 16px 20px;
        margin-bottom: 16px;
    }
    .chat-user-bubble {
        background-color: #1e293b;
        border: 1px solid #334155;
        border-radius: 12px 12px 2px 12px;
        padding: 14px 18px;
        margin-bottom: 14px;
        color: #f1f5f9;
        box-shadow: 0 2px 8px rgba(0,0,0,0.2);
    }
    .chat-agent-bubble {
        background-color: #0f172a;
        border: 1px solid #1e3a8a;
        border-radius: 12px 12px 12px 2px;
        padding: 14px 18px;
        margin-bottom: 14px;
        color: #e2e8f0;
        box-shadow: 0 0 20px -4px rgba(59, 130, 246, 0.2);
    }
    
    /* Trace Expander Container */
    .trace-box {
        background-color: #050811;
        border-left: 3px solid #3b82f6;
        padding: 12px 16px;
        font-family: 'Fira Code', monospace;
        font-size: 0.82rem;
        color: #93c5fd;
        border-radius: 6px;
        margin-top: 10px;
    }
</style>
"""
st.markdown(CUSTOM_CSS, unsafe_allow_html=True)

# --- 3. SESSION STATE INITIALIZATION ---
if "conversation_id" not in st.session_state:
    st.session_state.conversation_id = str(uuid.uuid4())

if "chat_history" not in st.session_state:
    st.session_state.chat_history = [
        {
            "sender": "agent",
            "text": "I am **ProcureMind AI**. Ask me about your contract portfolio, risk exposure, specific vendor terms, or clause remediation options.",
            "timestamp": datetime.now().strftime("%H:%M"),
            "agentTrace": ["Initialized ProcureMind Agentic Assistant session", "Bound session UUID"]
        }
    ]

# --- 4. STRICT BACKEND API CALLERS (NO MOCK FALLBACKS) ---
def safe_get_api(endpoint: str):
    url = f"{GATEWAY_URL}{endpoint}"
    try:
        res = requests.get(url, headers=oidc_auth.auth_headers(), timeout=API_TIMEOUT)
        if res.status_code == 401 and oidc_auth.refresh_or_relogin():
            res = requests.get(url, headers=oidc_auth.auth_headers(), timeout=API_TIMEOUT)
        if res.status_code == 200:
            return res.json()
    except Exception as e:
        st.warning(f"Unable to connect to API Gateway at {url}: {e}")
    return None

def fetch_contracts_api():
    res = safe_get_api("/api/contracts")
    return res if isinstance(res, list) else []

def fetch_dashboard_metrics_api():
    res = safe_get_api("/api/analysis/dashboard-metrics")
    if isinstance(res, dict):
        return {
            "totalAnalyzed": res.get("totalAnalyzed", 0),
            "highRiskCount": res.get("highRiskCount", 0),
            "averageRiskScore": res.get("averageRiskScore", 0.0)
        }
    return {"totalAnalyzed": 0, "highRiskCount": 0, "averageRiskScore": 0.0}

def fetch_financial_exposure_api():
    res = safe_get_api("/api/analysis/financial-exposure")
    return res if isinstance(res, list) else []

def fetch_risk_distribution_api():
    res = safe_get_api("/api/analysis/risks/distribution")
    return res if isinstance(res, list) else []

def fetch_contract_analysis_api(contract_id: str):
    res = safe_get_api(f"/api/analysis/{contract_id}")
    return res if isinstance(res, dict) else {}

def fetch_contract_toc_api(contract_id: str):
    res = safe_get_api(f"/api/analysis/{contract_id}/toc")
    return res if isinstance(res, list) else []

def fetch_node_detail_api(node_id: str):
    res = safe_get_api(f"/api/analysis/node/{node_id}")
    return res if isinstance(res, dict) else {}

def fetch_contract_risks_api(contract_id: str):
    res = safe_get_api(f"/api/analysis/{contract_id}/risks")
    return res if isinstance(res, list) else []

def upload_contract_api(file, vendor_name: str, contract_type: str, amount: float):
    url = f"{GATEWAY_URL}/api/contracts/upload"
    # Ensure file buffer is at position 0
    if hasattr(file, "seek"):
        file.seek(0)
    files = {"file": (file.name, file.getvalue() if hasattr(file, "getvalue") else file, "application/pdf")}
    data = {
        "vendorName": vendor_name,
        "contractType": contract_type,
        "amount": str(amount)
    }
    try:
        res = requests.post(url, files=files, data=data, headers=oidc_auth.auth_headers(), timeout=API_TIMEOUT)
        if res.status_code == 401 and oidc_auth.refresh_or_relogin():
            file.seek(0) if hasattr(file, "seek") else None
            files = {"file": (file.name, file.getvalue() if hasattr(file, "getvalue") else file, "application/pdf")}
            res = requests.post(url, files=files, data=data, headers=oidc_auth.auth_headers(), timeout=API_TIMEOUT)
        if res.status_code in [200, 201, 202]:
            try:
                return res.json()
            except Exception:
                return {"id": "UPLOADED", "status": "UPLOADED", "filename": file.name}
        else:
            st.error(f"Backend API Gateway returned HTTP {res.status_code}: {res.text}")
    except Exception as e:
        st.error(f"Failed to upload contract to {url}: {e}")
    return None

def check_status_api(contract_id: str):
    res = safe_get_api(f"/api/contracts/{contract_id}/status")
    return res if isinstance(res, dict) else {"id": contract_id, "status": "UNKNOWN"}

def send_chat_api(user_message: str, conversation_id: str):
    url = f"{GATEWAY_URL}/api/analysis/chat"
    payload = {"userMessage": user_message, "conversationId": conversation_id}
    try:
        res = requests.post(url, json=payload, headers=oidc_auth.auth_headers(), timeout=API_TIMEOUT)
        if res.status_code == 401 and oidc_auth.refresh_or_relogin():
            res = requests.post(url, json=payload, headers=oidc_auth.auth_headers(), timeout=API_TIMEOUT)
        if res.status_code == 200:
            return res.json()
        else:
            return {
                "answer": f"Backend API Gateway returned error HTTP {res.status_code}: {res.text}",
                "agentTrace": ["HTTP Error Response"]
            }
    except Exception as e:
        return {
            "answer": f"Could not connect to API Gateway at {url}: {e}",
            "agentTrace": ["Network Error / Connection Failure"]
        }

# --- 5. CQRS IN-MEMORY DATA MERGER ---
def load_merged_contracts():
    contracts = fetch_contracts_api()
    if not contracts:
        return pd.DataFrame()
        
    merged_rows = []
    for c in contracts:
        cid = c.get("id") or c.get("contractId") or "N/A"
        analysis = fetch_contract_analysis_api(cid) if cid != "N/A" else {}

        merged_rows.append({
            "Contract ID": cid,
            "Short ID": cid[:8].upper() if len(cid) >= 8 else cid,
            "File Name": c.get("fileName", "Unknown File"),
            "Vendor Name": c.get("vendorName", "Unknown Vendor"),
            "Status": c.get("status", "UNKNOWN"),
            "Uploaded At": c.get("uploadedAt", ""),
            "Contract Type": c.get("contractType") or analysis.get("contractType") or "MSA",
            "Amount ($)": float(c.get("amount") or analysis.get("amount") or 0),
            "Risk Score": float(analysis.get("riskScore", 0.0)),
            "High Risk Count": int(analysis.get("highRiskCount", 0)),
            "Recommendation": analysis.get("recommendation", "N/A"),
            "Summary": analysis.get("summary", "N/A")
        })
    return pd.DataFrame(merged_rows)

# --- 6. NAVIGATION & HEADER ---
def render_header():
    col1, col2, col3 = st.columns([3, 2, 2])
    with col1:
        st.markdown("<h2 style='margin:0; color:#f8fafc; font-weight:800;'>🛡️ ProcureMind <span style='font-size:0.85rem; color:#3b82f6; border:1px solid #1d4ed8; padding:2px 8px; border-radius:12px;'>BFF Enterprise</span></h2>", unsafe_allow_html=True)
        st.caption("AI-Powered Legal Contract Analysis & Financial Risk Platform")
    
    with col2:
        st.markdown(f"<div style='text-align:right; font-size:0.8rem; color:#94a3b8; margin-top:8px;'>Gateway: <code>{GATEWAY_URL}</code></div>", unsafe_allow_html=True)
    
    with col3:
        if oidc_auth.can_write() and st.button("➕ Upload Contract", type="primary", use_container_width=True):
            st.session_state.show_upload_modal = True

def render_sidebar():
    with st.sidebar:
        st.markdown("### 📌 Navigation")
        modules = [
            "📊 BI Dashboard",
            "🔍 Document Intelligence",
            "🏢 Vendors Portfolio",
        ]
        # Ingestion and the assistant are ANALYST/ADMIN only (plan section 12).
        if oidc_auth.can_write():
            modules += ["📤 Ingestion Pipeline", "🤖 ProcureMind AI Assistant"]

        selected_tab = st.radio(
            "Select Module",
            modules,
            index=0
        )
        st.markdown("---")
        
        st.markdown("#### ⚙️ System Status")
        st.markdown(f"🟢 **API Gateway:** `{GATEWAY_URL}`")
        st.markdown(f"⏳ **Timeout:** `{API_TIMEOUT}s`")

        _user = oidc_auth.current_user()
        if _user:
            st.markdown("---")
            st.markdown(f"👤 **{_user.get('preferred_username') or _user.get('name') or _user.get('sub')}**")
            if st.button("Sign out", use_container_width=True):
                oidc_auth.logout()

        st.markdown("---")
        st.caption(f"Session UUID:\n`{st.session_state.conversation_id}`")

        return selected_tab

# --- 7. UPLOAD MODAL COMPONENT (FR-2) ---
def render_upload_modal():
    st.markdown("---")
    st.subheader("📤 Ingest New Legal Contract")
    st.caption("Upload a `.pdf` agreement to trigger OCR text extraction, clause indexing, and AI risk scoring via backend API.")
    
    if "last_uploaded_contract" in st.session_state and st.session_state.last_uploaded_contract:
        last_c = st.session_state.last_uploaded_contract
        st.success(f"🎉 **Contract Ingested Successfully!** Assigned UUID: `{last_c.get('id')}`")
        st.markdown(f"""
        <div style="background-color:rgba(16, 185, 129, 0.1); border:1px solid rgba(16, 185, 129, 0.3); border-radius:10px; padding:16px; margin-bottom:20px;">
            <h5 style="color:#10b981; margin:0 0 8px 0;">✅ Pipeline Event Dispatched</h5>
            <p style="margin:0; font-size:0.9rem; color:#cbd5e1;">
                <b>File:</b> {last_c.get('filename')}<br>
                <b>Vendor:</b> {last_c.get('vendorName')}<br>
                <b>Status:</b> <span class="badge-low">{last_c.get('status', 'UPLOADED')}</span>
            </p>
        </div>
        """, unsafe_allow_html=True)
        if st.button("➕ Ingest Another Contract"):
            del st.session_state.last_uploaded_contract
            st.rerun()

    with st.form("upload_form", clear_on_submit=True):
        uploaded_file = st.file_uploader("Drag and drop PDF contract file", type=["pdf"])
        col_a, col_b, col_c = st.columns(3)
        with col_a:
            vendor_input = st.text_input("Vendor Name", value="Unknown Vendor")
        with col_b:
            type_input = st.selectbox("Contract Type", ["MSA", "SLA", "DPA", "Software License", "SOW", "Vendor Agreement", "NDA"])
        with col_c:
            amount_input = st.number_input("Contract Value ($)", value=500000, step=50000)
            
        submitted = st.form_submit_button("Start Pipeline Ingestion", type="primary", use_container_width=True)
        
        if submitted:
            if not uploaded_file:
                st.error("Please select a PDF contract file to upload.")
            else:
                with st.spinner("Uploading contract file to backend API Gateway (waiting for response)..."):
                    res = upload_contract_api(uploaded_file, vendor_input, type_input, amount_input)
                    if res and ("id" in res or "contractId" in res or "filename" in res or "status" in res):
                        cid = res.get("id") or res.get("contractId") or "NEW"
                        st.session_state.last_uploaded_contract = {
                            "id": cid,
                            "filename": uploaded_file.name,
                            "vendorName": vendor_input,
                            "status": res.get("status", "UPLOADED")
                        }
                        st.balloons()
                        st.rerun()
                    else:
                        st.error("Upload failed: Backend API did not return a valid contract acknowledgment.")

# --- 8. BI DASHBOARD COMPONENT (FR-1) ---
def render_dashboard(df):
    metrics = fetch_dashboard_metrics_api()
    exposure_data = fetch_financial_exposure_api()
    distribution_data = fetch_risk_distribution_api()

    # --- KPI STRIP (4 Cards) ---
    c1, c2, c3, c4 = st.columns(4)
    with c1:
        st.markdown(f"""
        <div class="metric-card" style="border-top:3px solid #10b981;">
            <div class="metric-title">Total Ingested Contracts</div>
            <div class="metric-value">{metrics['totalAnalyzed']}</div>
            <div class="metric-subtitle" style="color:#10b981;">From API Gateway</div>
        </div>
        """, unsafe_allow_html=True)
        
    with c2:
        st.markdown(f"""
        <div class="metric-card" style="border-top:3px solid #ef4444;">
            <div class="metric-title">High Risk Contracts</div>
            <div class="metric-value" style="color:#ef4444;">{metrics['highRiskCount']}</div>
            <div class="metric-subtitle" style="color:#ef4444;">Action Required</div>
        </div>
        """, unsafe_allow_html=True)
        
    with c3:
        st.markdown(f"""
        <div class="metric-card" style="border-top:3px solid #f59e0b;">
            <div class="metric-title">Average Risk Score</div>
            <div class="metric-value" style="color:#f59e0b;">{metrics['averageRiskScore']} <span style="font-size:1rem; color:#94a3b8;">/ 10</span></div>
            <div class="metric-subtitle" style="color:#94a3b8;">Threshold: 7.0</div>
        </div>
        """, unsafe_allow_html=True)
        
    with c4:
        total_exp = sum(float(d.get("amount", 0)) for d in exposure_data) if exposure_data else 0
        st.markdown(f"""
        <div class="metric-card" style="border-top:3px solid #3b82f6;">
            <div class="metric-title">Financial Risk Exposure</div>
            <div class="metric-value" style="color:#3b82f6;">${total_exp/1e6:.2f}M</div>
            <div class="metric-subtitle" style="color:#94a3b8;">Portfolio Commitment</div>
        </div>
        """, unsafe_allow_html=True)

    st.markdown("<br>", unsafe_allow_html=True)

    # --- CHARTS SECTION (2 COLUMNS) ---
    chart_col1, chart_col2 = st.columns([3, 2])

    with chart_col1:
        st.markdown("#### 📈 Financial Exposure vs. Risk Score Scatter Plot")
        if exposure_data:
            exp_df = pd.DataFrame(exposure_data)
            if "riskScore" in exp_df.columns and "amount" in exp_df.columns:
                fig_scatter = px.scatter(
                    exp_df,
                    x="riskScore",
                    y="amount",
                    size="amount" if "amount" in exp_df.columns else None,
                    color="riskScore",
                    hover_data=[c for c in ["vendorName", "contractType", "fileName"] if c in exp_df.columns],
                    color_continuous_scale=["#10b981", "#f59e0b", "#ef4444"],
                    labels={"riskScore": "Risk Score (1-10)", "amount": "Contract Value ($)"},
                    title=None
                )
                fig_scatter.update_layout(
                    plot_bgcolor="rgba(15, 23, 42, 0.6)",
                    paper_bgcolor="rgba(15, 23, 42, 0.0)",
                    font_color="#f8fafc",
                    height=340,
                    margin=dict(l=20, r=20, t=10, b=20),
                    xaxis=dict(gridcolor="#334155", range=[0, 10]),
                    yaxis=dict(gridcolor="#334155")
                )
                fig_scatter.add_vline(x=7.0, line_dash="dash", line_color="#ef4444", annotation_text="High Risk Threshold")
                st.plotly_chart(fig_scatter, use_container_width=True)
            else:
                st.info("Exposure data format returned from backend is missing required fields.")
        else:
            st.info("No financial exposure data returned from Backend API Gateway.")

    with chart_col2:
        st.markdown("#### 🍩 Risk Severity Breakdown")
        if distribution_data:
            dist_df = pd.DataFrame(distribution_data)
            if "severity" in dist_df.columns and "count" in dist_df.columns:
                color_map = {
                    "HIGH": "#ef4444", "High": "#ef4444",
                    "MODERATE": "#f59e0b", "Moderate": "#f59e0b", "MEDIUM": "#f59e0b", "Medium": "#f59e0b",
                    "LOW": "#10b981", "Low": "#10b981"
                }
                fig_donut = px.pie(
                    dist_df,
                    values="count",
                    names="severity",
                    hole=0.65,
                    color="severity",
                    color_discrete_map=color_map
                )
                fig_donut.update_layout(
                    plot_bgcolor="rgba(0,0,0,0)",
                    paper_bgcolor="rgba(0,0,0,0)",
                    font_color="#f8fafc",
                    height=340,
                    margin=dict(l=10, r=10, t=10, b=10),
                    legend=dict(orientation="h", yanchor="bottom", y=-0.1, xanchor="center", x=0.5)
                )
                st.plotly_chart(fig_donut, use_container_width=True)
            else:
                st.info("Risk distribution data format returned from backend is missing required fields.")
        else:
            st.info("No risk distribution data returned from Backend API Gateway.")

    st.markdown("---")

    # --- RECENT CONTRACTS DATA GRID ---
    st.markdown("#### 📋 Recent Contracts Master Data Grid")
    
    if df.empty:
        st.warning("No contracts returned from API Gateway (/api/contracts). Check if backend microservices are running.")
        return

    # Filter Bar
    f_col1, f_col2, f_col3 = st.columns([2, 1, 1])
    with f_col1:
        search_query = st.text_input("🔍 Search Vendor or File Name", placeholder="Type vendor name (e.g. Acme)...")
    with f_col2:
        status_filter = st.selectbox("Status Filter", ["ALL"] + [s for s in df["Status"].unique() if s])
    with f_col3:
        risk_filter = st.selectbox("Risk Filter", ["ALL", "HIGH (>=7.0)", "MEDIUM (4.0-6.9)", "LOW (<4.0)"])

    filtered_df = df.copy()
    if search_query:
        filtered_df = filtered_df[
            filtered_df["Vendor Name"].astype(str).str.contains(search_query, case=False) |
            filtered_df["File Name"].astype(str).str.contains(search_query, case=False)
        ]
    if status_filter != "ALL":
        filtered_df = filtered_df[filtered_df["Status"] == status_filter]
    if risk_filter != "ALL":
        if "HIGH" in risk_filter:
            filtered_df = filtered_df[filtered_df["Risk Score"] >= 7.0]
        elif "MEDIUM" in risk_filter:
            filtered_df = filtered_df[(filtered_df["Risk Score"] >= 4.0) & (filtered_df["Risk Score"] < 7.0)]
        elif "LOW" in risk_filter:
            filtered_df = filtered_df[filtered_df["Risk Score"] < 4.0]

    st.dataframe(
        filtered_df[["Short ID", "Vendor Name", "Contract Type", "Amount ($)", "Risk Score", "Status", "Uploaded At", "Recommendation"]],
        use_container_width=True,
        column_config={
            "Amount ($)": st.column_config.NumberColumn(format="$%d"),
            "Risk Score": st.column_config.ProgressColumn(format="%.1f", min_value=0, max_value=10),
            "Status": st.column_config.TextColumn()
        },
        hide_index=True
    )

# --- 9. DOCUMENT INTELLIGENCE VIEW (FR-3) ---
def render_document_intelligence(df):
    st.markdown("### 🔍 Document Intelligence & Deep Clause Analysis")
    st.caption("Inspect hierarchical contract structure, review clause extracts, and analyze flagged AI risk alerts strictly from backend API.")

    if df.empty:
        st.warning("No contracts returned from backend API Gateway.")
        return

    # Contract Selector
    selected_contract_id = st.selectbox(
        "Select Target Contract for Deep Dive:",
        options=df["Contract ID"].tolist(),
        format_func=lambda cid: f"{df[df['Contract ID']==cid]['Vendor Name'].values[0]} - {df[df['Contract ID']==cid]['File Name'].values[0]} (Risk: {df[df['Contract ID']==cid]['Risk Score'].values[0]})"
    )

    col_toc, col_pane, col_risks = st.columns([2, 3, 2])

    with col_toc:
        st.markdown("#### 🌲 Structure TOC")
        toc_nodes = fetch_contract_toc_api(selected_contract_id)
        
        node_map = {}
        if toc_nodes:
            node_titles = []
            for idx, n in enumerate(toc_nodes):
                nid = n.get("id") or str(idx)
                title = n.get("title") or n.get("name") or f"Section {idx+1}"
                node_map[nid] = n
                node_titles.append(f"{nid}: {title}")
                
            selected_node_str = st.radio("Inspect Clause Node:", node_titles)
            selected_node_id = selected_node_str.split(":")[0] if selected_node_str else ""
        else:
            st.info("No TOC structure returned from API for this contract.")
            selected_node_id = ""

    with col_pane:
        st.markdown("#### 📖 Legal Reading Pane")
        if selected_node_id:
            selected_toc_node = node_map.get(selected_node_id, {})
            node_detail = fetch_node_detail_api(selected_node_id)
            
            # Robust Multi-Field Extraction for Legal Text & Summary
            clause_title = node_detail.get('title') or selected_toc_node.get('title') or 'Section Detail'
            clause_text = (
                node_detail.get('rawContent') or
                node_detail.get('text') or
                node_detail.get('content') or
                node_detail.get('clauseText') or
                node_detail.get('snippet') or
                selected_toc_node.get('snippet') or
                selected_toc_node.get('summary') or
                "No clause text returned from backend API."
            )
            clause_summary = (
                node_detail.get('summary') or
                node_detail.get('aiSummary') or
                selected_toc_node.get('summary') or
                selected_toc_node.get('description') or
                "No summary available."
            )
            
            st.markdown(f"""
            <div style="background-color:#0f172a; border:1px solid #1e293b; padding:16px; border-radius:8px;">
                <h5 style="color:#3b82f6; margin-top:0;">{clause_title}</h5>
                <p style="font-family:serif; font-size:0.95rem; color:#cbd5e1; line-height:1.6; white-space: pre-wrap;">
                "{clause_text}"
                </p>
                <hr style="border-color:#334155;">
                <h5 style="color:#10b981; margin-bottom:4px;">🤖 AI Clause Summary</h5>
                <p style="font-size:0.88rem; color:#94a3b8;">
                {clause_summary}
                </p>
            </div>
            """, unsafe_allow_html=True)
        else:
            st.info("Select a node from the TOC tree to read legal text.")

    with col_risks:
        st.markdown("#### 🚨 Identified Risks Panel")
        risks_data = fetch_contract_risks_api(selected_contract_id)
        if risks_data:
            for r in risks_data:
                sev_raw = str(r.get("severity", "MEDIUM")).upper()
                badge_class = "badge-high" if sev_raw in ["HIGH", "CRITICAL"] else "badge-medium" if sev_raw in ["MEDIUM", "MODERATE"] else "badge-low"
                
                cat = r.get("category") or r.get("riskCategory") or r.get("type") or r.get("title") or r.get("clause") or "Risk Category"
                desc = r.get("description") or r.get("text") or r.get("details") or r.get("risk") or "No description provided."
                action = r.get("recommendation") or r.get("action") or r.get("suggestedAction") or r.get("mitigation")
                
                action_html = f'<b style="font-size:0.78rem; color:#3b82f6;">💡 Action: {action}</b>' if action else ''
                
                st.markdown(f"""
                <div style="background-color:rgba(15, 23, 42, 0.8); border:1px solid #334155; padding:12px; border-radius:8px; margin-bottom:10px;">
                    <span class="{badge_class}">{sev_raw} RISK</span>
                    <h5 style="margin:6px 0; color:#f8fafc;">{cat}</h5>
                    <p style="font-size:0.8rem; color:#94a3b8; margin-bottom:6px;">{desc}</p>
                    {action_html}
                </div>
                """, unsafe_allow_html=True)
        else:
            st.info("No explicit risk flags returned from backend for this contract.")

# --- 10. VENDORS PORTFOLIO COMPONENT ---
def render_vendors_portfolio(df):
    st.markdown("### 🏢 Vendor Risk & Exposure Portfolio")
    st.caption("Consolidated risk metrics, financial commitments, and contract counts grouped by vendor.")

    if df.empty:
        st.info("No vendor contract data available from API Gateway.")
        return

    vendor_group = df.groupby("Vendor Name").agg(
        ContractCount=("Contract ID", "count"),
        TotalValue=("Amount ($)", "sum"),
        AvgRiskScore=("Risk Score", "mean"),
        HighRiskCount=("High Risk Count", "sum")
    ).reset_index()

    vendor_group["AvgRiskScore"] = vendor_group["AvgRiskScore"].round(1)

    col1, col2 = st.columns([3, 2])
    with col1:
        st.dataframe(
            vendor_group,
            use_container_width=True,
            column_config={
                "TotalValue": st.column_config.NumberColumn(format="$%d"),
                "AvgRiskScore": st.column_config.ProgressColumn(format="%.1f", min_value=0, max_value=10)
            },
            hide_index=True
        )

    with col2:
        fig_vendor = px.bar(
            vendor_group,
            x="Vendor Name",
            y="TotalValue",
            color="AvgRiskScore",
            color_continuous_scale=["#10b981", "#f59e0b", "#ef4444"],
            labels={"TotalValue": "Total Value ($)", "AvgRiskScore": "Risk Score"},
            title="Financial Commitment by Vendor"
        )
        fig_vendor.update_layout(
            plot_bgcolor="rgba(0,0,0,0)",
            paper_bgcolor="rgba(0,0,0,0)",
            font_color="#f8fafc",
            height=300
        )
        st.plotly_chart(fig_vendor, use_container_width=True)

# --- 11. STANDALONE AGENTIC CHAT ASSISTANT PAGE (FR-4) ---
def render_agentic_chat_page():
    st.markdown("### 🤖 ProcureMind Agentic Assistant Workspace")
    
    st.markdown(f"""
    <div class="chat-container-header">
        <div style="display:flex; justify-content:space-between; align-items:center;">
            <div>
                <h4 style="margin:0; color:#f8fafc;">Conversational Contract Intelligence Agent</h4>
                <p style="margin:4px 0 0 0; font-size:0.83rem; color:#94a3b8;">
                    Connected to Backend Gateway <code>{GATEWAY_URL}</code> | Retrieval: Agentic hierarchical clause retrieval
                </p>
            </div>
            <div style="text-align:right;">
                <span class="badge-low">🟢 ACTIVE SESSION</span><br>
                <span style="font-size:0.75rem; color:#94a3b8;">UUID: <code>{st.session_state.conversation_id[:16]}...</code></span>
            </div>
        </div>
    </div>
    """, unsafe_allow_html=True)

    # Preset Quick Suggestion Prompts Row
    st.markdown("##### 💡 Suggested Queries")
    cp1, cp2, cp3, cp4 = st.columns(4)
    preset_clicked = None
    with cp1:
        if st.button("🚨 Highest Risk Contract", use_container_width=True):
            preset_clicked = "What is our highest risk contract?"
    with cp2:
        if st.button("🏢 Acme Corp Exposure", use_container_width=True):
            preset_clicked = "Show me Acme Corp MSA risks"
    with cp3:
        if st.button("⚖️ Indemnification Terms", use_container_width=True):
            preset_clicked = "Summarize indemnification clauses"
    with cp4:
        if st.button("🧹 Clear Chat", use_container_width=True):
            st.session_state.chat_history = [
                {
                    "sender": "agent",
                    "text": "Chat session reset. I am **ProcureMind AI**. How can I assist with your contract portfolio today?",
                    "timestamp": datetime.now().strftime("%H:%M"),
                    "agentTrace": ["Reset chat history buffer"]
                }
            ]
            st.rerun()

    st.markdown("<br>", unsafe_allow_html=True)

    # Chat Message Thread
    for msg in st.session_state.chat_history:
        if msg["sender"] == "user":
            st.markdown(f"""
            <div class="chat-user-bubble">
                <div style="font-size:0.8rem; color:#94a3b8; margin-bottom:6px;">👤 <b>You</b> • {msg['timestamp']}</div>
                <div>{msg['text']}</div>
            </div>
            """, unsafe_allow_html=True)
        else:
            st.markdown(f"""
            <div class="chat-agent-bubble">
                <div style="font-size:0.8rem; color:#3b82f6; margin-bottom:6px;">🤖 <b>ProcureMind AI</b> • {msg['timestamp']}</div>
                <div>{msg['text']}</div>
            </div>
            """, unsafe_allow_html=True)
            
            # Transparent Agent Execution Trace Accordion
            if msg.get("agentTrace"):
                with st.expander("🔍 View Transparent Agent Execution Trace", expanded=False):
                    trace_html = "<br>".join([f"• ⚙️ <code>{t}</code>" for t in msg["agentTrace"]])
                    st.markdown(f"<div class='trace-box'>{trace_html}</div>", unsafe_allow_html=True)

    st.markdown("<br>", unsafe_allow_html=True)

    # Input Form
    with st.form("chat_form", clear_on_submit=True):
        user_input = st.text_input("Ask ProcureMind AI a question...", placeholder="Type your contract or vendor query here...", label_visibility="collapsed")
        c_submit, _ = st.columns([1, 4])
        with c_submit:
            submitted = st.form_submit_button("Send Query to AI Agent 🚀", type="primary", use_container_width=True)

    send_query = preset_clicked or (user_input if submitted else None)

    if send_query:
        now_time = datetime.now().strftime("%H:%M")
        st.session_state.chat_history.append({
            "sender": "user",
            "text": send_query,
            "timestamp": now_time
        })
        
        with st.spinner(f"Agent calling backend AI service (waiting up to {API_TIMEOUT}s)..."):
            response = send_chat_api(send_query, st.session_state.conversation_id)
            
            answer_text = response.get("answer") or response.get("response") or response.get("text") or "No answer returned from API Gateway."
            trace_list = response.get("agentTrace") or response.get("trace") or []
            
            st.session_state.chat_history.append({
                "sender": "agent",
                "text": answer_text,
                "timestamp": datetime.now().strftime("%H:%M"),
                "agentTrace": trace_list
            })
        st.rerun()

# --- 12. MAIN APP ROUTER ---
def main():
    oidc_auth.handle_callback()
    oidc_auth.require_login()

    render_header()
    st.markdown("---")
    
    selected_tab = render_sidebar()
    df = load_merged_contracts()

    # Route screen based on sidebar choice
    if selected_tab == "📊 BI Dashboard":
        render_dashboard(df)
    elif selected_tab == "🔍 Document Intelligence":
        render_document_intelligence(df)
    elif selected_tab == "🏢 Vendors Portfolio":
        render_vendors_portfolio(df)
    elif selected_tab == "📤 Ingestion Pipeline" and oidc_auth.can_write():
        render_upload_modal()
    elif selected_tab == "🤖 ProcureMind AI Assistant" and oidc_auth.can_write():
        render_agentic_chat_page()

if __name__ == "__main__":
    main()