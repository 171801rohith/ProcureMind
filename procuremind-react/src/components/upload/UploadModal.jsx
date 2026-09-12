import React, { useState } from 'react';
import { useApp } from '../../context/AppContext';
import { Modal } from '../ui/Modal';
import { Button } from '../ui/Button';
import { ApiClient } from '../../api/client';
import { Upload, FileText, CheckCircle2 } from 'lucide-react';

export function UploadModal() {
  const { uploadModalOpen, setUploadModalOpen, refreshData } = useApp();
  const [file, setFile] = useState(null);
  const [vendorName, setVendorName] = useState('Unknown Vendor');
  const [contractType, setContractType] = useState('MSA');
  const [amount, setAmount] = useState(500000);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [progress, setProgress] = useState(0);
  const [statusMessage, setStatusMessage] = useState('');

  const handleDrop = (e) => {
    e.preventDefault();
    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      setFile(e.dataTransfer.files[0]);
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!file) return;

    setIsSubmitting(true);
    setProgress(33);
    setStatusMessage('Uploading contract PDF to API Gateway...');

    try {
      const res = await ApiClient.uploadContract(file, vendorName, contractType, amount);
      const cid = res?.id || res?.contractId;

      setProgress(66);
      setStatusMessage('Building the clause hierarchy & summarising sections...');
      await new Promise((r) => setTimeout(r, 1000));

      if (cid) {
        await ApiClient.checkContractStatus(cid);
      }

      setProgress(100);
      setStatusMessage('Ingestion pipeline completed!');
      await new Promise((r) => setTimeout(r, 800));

      await refreshData();
      setUploadModalOpen(false);
      setFile(null);
      setProgress(0);
    } catch (err) {
      setStatusMessage('Upload failed. Check gateway connection.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <Modal isOpen={uploadModalOpen} onClose={() => !isSubmitting && setUploadModalOpen(false)} title="Ingest New Legal Contract">
      <form onSubmit={handleSubmit} className="space-y-4">
        {/* Drag and Drop Zone */}
        <div
          onDragOver={(e) => e.preventDefault()}
          onDrop={handleDrop}
          className="border-2 border-dashed border-slate-700 hover:border-blue-500/50 bg-slate-950 p-6 rounded-xl text-center cursor-pointer transition-colors"
        >
          <Upload className="w-8 h-8 text-blue-400 mx-auto mb-2" />
          <p className="text-sm font-medium text-slate-200">
            {file ? file.name : 'Drag and drop contract PDF file here'}
          </p>
          <p className="text-xs text-slate-500 mt-1">Supports PDF files up to 25MB</p>
          <input
            type="file"
            accept=".pdf"
            onChange={(e) => setFile(e.target.files[0])}
            className="hidden"
            id="pdf-upload-input"
          />
          <label htmlFor="pdf-upload-input" className="inline-block mt-3 px-3 py-1 bg-slate-800 text-slate-300 text-xs rounded hover:bg-slate-700 cursor-pointer">
            Browse File
          </label>
        </div>

        {/* Metadata Inputs */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
          <div>
            <label className="block text-xs font-semibold text-slate-400 mb-1">Vendor Name</label>
            <input
              type="text"
              value={vendorName}
              onChange={(e) => setVendorName(e.target.value)}
              className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-sm text-slate-200 focus:outline-none focus:border-blue-500"
              required
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-400 mb-1">Contract Type</label>
            <select
              value={contractType}
              onChange={(e) => setContractType(e.target.value)}
              className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-sm text-slate-200 focus:outline-none focus:border-blue-500"
            >
              <option value="MSA">MSA</option>
              <option value="SLA">SLA</option>
              <option value="DPA">DPA</option>
              <option value="Software License">Software License</option>
              <option value="SOW">SOW</option>
              <option value="NDA">NDA</option>
            </select>
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-400 mb-1">Contract Amount ($)</label>
            <input
              type="number"
              value={amount}
              onChange={(e) => setAmount(Number(e.target.value))}
              className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-sm text-slate-200 focus:outline-none focus:border-blue-500"
              required
            />
          </div>
        </div>

        {/* Progress Tracker */}
        {isSubmitting && (
          <div className="space-y-2 pt-2">
            <div className="flex justify-between text-xs text-slate-400">
              <span>{statusMessage}</span>
              <span>{progress}%</span>
            </div>
            <div className="w-full bg-slate-950 rounded-full h-2 overflow-hidden border border-slate-800">
              <div className="bg-blue-600 h-full transition-all duration-300" style={{ width: `${progress}%` }} />
            </div>
          </div>
        )}

        <div className="flex justify-end space-x-3 pt-4 border-t border-slate-800">
          <Button type="button" variant="secondary" onClick={() => setUploadModalOpen(false)} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" isLoading={isSubmitting} disabled={!file}>
            Start Ingestion Pipeline
          </Button>
        </div>
      </form>
    </Modal>
  );
}
