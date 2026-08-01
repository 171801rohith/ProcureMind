import streamlit as st
import requests
import pandas as pd
import plotly.express as px
from datetime import datetime

# --- CONFIGURATION ---
st.set_page_config(page_title="ProcureMind Dashboard", layout="wide", initial_sidebar_state="expanded")
GATEWAY_URL = "http://localhost:8080"

# --- DATA FETCHING (The CQRS Read Models) ---
@st.cache_data(ttl=10) # Cache data for 10 seconds to avoid spamming the backend
def fetch_contracts():
    try:
        response = requests.get(f"{GATEWAY_URL}/api/contracts")
        if response.status_code == 200:
            return response.json()
        return []
    except Exception as e:
        st.error(f"Failed to connect to API Gateway: {e}")
        return []

@st.cache_data(ttl=10)
def fetch_analysis(contract_id):
    try:
        response = requests.get(f"{GATEWAY_URL}/api/analysis/{contract_id}")
        if response.status_code == 200:
            return response.json()
        return None
    except:
        return None

# --- DATA PROCESSING ---
def load_dashboard_data():
    contracts = fetch_contracts()
    dashboard_data = []
    
    for c in contracts:
        # For every contract, try to fetch its AI analysis
        analysis = fetch_analysis(c["id"])
        
        # Merge the data from contract-service and ai-service
        row = {
            "Contract ID": c["id"][:8].upper(), # Shorten UUID for display
            "Vendor": c["vendorName"],
            "Status": c["status"],
            "Uploaded At": datetime.fromisoformat(c["uploadedAt"]).strftime("%b %d, %Y"),
            "Risk Score": analysis["riskScore"] if analysis else None,
            "Recommendation": analysis["recommendation"] if analysis else "Pending Analysis",
            "Risks": analysis["risks"] if analysis else []
        }
        dashboard_data.append(row)
        
    return pd.DataFrame(dashboard_data)

# --- UI RENDERING ---
def render_sidebar():
    with st.sidebar:
        st.title("🛡️ ProcureMind")
        st.markdown("---")
        st.button("📊 Dashboard", use_container_width=True, type="primary")
        st.button("📄 Contracts", use_container_width=True)
        st.button("🏢 Vendors", use_container_width=True)
        st.button("⚖️ Comparisons", use_container_width=True)
        st.markdown("---")
        st.success("🟢 All Services Healthy")

def render_kpi_cards(df):
    total_contracts = len(df)
    pending_review = len(df[df["Status"] != "ANALYZED"])
    
    # Calculate metrics based on available AI data
    analyzed_df = df.dropna(subset=["Risk Score"])
    high_risk = len(analyzed_df[analyzed_df["Risk Score"] >= 7.0])
    avg_risk = round(analyzed_df["Risk Score"].mean(), 1) if not analyzed_df.empty else 0.0

    col1, col2, col3, col4 = st.columns(4)
    with col1:
        st.metric("Total Contracts", total_contracts)
    with col2:
        st.metric("Pending Review", pending_review)
    with col3:
        st.metric("High Risk Contracts", high_risk, delta="Requires Attention", delta_color="inverse")
    with col4:
        st.metric("Avg. Risk Score", f"{avg_risk} / 10")

def render_main_content(df):
    col_left, col_right = st.columns([2, 1])
    
    with col_left:
        st.subheader("Recent Contracts")
        # Format the dataframe for display
        display_df = df[["Contract ID", "Vendor", "Risk Score", "Status", "Uploaded At"]].copy()
        
        # Use Streamlit's native dataframe rendering with styling
        st.dataframe(
            display_df.style.highlight_null(color='gray'),
            use_container_width=True,
            hide_index=True
        )

    with col_right:
        st.subheader("Risk Distribution")
        # Aggregate severities from the nested risks arrays
        severities = []
        for risks_list in df["Risks"].dropna():
            for risk in risks_list:
                severities.append(risk["severity"])
        
        if severities:
            sev_counts = pd.Series(severities).value_counts().reset_index()
            sev_counts.columns = ['Severity', 'Count']
            
            fig = px.pie(sev_counts, values='Count', names='Severity', hole=0.6, 
                         color='Severity', color_discrete_map={'HIGH':'red', 'MEDIUM':'orange', 'LOW':'green'})
            fig.update_layout(margin=dict(t=0, b=0, l=0, r=0), height=250)
            st.plotly_chart(fig, use_container_width=True)
        else:
            st.info("No risk data available to chart.")

# --- APP EXECUTION ---
def main():
    render_sidebar()
    st.header("UI / Dashboard")
    
    df = load_dashboard_data()
    
    if df.empty:
        st.warning("No contracts found. Please upload a contract to begin.")
    else:
        render_kpi_cards(df)
        st.markdown("---")
        render_main_content(df)

if __name__ == "__main__":
    main()