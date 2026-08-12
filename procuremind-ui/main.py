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

# --- 1. ENVIRONMENT CONFIGURATION & SETUP ---
load_dotenv()
GATEWAY_URL = os.getenv("GATEWAY_URL", "http://localhost:8080")
# Extended timeout so the frontend waits for long-running LLM and backend microservices
API_TIMEOUT = int(os.getenv("API_TIMEOUT", "120"))

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
        background-color: #0b0f19;
        color: #f1f5f9;
        font-family: 'Inter', system-ui, -apple-system, sans-serif;
    }
    
    /* Header Styling */
    header[data-testid="stHeader"] {
        background-color: #0b0f19;
    }
    
    /* Sidebar Styling */
    section[data-testid="stSidebar"] {
        background-color: #0f172a;
        border-right: 1px solid #1e293b;
    }
    
    /* Executive Metric Card Styles */
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
        font-size: 0.85rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
    }
    .metric-value {
        color: #f8fafc;
        font-size: 1.8rem;
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

    /* Custom Chat Message Bubbles */
    .chat-user {
        background-color: #1e293b;
        border: 1px solid #334155;
        border-radius: 12px 12px 2px 12px;
        padding: 12px 16px;
        margin-bottom: 10px;
        color: #f1f5f9;
    }
    .chat-agent {
        background-color: #0f172a;
        border: 1px solid #1e3a8a;
        border-radius: 12px 12px 12px 2px;
        padding: 12px 16px;
        margin-bottom: 10px;
        color: #e2e8f0;
        box-shadow: 0 0 15px -3px rgba(59, 130, 246, 0.15);
    }
    
    /* Trace Expander Container */
    .trace-box {
        background-color: #070b14;
        border-left: 3px solid #3b82f6;
        padding: 10px 14px;
        font-family: 'Fira Code', monospace;
        font-size: 0.82rem;
        color: #93c5fd;
        border-radius: 4px;
        margin-top: 8px;
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
            "text": "I am ProcureMind AI. Ask me about your contract portfolio, risk exposure, or specific vendor terms.",
            "timestamp": datetime.now().strftime("%H:%M"),
            "agentTrace": ["Initialized ProcureMind Agentic Assistant session", "Bound session UUID"]
        }
    ]

# --- 4. STRICT BACKEND API CALLERS (NO MOCK FALLBACKS) ---
def safe_get_api(endpoint: str):
    url = f"{GATEWAY_URL}{endpoint}"
    try:
        res = requests.get(url, timeout=API_TIMEOUT)
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
    files = {"file": (file.name, file, "application/pdf")}
    data = {
        "vendorName": vendor_name,
        "contractType": contract_type,
        "amount": str(amount)
    }
    try:
        res = requests.post(url, files=files, data=data, timeout=API_TIMEOUT)
        if res.status_code in [200, 201]:
            return res.json()
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
        res = requests.post(url, json=payload, timeout=API_TIMEOUT)
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
        st.markdown("<h2 style='margin:0; color:#f8fafc; font-weight:800;'>🛡️ ProcureMind <span style='font-size:0.9rem; color:#3b82f6; border:1px solid #1d4ed8; padding:2px 8px; border-radius:12px;'>BFF Enterprise</span></h2>", unsafe_allow_html=True)
        st.caption("AI-Powered Legal Contract Analysis & Financial Risk Platform")
    
    with col2:
        st.markdown(f"<div style='text-align:right; font-size:0.8rem; color:#94a3b8; margin-top:8px;'>Gateway: <code>{GATEWAY_URL}</code></div>", unsafe_allow_html=True)
    
    with col3:
        if st.button("➕ Upload Contract", type="primary", use_container_width=True):
            st.session_state.show_upload_modal = True

def render_sidebar():
    with st.sidebar:
        st.markdown("### 📌 Navigation")
        selected_tab = st.radio(
            "Select Module",
            ["📊 BI Dashboard", "🔍 Document Intelligence", "🏢 Vendors Portfolio", "📤 Ingestion Pipeline"],
            index=0
        )
        st.markdown("---")
        
        st.markdown("#### ⚙️ Backend Connectivity")
        st.markdown(f"🔗 **API Gateway:** `http://localhost:8080`")
        st.markdown(f"⏳ **Request Timeout:** `{API_TIMEOUT}s`")
        st.markdown("---")
        
        st.markdown("#### 🤖 Agentic Assistant")
        st.caption(f"Session UUID: `{st.session_state.conversation_id[:13]}...`")
        show_chat = st.checkbox("Open Chat Drawer Panel", value=True)
        
        return selected_tab, show_chat

