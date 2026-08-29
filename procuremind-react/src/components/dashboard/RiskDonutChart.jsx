import React from 'react';
import { useApp } from '../../context/AppContext';
import { Card } from '../ui/Card';
import { Skeleton } from '../ui/Skeleton';
import { ResponsiveContainer, PieChart, Pie, Cell, Tooltip, Legend } from 'recharts';

export function RiskDonutChart() {
  const { riskDistribution, isLoading } = useApp();

  if (isLoading) {
    return <Skeleton className="h-[300px] sm:h-[350px] lg:h-[380px] w-full" />;
  }

  const COLORS = {
    HIGH: '#ef4444',
    High: '#ef4444',
    MEDIUM: '#f59e0b',
    Medium: '#f59e0b',
    MODERATE: '#f59e0b',
    LOW: '#10b981',
    Low: '#10b981'
  };

  const total = riskDistribution.reduce((acc, item) => acc + (item.count || 0), 0);

  return (
    <Card className="h-[300px] sm:h-[350px] lg:h-[380px] flex flex-col p-4 sm:p-5">
      <div className="mb-2">
        <h3 className="text-sm sm:text-base font-bold text-slate-100">Risk Severity Breakdown</h3>
        <p className="text-[11px] sm:text-xs text-slate-400">Distribution across flagged risk severities</p>
      </div>

      <div className="flex-1 w-full min-h-0 relative">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={riskDistribution}
              cx="50%"
              cy="50%"
              innerRadius={55}
              outerRadius={85}
              paddingAngle={4}
              dataKey="count"
              nameKey="severity"
            >
              {riskDistribution.map((entry, index) => (
                <Cell key={`cell-${index}`} fill={COLORS[entry.severity] || '#3b82f6'} />
              ))}
            </Pie>
            <Tooltip
              contentStyle={{ backgroundColor: '#0f172a', borderColor: '#334155', borderRadius: '8px', color: '#f8fafc' }}
            />
            <Legend verticalAlign="bottom" height={32} iconType="circle" wrapperStyle={{ fontSize: '11px' }} />
          </PieChart>
        </ResponsiveContainer>

        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-center pointer-events-none pb-6">
          <div className="text-xl sm:text-2xl font-bold text-slate-100">{total}</div>
          <div className="text-[10px] sm:text-[11px] text-slate-400 font-medium uppercase">Total Risks</div>
        </div>
      </div>
    </Card>
  );
}
