import React from 'react';

export function EmptyState({ icon: Icon, title, description, action }) {
  return (
    <div className="flex flex-col items-center justify-center p-8 text-center bg-slate-900/40 border border-slate-800 border-dashed rounded-xl my-4">
      {Icon && <Icon className="w-12 h-12 text-slate-500 mb-3" />}
      <h3 className="text-lg font-semibold text-slate-200">{title}</h3>
      <p className="text-sm text-slate-400 max-w-sm mt-1 mb-4">{description}</p>
      {action}
    </div>
  );
}
