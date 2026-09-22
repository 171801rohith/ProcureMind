import React, { createContext, useContext, useState, useEffect } from 'react';
import { useAuth } from 'react-oidc-context';
import { ApiClient, setAccessToken, setOnAuthExpired } from '../api/client';
import { AUTH_REQUIRED } from '../auth/authConfig';

const AppContext = createContext();

export function AppProvider({ children }) {
  const auth = useAuth();

  const [activeTab, setActiveTab] = useState('dashboard');
  const [contracts, setContracts] = useState([]);
  const [metrics, setMetrics] = useState({ totalAnalyzed: 0, highRiskCount: 0, averageRiskScore: 0 });
  const [exposureData, setExposureData] = useState([]);
  const [riskDistribution, setRiskDistribution] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState(null);

  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [isMobileSidebarOpen, setIsMobileSidebarOpen] = useState(false);

  const [conversationId] = useState(() => 'conv-' + Math.random().toString(36).substr(2, 9));
  const [chatHistory, setChatHistory] = useState([
    {
      sender: 'agent',
      text: 'I am ProcureMind AI. Ask me about your contract portfolio, risk exposure, or specific vendor terms.',
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      agentTrace: ['Initialized ProcureMind Agentic Assistant session', 'Bound session UUID']
    }
  ]);

  // Keep the API client's bearer token in sync with the OIDC user, and give it a
  // "token expired" handler that tries one silent renew before forcing a redirect.
  useEffect(() => {
    setAccessToken(auth.user?.access_token);
    setOnAuthExpired(async () => {
      try {
        const user = await auth.signinSilent();
        if (user?.access_token) {
          setAccessToken(user.access_token);
          return true;
        }
      } catch {
        // fall through to a full redirect
      }
      auth.signinRedirect();
      return false;
    });
  }, [auth, auth.user?.access_token]);

  const loadData = async (showRefresh = false) => {
    if (showRefresh) setIsRefreshing(true);
    else setIsLoading(true);
    setError(null);

    try {
      const [merged, met, exp, dist] = await Promise.all([
        ApiClient.getMergedContracts(),
        ApiClient.getDashboardMetrics(),
        ApiClient.getFinancialExposure(),
        ApiClient.getRiskDistribution()
      ]);

      setContracts(merged || []);
      if (met) setMetrics(met);
      if (exp) setExposureData(exp);
      if (dist) setRiskDistribution(dist);
    } catch (err) {
      setError('Unable to reach ProcureMind API Gateway.');
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
    }
  };

  useEffect(() => {
    if (!AUTH_REQUIRED || auth.isAuthenticated) {
      loadData();
    }
  }, [auth.isAuthenticated]);

  return (
    <AppContext.Provider
      value={{
        activeTab,
        setActiveTab,
        contracts,
        metrics,
        exposureData,
        riskDistribution,
        isLoading,
        isRefreshing,
        error,
        refreshData: () => loadData(true),
        uploadModalOpen,
        setUploadModalOpen,
        isMobileSidebarOpen,
        setIsMobileSidebarOpen,
        conversationId,
        chatHistory,
        setChatHistory
      }}
    >
      {children}
    </AppContext.Provider>
  );
}

export function useApp() {
  return useContext(AppContext);
}
