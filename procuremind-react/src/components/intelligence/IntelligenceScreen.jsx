import React, { useState, useEffect } from 'react';
import { useApp } from '../../context/AppContext';
import { ApiClient } from '../../api/client';
import { TOCNav } from './TOCNav';
import { LegalReadingPane } from './LegalReadingPane';
import { RiskSidePanel } from './RiskSidePanel';
import { Card } from '../ui/Card';
import { Button } from '../ui/Button';
import { FileDown } from 'lucide-react';
import { exportContractAnalysisToPdf } from '../../utils/exportUtils';

export function IntelligenceScreen() {
  const { contracts } = useApp();
  const [selectedContractId, setSelectedContractId] = useState('');
  const [tocNodes, setTocNodes] = useState([]);
  const [selectedNodeId, setSelectedNodeId] = useState('');
  const [nodeDetail, setNodeDetail] = useState(null);
  const [risks, setRisks] = useState([]);

  useEffect(() => {
    if (contracts.length > 0 && !selectedContractId) {
      setSelectedContractId(contracts[0].id);
    }
  }, [contracts, selectedContractId]);

  useEffect(() => {
    if (!selectedContractId) return;

    const fetchIntel = async () => {
      try {
        const [toc, riskList] = await Promise.all([
          ApiClient.getContractTOC(selectedContractId),
          ApiClient.getContractRisks(selectedContractId)
        ]);

        setTocNodes(toc || []);
        setRisks(riskList || []);

        if (toc && toc.length > 0) {
          setSelectedNodeId(toc[0].id);
        } else {
          setSelectedNodeId('');
          setNodeDetail(null);
        }
      } catch (err) {
        // Handled cleanly
      }
    };

    fetchIntel();
  }, [selectedContractId]);

  useEffect(() => {
    if (!selectedNodeId) return;

    const fetchNode = async () => {
      try {
        const detail = await ApiClient.getNodeDetail(selectedNodeId);
        setNodeDetail(detail);
      } catch {
        setNodeDetail(null);
      }
    };

    fetchNode();
  }, [selectedNodeId]);

  if (contracts.length === 0) {
    return (
      <Card className="p-8 text-center text-slate-400">
        No contract records available for deep clause analysis.
      </Card>
    );
  }

  const selectedTocNode = tocNodes.find((n) => n.id === selectedNodeId);
  const selectedContract = contracts.find((c) => c.id === selectedContractId);

  return (
    <div className="space-y-4 min-h-[calc(100vh-7rem)] flex flex-col">
      {/* Contract Selector Header */}
      <div className="bg-slate-900 border border-slate-800 p-3 sm:p-4 rounded-xl flex flex-col sm:flex-row items-start sm:items-center justify-between gap-2">
        <label className="text-xs font-semibold text-slate-400 uppercase tracking-wider">
          Target Agreement:
        </label>
        <div className="flex items-center gap-2 w-full sm:w-auto">
          <select
            value={selectedContractId}
            onChange={(e) => setSelectedContractId(e.target.value)}
            className="flex-1 sm:flex-none bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-xs sm:text-sm text-slate-200 focus:outline-none focus:border-blue-500 max-w-full sm:max-w-md truncate"
          >
            {contracts.map((c) => (
              <option key={c.id} value={c.id}>
                {c.vendorName} - {c.fileName} (Risk: {c.riskScore} / 10)
              </option>
            ))}
          </select>

          <Button
            variant="secondary"
            size="sm"
            onClick={() => exportContractAnalysisToPdf(selectedContract, risks)}
            disabled={!selectedContract}
            title={!selectedContract ? 'Select an agreement first' : 'Download this agreement\'s analysis as a PDF report'}
            className="gap-1.5 whitespace-nowrap shrink-0"
          >
            <FileDown className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Export PDF</span>
          </Button>
        </div>
      </div>

      {/* 3-Column Responsive Grid */}
      <div className="flex-1 grid grid-cols-1 lg:grid-cols-12 gap-4">
        <div className="lg:col-span-3 min-h-[220px] max-h-[300px] lg:max-h-none">
          <TOCNav
            tocNodes={tocNodes}
            selectedNodeId={selectedNodeId}
            onSelectNode={setSelectedNodeId}
          />
        </div>

        <div className="lg:col-span-6 min-h-[350px]">
          <LegalReadingPane
            nodeDetail={nodeDetail}
            selectedTocNode={selectedTocNode}
          />
        </div>

        <div className="lg:col-span-3 min-h-[220px]">
          <RiskSidePanel risks={risks} />
        </div>
      </div>
    </div>
  );
}
