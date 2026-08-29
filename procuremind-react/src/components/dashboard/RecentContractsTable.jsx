import React, { useState, useMemo } from 'react';
import { useApp } from '../../context/AppContext';
import { Card } from '../ui/Card';
import { Badge } from '../ui/Badge';
import { Button } from '../ui/Button';
import { Search, ArrowUpDown, ChevronRight } from 'lucide-react';

export function RecentContractsTable() {
  const { contracts, setActiveTab } = useApp();
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [riskFilter, setRiskFilter] = useState('ALL');
  const [sortField, setSortField] = useState('uploadedAt');
  const [sortDirection, setSortDirection] = useState('desc');

  const handleSort = (field) => {
    if (sortField === field) {
      setSortDirection(sortDirection === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortDirection('desc');
    }
  };

  const filteredContracts = useMemo(() => {
    return contracts
      .filter((c) => {
        const matchesSearch =
          (c.vendorName || '').toLowerCase().includes(searchTerm.toLowerCase()) ||
          (c.fileName || '').toLowerCase().includes(searchTerm.toLowerCase());
        const matchesStatus = statusFilter === 'ALL' || c.status === statusFilter;
        
        let matchesRisk = true;
        if (riskFilter === 'HIGH') matchesRisk = c.riskScore >= 7.0;
        else if (riskFilter === 'MEDIUM') matchesRisk = c.riskScore >= 4.0 && c.riskScore < 7.0;
        else if (riskFilter === 'LOW') matchesRisk = c.riskScore < 4.0;

        return matchesSearch && matchesStatus && matchesRisk;
      })
      .sort((a, b) => {
        let valA = a[sortField];
        let valB = b[sortField];
        if (typeof valA === 'string') valA = valA.toLowerCase();
        if (typeof valB === 'string') valB = valB.toLowerCase();
        
        if (valA < valB) return sortDirection === 'asc' ? -1 : 1;
        if (valA > valB) return sortDirection === 'asc' ? 1 : -1;
        return 0;
      });
  }, [contracts, searchTerm, statusFilter, riskFilter, sortField, sortDirection]);

  return (
    <Card className="p-0 overflow-hidden">
      {/* Table Filter Controls */}
      <div className="p-3 sm:p-4 border-b border-slate-800 flex flex-col sm:flex-row gap-3 items-center justify-between">
        <div className="relative w-full sm:w-64 lg:w-72">
          <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            placeholder="Search vendor or file..."
            className="w-full pl-9 pr-4 py-1.5 bg-slate-950 border border-slate-800 rounded-lg text-xs sm:text-sm text-slate-200 placeholder-slate-500 focus:outline-none focus:border-blue-500"
          />
        </div>

        <div className="flex items-center gap-2 sm:gap-3 w-full sm:w-auto">
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="flex-1 sm:flex-none bg-slate-950 border border-slate-800 text-xs rounded-lg px-2.5 py-1.5 text-slate-300 focus:outline-none focus:border-blue-500"
          >
            <option value="ALL">All Statuses</option>
            <option value="ANALYZED">ANALYZED</option>
            <option value="INDEXED">INDEXED</option>
            <option value="UPLOADED">UPLOADED</option>
          </select>

          <select
            value={riskFilter}
            onChange={(e) => setRiskFilter(e.target.value)}
            className="flex-1 sm:flex-none bg-slate-950 border border-slate-800 text-xs rounded-lg px-2.5 py-1.5 text-slate-300 focus:outline-none focus:border-blue-500"
          >
            <option value="ALL">All Risk Levels</option>
            <option value="HIGH">High Risk (≥ 7.0)</option>
            <option value="MEDIUM">Medium Risk (4.0-6.9)</option>
            <option value="LOW">Low Risk (&lt; 4.0)</option>
          </select>
        </div>
      </div>

      {/* Table Body with Horizontal Scrolling on Mobile */}
      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs sm:text-sm text-slate-300 min-w-[640px]">
          <thead className="bg-slate-950/60 text-[11px] uppercase text-slate-400 border-b border-slate-800">
            <tr>
              <th className="px-4 py-3 cursor-pointer select-none" onClick={() => handleSort('vendorName')}>
                <div className="flex items-center gap-1">Vendor <ArrowUpDown className="w-3 h-3" /></div>
              </th>
              <th className="px-4 py-3">Type</th>
              <th className="px-4 py-3 cursor-pointer select-none" onClick={() => handleSort('amount')}>
                <div className="flex items-center gap-1">Value ($) <ArrowUpDown className="w-3 h-3" /></div>
              </th>
              <th className="px-4 py-3 cursor-pointer select-none" onClick={() => handleSort('riskScore')}>
                <div className="flex items-center gap-1">Risk Score <ArrowUpDown className="w-3 h-3" /></div>
              </th>
              <th className="px-4 py-3">Status</th>
              <th className="px-4 py-3 text-right">Action</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-800/60">
            {filteredContracts.length === 0 ? (
              <tr>
                <td colSpan="6" className="px-4 py-8 text-center text-slate-500">
                  No contract records match your filter criteria.
                </td>
              </tr>
            ) : (
              filteredContracts.map((contract) => {
                const isHigh = contract.riskScore >= 7.0;
                const isMed = contract.riskScore >= 4.0 && contract.riskScore < 7.0;
                const badgeVariant = isHigh ? 'high' : isMed ? 'medium' : 'low';

                return (
                  <tr key={contract.id} className="hover:bg-slate-800/30 transition-colors">
                    <td className="px-4 py-3 font-semibold text-slate-200">
                      <div>{contract.vendorName}</div>
                      <div className="text-[11px] font-normal text-slate-500 truncate max-w-[180px] sm:max-w-xs">{contract.fileName}</div>
                    </td>
                    <td className="px-4 py-3">{contract.contractType}</td>
                    <td className="px-4 py-3 font-mono">${(contract.amount || 0).toLocaleString()}</td>
                    <td className="px-4 py-3">
                      <Badge variant={badgeVariant}>
                        {contract.riskScore} / 10
                      </Badge>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-[10px] px-2 py-0.5 rounded border border-slate-700 bg-slate-900 text-slate-300 font-mono">
                        {contract.status}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setActiveTab('intelligence')}
                        className="text-blue-400 hover:text-blue-300 gap-1 px-2 text-xs"
                      >
                        <span>Inspect</span>
                        <ChevronRight className="w-3.5 h-3.5" />
                      </Button>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </Card>
  );
}
