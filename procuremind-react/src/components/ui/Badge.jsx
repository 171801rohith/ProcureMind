import React from 'react';

export function Badge({ children, variant = 'neutral', className = '' }) {
  const variants = {
    high: 'bg-red-500/15 text-red-400 border-red-500/30',
    medium: 'bg-amber-500/15 text-amber-400 border-amber-500/30',
    low: 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30',
    info: 'bg-blue-500/15 text-blue-400 border-blue-500/30',
    neutral: 'bg-slate-800 text-slate-400 border-slate-700'
  };

  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium border ${variants[variant] || variants.neutral} ${className}`}>
      {children}
    </span>
  );
}
