import React from 'react';
import { AppProvider, useApp } from './context/AppContext';
import { Header } from './components/layout/Header';
import { Sidebar } from './components/layout/Sidebar';
import { KPICards } from './components/dashboard/KPICards';
import { ExposureChart } from './components/dashboard/ExposureChart';
import { RiskDonutChart } from './components/dashboard/RiskDonutChart';
import { RecentContractsTable } from './components/dashboard/RecentContractsTable';
import { IntelligenceScreen } from './components/intelligence/IntelligenceScreen';
import { VendorPortfolio } from './components/vendors/VendorPortfolio';
import { ChatInterface } from './components/chat/ChatInterface';
import { UploadModal } from './components/upload/UploadModal';

function MainContent() {
  const { activeTab, error } = useApp();

  return (
    <main className="flex-1 overflow-y-auto p-6 space-y-6">
      {error && (
        <div className="bg-amber-500/10 border border-amber-500/30 text-amber-300 px-4 py-3 rounded-xl text-sm flex items-center justify-between">
          <span>{error}</span>
        </div>
      )}

      {activeTab === 'dashboard' && (
        <div className="space-y-6">
          <KPICards />
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
            <div className="lg:col-span-7">
              <ExposureChart />
            </div>
            <div className="lg:col-span-5">
              <RiskDonutChart />
            </div>
          </div>
          <RecentContractsTable />
        </div>
      )}

      {activeTab === 'intelligence' && <IntelligenceScreen />}

      {activeTab === 'vendors' && <VendorPortfolio />}

      {activeTab === 'chat' && <ChatInterface />}

      <UploadModal />
    </main>
  );
}

export default function App() {
  return (
    <AppProvider>
      <div className="flex h-screen bg-slate-950 text-slate-100 font-sans overflow-hidden">
        <Sidebar />
        <div className="flex-1 flex flex-col min-w-0">
          <Header />
          <MainContent />
        </div>
      </div>
    </AppProvider>
  );
}
