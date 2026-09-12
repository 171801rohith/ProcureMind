/*
  ProcureMind Strict API Client
  - Connects to API Gateway via Vite Proxy or direct VITE_GATEWAY_URL.
  - Fixes CORS issues when fetching from http://localhost:5173.
  - Phase 5: attaches `Authorization: Bearer <access_token>` to every request and,
    on a 401, attempts one silent refresh + retry before giving up. No X-User-* headers.
*/

const GATEWAY_URL = import.meta.env.VITE_GATEWAY_URL !== undefined ? import.meta.env.VITE_GATEWAY_URL : '';
const API_TIMEOUT = parseInt(import.meta.env.VITE_API_TIMEOUT || '120000', 10);

// --- Auth wiring (populated by AppContext once the OIDC user is available) ---------------
let accessToken = null;
let onAuthExpired = null;

/** Set/clear the bearer token used for outgoing API calls. */
export function setAccessToken(token) {
  accessToken = token || null;
}

/**
 * Register the "token expired" handler.
 * @param {() => Promise<boolean>} fn resolves true when a fresh access token is available
 *   (already pushed via setAccessToken), false when the user must re-authenticate
 *   (fn is expected to trigger the sign-in redirect itself).
 */
export function setOnAuthExpired(fn) {
  onAuthExpired = fn;
}

function authHeader() {
  return accessToken ? { Authorization: `Bearer ${accessToken}` } : {};
}

async function apiFetch(url, options = {}, allowReauth = true) {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), API_TIMEOUT);

  try {
    const response = await fetch(url, {
      ...options,
      signal: controller.signal,
      headers: {
        'Accept': 'application/json',
        ...authHeader(),
        ...(options.headers || {}),
      },
    });
    clearTimeout(timeoutId);

    if (response.status === 401 && allowReauth && onAuthExpired) {
      const refreshed = await onAuthExpired();
      if (refreshed) {
        return apiFetch(url, options, false);
      }
      return null;
    }

    if (response.ok) {
      return await response.json();
    }
    console.warn(`API returned HTTP ${response.status} for ${url}`);
  } catch (err) {
    console.error(`API Fetch Error for ${url}:`, err);
  }

  return null;
}

async function authedFetch(url, options) {
  let response = await fetch(url, { ...options, headers: { ...(options.headers || {}), ...authHeader() } });
  if (response.status === 401 && onAuthExpired) {
    const refreshed = await onAuthExpired();
    if (refreshed) {
      response = await fetch(url, { ...options, headers: { ...(options.headers || {}), ...authHeader() } });
    }
  }
  return response;
}

/** Best-effort parse of an error body; a non-JSON response must not mask the status. */
async function readProblem(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

/** Builds an Error carrying the HTTP status plus any per-field validation messages. */
function apiError(response, problem, fallback) {
  const error = new Error(problem?.message || fallback);
  error.status = response.status;
  error.fieldErrors = problem?.fieldErrors || null;
  return error;
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
    const response = await authedFetch(url, { method: 'POST', body: formData });

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

  // --- ADMIN-only user administration (auth-service, proxied by the gateway) -------------

  /**
   * List users. The response never contains password hashes; auth-service maps entities to
   * a UserResponse of id, username, email, enabled and roles.
   * @throws Error with a `status` field so the caller can distinguish 403 from a failure.
   */
  async listUsers() {
    const response = await authedFetch(`${GATEWAY_URL}/api/users`, { method: 'GET' });
    if (response.ok) {
      const users = await response.json();
      return Array.isArray(users) ? users : [];
    }
    throw apiError(response, await readProblem(response), 'Unable to load users.');
  },

  /**
   * Create a user. Authorization is enforced by the gateway and again by auth-service, so a
   * non-admin reaching this call still gets a 403.
   */
  async createUser({ username, password, email, roles }) {
    const response = await authedFetch(`${GATEWAY_URL}/api/users`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, email: email || null, roles }),
    });
    if (response.ok) {
      return await response.json();
    }
    throw apiError(response, await readProblem(response), 'Unable to create the user.');
  },

  async sendChatMessage(userMessage, conversationId) {
    const url = `${GATEWAY_URL}/api/analysis/chat`;
    const response = await authedFetch(url, {
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
