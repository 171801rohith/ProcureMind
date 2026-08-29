import React from 'react';

export function Skeleton({ className = '', ...props }) {
  return (
    <div className={`animate-pulse bg-slate-800/80 rounded-md ${className}`} {...props} />
  );
}
