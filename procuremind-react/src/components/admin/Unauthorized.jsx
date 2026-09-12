import React from 'react';
import { ShieldAlert, ArrowLeft } from 'lucide-react';
import { Card } from '../ui/Card';
import { Button } from '../ui/Button';
import { useApp } from '../../context/AppContext';

/**
 * Shown when a non-admin reaches an administrative screen.
 *
 * This is a courtesy, not a control. The gateway and auth-service both reject
 * `/api/users` for anything but an ADMIN token, so a user who bypasses this screen
 * still cannot read or change anything.
 */
export function Unauthorized({ requiredRole = 'ADMIN' }) {
  const { setActiveTab } = useApp();

  return (
    <div className="flex items-center justify-center py-16">
      <Card className="max-w-md text-center">
        <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-xl border border-amber-500/25 bg-amber-500/10">
          <ShieldAlert className="h-6 w-6 text-amber-400" />
        </div>
        <h2 className="text-lg font-bold text-slate-100">Access denied</h2>
        <p className="mt-2 text-sm text-slate-400">
          This area is restricted to users with the{' '}
          <span className="font-semibold text-slate-300">{requiredRole}</span> role. Ask a
          ProcureMind administrator if you need access.
        </p>
        <Button
          variant="secondary"
          size="sm"
          className="mt-6 gap-2"
          onClick={() => setActiveTab('dashboard')}
        >
          <ArrowLeft className="h-4 w-4" />
          Back to dashboard
        </Button>
      </Card>
    </div>
  );
}
