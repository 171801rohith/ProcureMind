import React from 'react';
import { useAuth } from 'react-oidc-context';
import { useApp } from '../../context/AppContext';
import { Button } from '../ui/Button';
import { Menu, RefreshCw, Upload, Radio, LogOut, User } from 'lucide-react';
import { AUTH_REQUIRED } from '../../auth/authConfig';
import { useRoles } from '../../auth/roles';

export function Header() {
  const { setUploadModalOpen, refreshData, isRefreshing, activeTab, setIsMobileSidebarOpen } = useApp();
  const auth = useAuth();
  const { canWrite } = useRoles();

  const username =
    auth.user?.profile?.preferred_username ||
    auth.user?.profile?.name ||
    auth.user?.profile?.sub;

  const tabTitles = {
    dashboard: 'BI Executive Dashboard',
    intelligence: 'Document Intelligence',
    vendors: 'Vendor Portfolio',
    chat: 'AI Assistant Workspace',
    admin: 'User Management'
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

        {canWrite && (
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
        )}

        {AUTH_REQUIRED && auth.isAuthenticated && (
          <div className="flex items-center gap-2 pl-2 sm:pl-3 border-l border-slate-800">
            <span className="hidden md:inline-flex items-center gap-1.5 text-xs font-medium text-slate-400 max-w-[10rem] truncate">
              <User className="w-3.5 h-3.5 flex-shrink-0" />
              {username}
            </span>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => auth.signoutRedirect()}
              title="Sign out"
              aria-label="Sign out"
            >
              <LogOut className="w-4 h-4" />
            </Button>
          </div>
        )}
      </div>
    </header>
  );
}