# --- 7. UPLOAD MODAL COMPONENT (FR-2) ---
def render_upload_modal():
    st.markdown("---")
    st.subheader("📤 Ingest New Legal Contract")
    st.caption("Upload a `.pdf` agreement to trigger OCR text extraction, clause indexing, and AI risk scoring via backend API.")
    
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
                    if res and ("id" in res or "contractId" in res):
                        cid = res.get("id") or res.get("contractId")
                        st.success(f"File uploaded successfully! Assigned ID: `{cid}`")
                        
                        progress_bar = st.progress(50, text="Checking ingestion status from API...")
                        status_res = check_status_api(cid)
                        progress_bar.progress(100, text=f"Status: {status_res.get('status', 'ANALYZED')}!")
                        st.balloons()
                        st.rerun()
                    else:
                        st.error("Upload failed or backend API returned an invalid response.")

# --- 8. BI DASHBOARD COMPONENT (FR-1) ---
def render_dashboard(df):
    metrics = fetch_dashboard_metrics_api()
    exposure_data = fetch_financial_exposure_api()
    distribution_data = fetch_risk_distribution_api()

    # --- KPI STRIP (4 Cards) ---
    c1, c2, c3, c4 = st.columns(4)
    with c1:
        st.markdown(f"""
        <div class="metric-card">
            <div class="metric-title">Total Ingested Contracts</div>
            <div class="metric-value">{metrics['totalAnalyzed']}</div>
            <div class="metric-subtitle" style="color:#10b981;">From Backend API</div>
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
        <div class="metric-card">
            <div class="metric-title">Financial Risk Exposure</div>
            <div class="metric-value" style="color:#3b82f6;">${total_exp/1e6:.2f}M</div>
            <div class="metric-subtitle" style="color:#94a3b8;">From Backend Exposure API</div>
        </div>
        """, unsafe_allow_html=True)

    st.markdown("<br>", unsafe_allow_html=True)

    # --- CHARTS SECTION (2 COLUMNS) ---
    chart_col1, chart_col2 = st.columns([3, 2])

    with chart_col1:
        st.markdown("#### 📈 Financial Exposure vs. Risk Score Scatter Plot")
        if exposure_data:
            exp_df = pd.DataFrame(exposure_data)
            # Ensure expected numeric columns
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
                fig_scatter.add_vline(x=7.0, line_dash="dash", line_color="#ef4444", annotation_text="High Risk Cutoff")
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
                color_map = {"HIGH": "#ef4444", "MEDIUM": "#f59e0b", "LOW": "#10b981"}
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
        
        if toc_nodes:
            node_titles = [f"{n.get('id', idx)}: {n.get('title', 'Section')}" for idx, n in enumerate(toc_nodes)]
            selected_node_str = st.radio("Inspect Clause Node:", node_titles)
            selected_node_id = selected_node_str.split(":")[0] if selected_node_str else ""
        else:
            st.info("No TOC structure returned from API for this contract.")
            selected_node_id = ""

    with col_pane:
        st.markdown("#### 📖 Legal Reading Pane")
        if selected_node_id:
            node_detail = fetch_node_detail_api(selected_node_id)
            if node_detail:
                st.markdown(f"""
                <div style="background-color:#0f172a; border:1px solid #1e293b; padding:16px; border-radius:8px;">
                    <h5 style="color:#3b82f6; margin-top:0;">{node_detail.get('title', 'Section Detail')}</h5>
                    <p style="font-family:serif; font-size:0.95rem; color:#cbd5e1; line-height:1.6;">
                    "{node_detail.get('text', 'No clause text returned.')}"
                    </p>
                    <hr style="border-color:#334155;">
                    <h5 style="color:#10b981; margin-bottom:4px;">🤖 AI Clause Summary</h5>
                    <p style="font-size:0.88rem; color:#94a3b8;">
                    {node_detail.get('summary', 'No summary available.')}
                    </p>
                </div>
                """, unsafe_allow_html=True)
            else:
                st.info(f"No detail returned from backend for node `{selected_node_id}`.")
        else:
            st.info("Select a node from the TOC tree to read legal text.")

    with col_risks:
        st.markdown("#### 🚨 Identified Risks Panel")
        risks_data = fetch_contract_risks_api(selected_contract_id)
        if risks_data:
            for r in risks_data:
                sev = r.get("severity", "MEDIUM").upper()
                badge_class = "badge-high" if sev == "HIGH" else "badge-medium" if sev == "MEDIUM" else "badge-low"
                st.markdown(f"""
                <div style="background-color:rgba(15, 23, 42, 0.8); border:1px solid #334155; padding:12px; border-radius:8px; margin-bottom:10px;">
                    <span class="{badge_class}">{sev} RISK</span>
                    <h5 style="margin:6px 0; color:#f8fafc;">{r.get('category', 'Risk Category')}</h5>
                    <p style="font-size:0.8rem; color:#94a3b8; margin-bottom:6px;">{r.get('description', 'No description.')}</p>
                    <b style="font-size:0.78rem; color:#3b82f6;">💡 Action: {r.get('recommendation', 'N/A')}</b>
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

# --- 11. AGENTIC CHAT ASSISTANT DRAWER (FR-4) ---
def render_agentic_chat_drawer():
    st.markdown("---")
    st.subheader("🤖 ProcureMind Agentic Assistant")
    st.caption(f"Session ID: `{st.session_state.conversation_id}`")

    # Preset Quick Suggestion Chips
    st.markdown("**Quick Prompts:**")
    cp1, cp2, cp3 = st.columns(3)
    preset_clicked = None
    with cp1:
        if st.button("🚨 What is our highest risk contract?", use_container_width=True):
            preset_clicked = "What is our highest risk contract?"
    with cp2:
        if st.button("🏢 Show me Acme Corp MSA risks", use_container_width=True):
            preset_clicked = "Show me Acme Corp MSA risks"
    with cp3:
        if st.button("⚖️ Summarize indemnification clauses", use_container_width=True):
            preset_clicked = "Summarize indemnification clauses"

    # Chat Message Container
    chat_container = st.container()
    with chat_container:
        for msg in st.session_state.chat_history:
            if msg["sender"] == "user":
                st.markdown(f"""
                <div class="chat-user">
                    <b>👤 You ({msg['timestamp']}):</b><br>{msg['text']}
                </div>
                """, unsafe_allow_html=True)
            else:
                st.markdown(f"""
                <div class="chat-agent">
                    <b>🤖 ProcureMind AI ({msg['timestamp']}):</b><br>{msg['text']}
                </div>
                """, unsafe_allow_html=True)
                
                # Transparent Agent Trace Accordion
                if msg.get("agentTrace"):
                    with st.expander("🔍 View Transparent Agent Execution Trace", expanded=False):
                        trace_html = "<br>".join([f"• <code>{t}</code>" for t in msg["agentTrace"]])
                        st.markdown(f"<div class='trace-box'>{trace_html}</div>", unsafe_allow_html=True)

    # Chat Input Box
    user_input = st.text_input("Ask ProcureMind AI a question...", key="chat_input_text", placeholder="Type your contract query here...")
    
    send_query = preset_clicked or user_input
    if st.button("Send Query to AI Agent", type="primary") or preset_clicked:
        if send_query:
            now_time = datetime.now().strftime("%H:%M")
            st.session_state.chat_history.append({
                "sender": "user",
                "text": send_query,
                "timestamp": now_time
            })
            
            with st.spinner(f"Agent calling backend AI service (waiting up to {API_TIMEOUT}s)..."):
                response = send_chat_api(send_query, st.session_state.conversation_id)
                
                st.session_state.chat_history.append({
                    "sender": "agent",
                    "text": response.get("answer", "No answer returned from API Gateway."),
                    "timestamp": datetime.now().strftime("%H:%M"),
                    "agentTrace": response.get("agentTrace", [])
                })
            st.rerun()

# --- 12. MAIN APP ROUTER ---
def main():
    render_header()
    st.markdown("---")
    
    selected_tab, show_chat = render_sidebar()
    df = load_merged_contracts()

    # Route screen based on sidebar choice
    if selected_tab == "📊 BI Dashboard":
        render_dashboard(df)
    elif selected_tab == "🔍 Document Intelligence":
        render_document_intelligence(df)
    elif selected_tab == "🏢 Vendors Portfolio":
        render_vendors_portfolio(df)
    elif selected_tab == "📤 Ingestion Pipeline":
        render_upload_modal()

    # Persistent Agentic Chat Assistant Drawer
    if show_chat:
        render_agentic_chat_drawer()

if __name__ == "__main__":
    main()