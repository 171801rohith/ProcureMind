import React from 'react';
import { Card } from '../ui/Card';
import { Badge } from '../ui/Badge';
import { AlertTriangle, Lightbulb } from 'lucide-react';

export function RiskSidePanel({ risks }) {
  return (
    <Card className="h-full flex flex-col p-4">
      <div className="flex items-center space-x-2 pb-3 mb-3 border-b border-slate-800 text-slate-200">
        <AlertTriangle className="w-4 h-4 text-red-400" />
        <h3 className="text-sm font-bold uppercase tracking-wider">Identified Risks</h3>
      </div>

      <div className="flex-1 overflow-y-auto space-y-3 pr-1">
        {(!risks || risks.length === 0) ? (
          <div className="text-xs text-slate-500 py-4 text-center">
            No high risk flags identified for this agreement.
          </div>
        ) : (
          risks.map((risk, idx) => {
            const sev = (risk.severity || 'MEDIUM').toUpperCase();
            const badgeVariant = sev === 'HIGH' || sev === 'CRITICAL' ? 'high' : sev === 'MEDIUM' || sev === 'MODERATE' ? 'medium' : 'low';

            return (
              <div key={idx} className="bg-slate-950/80 border border-slate-800 rounded-lg p-3 space-y-2">
                <div className="flex items-center justify-between">
                  <Badge variant={badgeVariant}>{sev} RISK</Badge>
                  <span className="text-[10px] font-semibold text-slate-400">{risk.category || 'Risk Flag'}</span>
                </div>
                <p className="text-xs text-slate-300 leading-normal">{risk.description}</p>
                {risk.recommendation && (
                  <div className="flex items-start space-x-1.5 pt-1 text-[11px] text-blue-400 border-t border-slate-900">
                    <Lightbulb className="w-3.5 h-3.5 text-blue-400 shrink-0 mt-0.5" />
                    <span>{risk.recommendation}</span>
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>
    </Card>
  );
}
