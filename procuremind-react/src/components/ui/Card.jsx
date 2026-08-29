import React from 'react';

export function Card({ children, className = '', ...props }) {
  return (
    <div className={`bg-slate-900/80 backdrop-blur-md border border-slate-800 rounded-xl p-5 shadow-xl ${className}`} {...props}>
      {children}
    </div>
  );
}
