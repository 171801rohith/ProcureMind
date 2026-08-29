import React from 'react';
import { useApp } from '../../context/AppContext';
import { Card } from '../ui/Card';
import { Skeleton } from '../ui/Skeleton';
import { FileText, AlertTriangle, Activity, DollarSign } from 'lucide-react';

export function KPICards() {
  const { metrics, exposureData, isLoading } = useApp();

  if (isLoading) {
    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4 mb-6">
        {[1, 2, 3, 4].map((i) => (
          <Skeleton key={i} className="h-28" />
        ))}
      </div>
    );
  }

  const totalExposure = exposureData.reduce((acc, item) => acc + (item.amount || 0), 0);

  const kpis = [
    {
      title: 'Total Contracts',
      value: metrics.totalAnalyzed || 0,
      subText: 'Across active portfolio',
      border: 'border-t-emerald-500',
      icon: FileText,
      iconColor: 'text-emerald-400'
    },
    {
      title: 'High Risk Contracts',
      value: metrics.highRiskCount || 0,
      subText: 'Requires Immediate Action',
      border: 'border-t-red-500',
      icon: AlertTriangle,
      iconColor: 'text-red-400'
    },
    {
      title: 'Average Risk Score',
      value: `${metrics.averageRiskScore || 0} / 10`,
      subText: 'Threshold Limit: 7.0',
      border: 'border-t-amber-500',
      icon: Activity,
      iconColor: 'text-amber-400'
    },
    {
      title: 'Financial Exposure',
      value: `$${(totalExposure / 1000000).toFixed(2)}M`,
      subText: 'Cumulative Valuation',
      border: 'border-t-blue-500',
      icon: DollarSign,
      iconColor: 'text-blue-400'
    }
  ];

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4 mb-6">
      {kpis.map((kpi, idx) => {
        const Icon = kpi.icon;
        return (
          <Card key={idx} className={`border-t-2 ${kpi.border} hover:border-slate-700 transition-colors p-4 sm:p-5`}>
            <div className="flex items-center justify-between mb-2">
              <span className="text-[11px] sm:text-xs font-semibold uppercase tracking-wider text-slate-400">
                {kpi.title}
              </span>
              <Icon className={`w-4 h-4 sm:w-5 sm:h-5 ${kpi.iconColor}`} />
            </div>
            <div className="text-xl sm:text-2xl font-bold text-slate-100">{kpi.value}</div>
            <div className="text-xs font-medium text-slate-400 mt-1">{kpi.subText}</div>
          </Card>
        );
      })}
    </div>
  );
}
