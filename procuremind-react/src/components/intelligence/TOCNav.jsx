import React from 'react';
import { Card } from '../ui/Card';
import { FileText, FolderTree } from 'lucide-react';

export function TOCNav({ tocNodes, selectedNodeId, onSelectNode }) {
  return (
    <Card className="h-full flex flex-col p-4">
      <div className="flex items-center space-x-2 pb-3 mb-3 border-b border-slate-800 text-slate-200">
        <FolderTree className="w-4 h-4 text-blue-400" />
        <h3 className="text-sm font-bold uppercase tracking-wider">Structure TOC</h3>
      </div>

      <div className="flex-1 overflow-y-auto space-y-1 pr-1">
        {(!tocNodes || tocNodes.length === 0) ? (
          <div className="text-xs text-slate-500 py-4 text-center">No TOC structure available.</div>
        ) : (
          tocNodes.map((node) => {
            const isSelected = selectedNodeId === node.id;
            return (
              <button
                key={node.id}
                onClick={() => onSelectNode(node.id)}
                className={`w-full text-left px-3 py-2 rounded-lg text-xs font-medium transition-colors flex items-start space-x-2 ${
                  isSelected
                    ? 'bg-blue-600/20 text-blue-300 border border-blue-500/40'
                    : 'text-slate-300 hover:bg-slate-800/60 hover:text-slate-100'
                }`}
              >
                <FileText className="w-3.5 h-3.5 text-slate-400 shrink-0 mt-0.5" />
                <span className="line-clamp-2">{node.title}</span>
              </button>
            );
          })
        )}
      </div>
    </Card>
  );
}
