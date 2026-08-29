import React from 'react';
import { useApp } from '../../context/AppContext';
import { Button } from '../ui/Button';
import { Menu, RefreshCw, Upload, Radio } from 'lucide-react';

export function Header() {
  const { setUploadModalOpen, refreshData, isRefreshing, activeTab, setIsMobileSidebarOpen } = useApp();

  const tabTitles = {
    dashboard: 'BI Executive Dashboard',
    intelligence: 'Document Intelligence',
    vendors: 'Vendor Portfolio',
    chat: 'AI Assistant Workspace'
  };

  return (
    <header className="h-16 border-b border-slate-800 bg-slate-950/90 backdrop-blur-md px-4 sm:px-6 flex items-center justify-between sticky top-0 z-30">
      <div className="flex items-center space-x-3">
        {/* Mobile Hamburger Menu Toggle */}
        <button
          onClick={() => setIsMobileSidebarOpen(true)}
          className="lg:hidden p-2 rounded-lg text-slate-400 hover:text-slate-200 hover:bg-slate-900 transition-colors"
          aria-label="Toggle navigation menu"
        >
          <Menu className="w-5 h-5" />
        </button>

        <h1 className="text-base sm:text-lg lg:text-xl font-bold text-slate-100 truncate">
          {tabTitles[activeTab] || 'Dashboard'}
        </h1>

        <span className="hidden xl:inline-flex items-center gap-1.5 text-xs font-semibold px-2.5 py-1 rounded-full bg-blue-500/10 text-blue-400 border border-blue-500/20">
          <Radio className="w-3 h-3 animate-pulse text-blue-400" /> Gateway Active
        </span>
      </div>

      <div className="flex items-center space-x-2 sm:space-x-3">
        <Button
          variant="secondary"
          size="sm"
          onClick={refreshData}
          isLoading={isRefreshing}
          title="Refresh Data"
        >
          <RefreshCw className={`w-4 h-4 ${isRefreshing ? 'animate-spin' : ''}`} />
        </Button>

        <Button
          variant="primary"
          size="sm"
          onClick={() => setUploadModalOpen(true)}
          className="gap-1.5 sm:gap-2 px-3 sm:px-4"
        >
          <Upload className="w-4 h-4" />
          <span className="hidden sm:inline">Upload Contract</span>
          <span className="sm:hidden">Upload</span>
        </Button>
      </div>
    </header>
  );
}
