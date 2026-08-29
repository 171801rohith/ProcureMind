/*
  ProcureMind Strict API Client
  - Connects to API Gateway via Vite Proxy or direct VITE_GATEWAY_URL.
  - Fixes CORS issues when fetching from http://localhost:5173.
*/

const GATEWAY_URL = import.meta.env.VITE_GATEWAY_URL !== undefined ? import.meta.env.VITE_GATEWAY_URL : '';
const API_TIMEOUT = parseInt(import.meta.env.VITE_API_TIMEOUT || '120000', 10);

async function apiFetch(url, options = {}) {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), API_TIMEOUT);

  try {
    const response = await fetch(url, {
      ...options,
      signal: controller.signal,
      headers: {
        'Accept': 'application/json',
        ...(options.headers || {}),
      },
    });
    clearTimeout(timeoutId);

    if (response.ok) {
      return await response.json();
    } else {
      console.warn(`API returned HTTP ${response.status} for ${url}`);
    }
  } catch (err) {
    console.error(`API Fetch Error for ${url}:`, err);
  }

  return null;
}

export const ApiClient = {
  async getDashboardMetrics() {
    const url = `${GATEWAY_URL}/api/analysis/dashboard-metrics`;
    return await apiFetch(url);
  },

  async getFinancialExposure() {
    const url = `${GATEWAY_URL}/api/analysis/financial-exposure`;
    const res = await apiFetch(url);
    return Array.isArray(res) ? res : [];
  },

  async getRiskDistribution() {
    const url = `${GATEWAY_URL}/api/analysis/risks/distribution`;
    const res = await apiFetch(url);
    return Array.isArray(res) ? res : [];
  },

  async getContracts() {
    const url = `${GATEWAY_URL}/api/contracts`;
    const res = await apiFetch(url);
    return Array.isArray(res) ? res : [];
  },

  async getContractAnalysis(contractId) {
    if (!contractId) return null;
    const url = `${GATEWAY_URL}/api/analysis/${contractId}`;
    return await apiFetch(url);
  },

  async getMergedContracts() {
    const contracts = await this.getContracts();
    if (!contracts || !Array.isArray(contracts)) return [];

    const mergedList = await Promise.all(
      contracts.map(async (contract) => {
        try {
          const intelligence = await this.getContractAnalysis(contract.id);
          return {
            ...contract,
            riskScore: intelligence?.riskScore ?? contract.riskScore ?? 0,
            recommendation: intelligence?.recommendation ?? 'No analysis recommendation.',
            summary: intelligence?.summary ?? contract.summary ?? 'No summary available.',
            highRiskCount: intelligence?.highRiskCount ?? 0,
            contractType: contract.contractType || intelligence?.contractType || 'Agreement',
            amount: contract.amount || intelligence?.amount || 0,
          };
        } catch {
          return {
            ...contract,
            riskScore: contract.riskScore ?? 0,
            recommendation: 'Analysis pending.',
            summary: 'Uploaded agreement awaiting analysis.',
            highRiskCount: 0,
            contractType: contract.contractType || 'Agreement',
            amount: contract.amount || 0,
          };
        }
      })
    );
    return mergedList;
  },

  async getContractTOC(contractId) {
    if (!contractId) return [];
    const url = `${GATEWAY_URL}/api/analysis/${contractId}/toc`;
    const res = await apiFetch(url);
    return Array.isArray(res) ? res : [];
  },

  async getNodeDetail(nodeId) {
    if (!nodeId) return null;
    const url = `${GATEWAY_URL}/api/analysis/node/${nodeId}`;
    return await apiFetch(url);
  },

  async getContractRisks(contractId) {
    if (!contractId) return [];
    const url = `${GATEWAY_URL}/api/analysis/${contractId}/risks`;
    const res = await apiFetch(url);
    return Array.isArray(res) ? res : [];
  },

  async uploadContract(file, vendorName, contractType = 'MSA', amount = 0) {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('vendorName', vendorName || 'Unknown Vendor');
    formData.append('contractType', contractType);
    formData.append('amount', amount.toString());

    const url = `${GATEWAY_URL}/api/contracts/upload`;
    const response = await fetch(url, {
      method: 'POST',
      body: formData,
    });

    if (response.ok) {
      return await response.json();
    }
    throw new Error(`Upload failed with HTTP ${response.status}`);
  },

  async checkContractStatus(id) {
    if (!id) return null;
    const url = `${GATEWAY_URL}/api/contracts/${id}/status`;
    return await apiFetch(url);
  },

  async sendChatMessage(userMessage, conversationId) {
    const url = `${GATEWAY_URL}/api/analysis/chat`;
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userMessage, conversationId }),
    });

    if (response.ok) {
      return await response.json();
    }
    throw new Error(`Chat API failed with HTTP ${response.status}`);
  },
};
