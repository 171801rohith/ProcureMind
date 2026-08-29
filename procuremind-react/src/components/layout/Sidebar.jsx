import React from 'react';
import { useApp } from '../../context/AppContext';
import { Shield, LayoutDashboard, FileSearch, Building2, Bot, Upload, X } from 'lucide-react';

export function Sidebar() {
  const { activeTab, setActiveTab, setUploadModalOpen, conversationId, isMobileSidebarOpen, setIsMobileSidebarOpen } = useApp();

  const navItems = [
    { id: 'dashboard', label: 'BI Dashboard', icon: LayoutDashboard },
    { id: 'intelligence', label: 'Document Intelligence', icon: FileSearch },
    { id: 'vendors', label: 'Vendors Portfolio', icon: Building2 },
    { id: 'chat', label: 'AI Chat Assistant', icon: Bot }
  ];

  const handleNavClick = (id) => {
    setActiveTab(id);
    setIsMobileSidebarOpen(false);
  };

  const sidebarContent = (
    <div className="flex flex-col h-full bg-slate-950">
      {/* Brand Header */}
      <div className="h-16 border-b border-slate-800 px-5 flex items-center justify-between">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-blue-600/20 border border-blue-500/30 text-blue-400">
            <Shield className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-base font-bold text-slate-100 leading-none">ProcureMind</h2>
            <span className="text-[10px] font-semibold text-blue-400 tracking-wider uppercase">Enterprise SaaS</span>
          </div>
        </div>

        {/* Close button for mobile menu */}
        <button
          onClick={() => setIsMobileSidebarOpen(false)}
          className="lg:hidden p-1.5 rounded-lg text-slate-400 hover:text-slate-200 hover:bg-slate-900"
        >
          <X className="w-5 h-5" />
        </button>
      </div>

      {/* Navigation Links */}
      <div className="p-4 flex-1 space-y-1 overflow-y-auto">
        <div className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider px-3 mb-2">
          Navigation Modules
        </div>
        {navItems.map((item) => {
          const Icon = item.icon;
          const isActive = activeTab === item.id;
          return (
            <button
              key={item.id}
              onClick={() => handleNavClick(item.id)}
              className={`w-full flex items-center space-x-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-all ${
                isActive
                  ? 'bg-blue-600 text-white shadow-lg shadow-blue-600/25'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900'
              }`}
            >
              <Icon className="w-4 h-4 shrink-0" />
              <span>{item.label}</span>
            </button>
          );
        })}
      </div>

      {/* Upload Action */}
      <div className="p-4 border-t border-slate-900">
        <button
          onClick={() => {
            setUploadModalOpen(true);
            setIsMobileSidebarOpen(false);
          }}
          className="w-full flex items-center justify-center space-x-2 py-2 px-4 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-800 text-sm font-medium text-slate-200 transition-colors"
        >
          <Upload className="w-4 h-4 text-blue-400" />
          <span>Ingest Contract</span>
        </button>
      </div>

      {/* System Status Footer */}
      <div className="p-4 border-t border-slate-800 bg-slate-900/40 text-xs space-y-1.5">
        <div className="flex items-center justify-between text-slate-400">
          <span>Gateway:</span>
          <span className="text-emerald-400 font-mono">Connected</span>
        </div>
        <div className="flex items-center justify-between text-slate-400">
          <span>Session:</span>
          <span className="text-slate-300 font-mono text-[10px]">{conversationId.substring(0, 8)}...</span>
        </div>
      </div>
    </div>
  );

  return (
    <>
      {/* Desktop Sidebar (lg+) */}
      <aside className="hidden lg:flex w-64 border-r border-slate-800 bg-slate-950 flex-col h-screen sticky top-0 shrink-0">
        {sidebarContent}
      </aside>

      {/* Mobile Slide-Over Drawer (< lg) */}
      {isMobileSidebarOpen && (
        <div className="fixed inset-0 z-50 lg:hidden flex">
          {/* Backdrop Overlay */}
          <div
            className="fixed inset-0 bg-slate-950/80 backdrop-blur-sm animate-fadeIn"
            onClick={() => setIsMobileSidebarOpen(false)}
          />

          {/* Drawer Panel */}
          <div className="relative flex-1 max-w-xs w-full bg-slate-950 border-r border-slate-800 shadow-2xl z-10">
            {sidebarContent}
          </div>
        </div>
      )}
    </>
  );
}
