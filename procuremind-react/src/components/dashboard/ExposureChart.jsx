import React from 'react';
import { useApp } from '../../context/AppContext';
import { Card } from '../ui/Card';
import { Skeleton } from '../ui/Skeleton';
import { ResponsiveContainer, ScatterChart, Scatter, XAxis, YAxis, Tooltip, ReferenceLine, ZAxis } from 'recharts';

export function ExposureChart() {
  const { exposureData, isLoading } = useApp();

  if (isLoading) {
    return <Skeleton className="h-[300px] sm:h-[350px] lg:h-[380px] w-full" />;
  }

  const formatYAxis = (tickItem) => `$${(tickItem / 1000000).toFixed(1)}M`;

  const CustomTooltip = ({ active, payload }) => {
    if (active && payload && payload.length) {
      const data = payload[0].payload;
      return (
        <div className="bg-slate-900 border border-slate-700 p-2.5 rounded-lg shadow-xl text-xs space-y-1">
          <div className="font-bold text-slate-200">{data.vendorName || 'Vendor'}</div>
          <div className="text-slate-400">{data.fileName}</div>
          <div className="text-blue-400">Value: ${(data.amount / 1000000).toFixed(2)}M</div>
          <div className={`font-semibold ${data.riskScore >= 7 ? 'text-red-400' : 'text-amber-400'}`}>
            Risk Score: {data.riskScore} / 10
          </div>
        </div>
      );
    }
    return null;
  };

  return (
    <Card className="h-[300px] sm:h-[350px] lg:h-[380px] flex flex-col p-4 sm:p-5">
      <div className="mb-2 sm:mb-4">
        <h3 className="text-sm sm:text-base font-bold text-slate-100">Financial Exposure vs. Risk Score</h3>
        <p className="text-[11px] sm:text-xs text-slate-400">Correlation between contract valuation ($) and AI risk score</p>
      </div>

      <div className="flex-1 w-full min-h-0">
        <ResponsiveContainer width="100%" height="100%">
          <ScatterChart margin={{ top: 10, right: 15, bottom: 15, left: 0 }}>
            <XAxis
              type="number"
              dataKey="riskScore"
              name="Risk Score"
              domain={[0, 10]}
              tick={{ fill: '#94a3b8', fontSize: 11 }}
              axisLine={{ stroke: '#334155' }}
              tickLine={false}
            />
            <YAxis
              type="number"
              dataKey="amount"
              name="Amount"
              tickFormatter={formatYAxis}
              tick={{ fill: '#94a3b8', fontSize: 11 }}
              axisLine={{ stroke: '#334155' }}
              tickLine={false}
            />
            <ZAxis type="number" dataKey="amount" range={[40, 300]} />
            <Tooltip content={<CustomTooltip />} />
            <ReferenceLine x={7.0} stroke="#ef4444" strokeDasharray="3 3" />
            <Scatter data={exposureData} fill="#3b82f6" fillOpacity={0.7} stroke="#60a5fa" />
          </ScatterChart>
        </ResponsiveContainer>
      </div>
    </Card>
  );
}
