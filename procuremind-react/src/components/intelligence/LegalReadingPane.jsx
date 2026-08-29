import React from 'react';
import { Card } from '../ui/Card';
import { BookOpen, Sparkles } from 'lucide-react';

export function LegalReadingPane({ nodeDetail, selectedTocNode }) {
  const title = nodeDetail?.title || selectedTocNode?.title || 'Section Detail';
  const text =
    nodeDetail?.rawContent ||
    nodeDetail?.text ||
    nodeDetail?.content ||
    selectedTocNode?.snippet ||
    selectedTocNode?.summary ||
    'Select a clause node from the TOC tree to inspect exact legal wording.';
  const summary =
    nodeDetail?.summary ||
    selectedTocNode?.summary ||
    'No AI clause summary available for this section.';

  return (
    <Card className="h-full flex flex-col p-5">
      <div className="flex items-center space-x-2 pb-3 mb-4 border-b border-slate-800 text-slate-200">
        <BookOpen className="w-4 h-4 text-blue-400" />
        <h3 className="text-sm font-bold uppercase tracking-wider">Legal Reading Pane</h3>
      </div>

      <div className="flex-1 space-y-4 overflow-y-auto pr-1">
        <div>
          <h4 className="text-base font-bold text-blue-400 mb-2">{title}</h4>
          <div className="bg-slate-950 border border-slate-800 rounded-lg p-4 font-serif text-sm leading-relaxed text-slate-300 whitespace-pre-wrap">
            "{text}"
          </div>
        </div>

        <div className="pt-2">
          <div className="flex items-center space-x-2 text-emerald-400 mb-2">
            <Sparkles className="w-4 h-4" />
            <h5 className="text-xs font-bold uppercase tracking-wider">AI Clause Summary</h5>
          </div>
          <div className="bg-emerald-950/20 border border-emerald-500/20 rounded-lg p-3 text-xs text-slate-300">
            {summary}
          </div>
        </div>
      </div>
    </Card>
  );
}
