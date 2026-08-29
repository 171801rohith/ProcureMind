import React from 'react';
import { useApp } from '../../context/AppContext';
import { Card } from '../ui/Card';
import { Badge } from '../ui/Badge';
import { Building2, DollarSign, AlertTriangle } from 'lucide-react';
import { ResponsiveContainer, BarChart, Bar, XAxis, YAxis, Tooltip, Cell } from 'recharts';

export function VendorPortfolio() {
  const { contracts } = useApp();

  const vendorMap = {};
  contracts.forEach((c) => {
    const v = c.vendorName || 'Unknown Vendor';
    if (!vendorMap[v]) {
      vendorMap[v] = { vendorName: v, count: 0, totalValue: 0, scores: [], highRisks: 0 };
    }
    vendorMap[v].count += 1;
    vendorMap[v].totalValue += (c.amount || 0);
    vendorMap[v].scores.push(c.riskScore || 5.0);
    if (c.riskScore >= 7.0) vendorMap[v].highRisks += 1;
  });

  const vendorSummaries = Object.values(vendorMap).map((v) => {
    const avgScore = Number((v.scores.reduce((a, b) => a + b, 0) / (v.scores.length || 1)).toFixed(1));
    return {
      ...v,
      avgScore,
      rating: avgScore >= 7.0 ? 'HIGH' : avgScore >= 4.0 ? 'MEDIUM' : 'LOW'
    };
  });

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2">
          <Card className="p-0 overflow-hidden">
            <div className="p-4 border-b border-slate-800 flex items-center justify-between">
              <h3 className="text-base font-bold text-slate-100 flex items-center gap-2">
                <Building2 className="w-5 h-5 text-blue-400" /> Vendor Portfolio Overview
              </h3>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm text-slate-300">
                <thead className="bg-slate-950/60 text-xs uppercase text-slate-400 border-b border-slate-800">
                  <tr>
                    <th className="px-4 py-3">Vendor Name</th>
                    <th className="px-4 py-3">Contracts</th>
                    <th className="px-4 py-3">Total Value ($)</th>
                    <th className="px-4 py-3">Avg Risk Score</th>
                    <th className="px-4 py-3">Rating</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-800/60">
                  {vendorSummaries.map((vendor, idx) => (
                    <tr key={idx} className="hover:bg-slate-800/30 transition-colors">
                      <td className="px-4 py-3 font-semibold text-slate-200">{vendor.vendorName}</td>
                      <td className="px-4 py-3">{vendor.count}</td>
                      <td className="px-4 py-3 font-mono">${vendor.totalValue.toLocaleString()}</td>
                      <td className="px-4 py-3 font-semibold">{vendor.avgScore} / 10</td>
                      <td className="px-4 py-3">
                        <Badge variant={vendor.rating === 'HIGH' ? 'high' : vendor.rating === 'MEDIUM' ? 'medium' : 'low'}>
                          {vendor.rating} RISK
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        </div>

        <div>
          <Card className="h-full flex flex-col">
            <h3 className="text-base font-bold text-slate-100 mb-2">Financial Commitment by Vendor</h3>
            <div className="flex-1 w-full min-h-[260px]">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={vendorSummaries} margin={{ top: 10, right: 10, left: 10, bottom: 40 }}>
                  <XAxis dataKey="vendorName" tick={{ fill: '#94a3b8', fontSize: 10 }} interval={0} angle={-35} textAnchor="end" />
                  <YAxis tickFormatter={(val) => `$${(val/1000000).toFixed(1)}M`} tick={{ fill: '#94a3b8', fontSize: 10 }} />
                  <Tooltip contentStyle={{ backgroundColor: '#0f172a', borderColor: '#334155', borderRadius: '8px' }} />
                  <Bar dataKey="totalValue" radius={[4, 4, 0, 0]}>
                    {vendorSummaries.map((entry, index) => (
                      <Cell key={`cell-${index}`} fill={entry.rating === 'HIGH' ? '#ef4444' : entry.rating === 'MEDIUM' ? '#f59e0b' : '#10b981'} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
}
