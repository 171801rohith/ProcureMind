/*
  Client-side export helpers (CSV + PDF) for the contracts dashboard.

  Everything here works off data the app has already fetched (via
  ApiClient.getMergedContracts() / getContractRisks()) — there is no dedicated
  export endpoint. CSV is hand-rolled (RFC 4180 style escaping) rather than
  pulling in a library like papaparse, since the escaping rules needed here are
  small and well understood. PDF uses jspdf + jspdf-autotable since building
  a table layout by hand with raw jsPDF primitives is error-prone.
*/

import { jsPDF } from 'jspdf';
import { autoTable } from 'jspdf-autotable';

function csvEscape(value) {
  if (value === null || value === undefined) return '';
  const str = String(value);
  // Quote whenever the value contains a comma, quote, or newline; double any
  // embedded quotes per RFC 4180.
  if (/[",\n\r]/.test(str)) {
    return `"${str.replace(/"/g, '""')}"`;
  }
  return str;
}

function formatDate(value) {
  if (!value) return '';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return String(value);
  return d.toLocaleDateString();
}

function triggerDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

const CSV_COLUMNS = [
  { key: 'vendorName', label: 'Vendor', fallback: 'Unknown Vendor' },
  { key: 'fileName', label: 'File Name', fallback: '' },
  { key: 'contractType', label: 'Type', fallback: 'Agreement' },
  { key: 'status', label: 'Status', fallback: 'UNKNOWN' },
  { key: 'riskScore', label: 'Risk Score', fallback: 0 },
  { key: 'amount', label: 'Value ($)', fallback: 0 },
  { key: 'recommendation', label: 'Recommendation', fallback: '' },
  { key: 'uploadedAt', label: 'Uploaded At', fallback: '', format: formatDate },
];

/**
 * Builds a CSV string from the merged-contracts shape and triggers a browser
 * download. Safe to call with an empty array (produces a header-only CSV) —
 * callers should still disable the triggering button while data is loading.
 */
export function exportContractsToCsv(contracts) {
  const rows = Array.isArray(contracts) ? contracts : [];

  const header = CSV_COLUMNS.map((col) => csvEscape(col.label)).join(',');
  const lines = rows.map((contract) =>
    CSV_COLUMNS.map((col) => {
      const raw = contract[col.key] ?? col.fallback;
      const value = col.format ? col.format(raw) : raw;
      return csvEscape(value);
    }).join(',')
  );

  const csvContent = [header, ...lines].join('\r\n');
  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
  const stamp = new Date().toISOString().slice(0, 10);
  triggerDownload(blob, `procuremind-contracts-${stamp}.csv`);
}

/**
 * Renders a single contract's analysis (risk score, recommendation, summary,
 * and identified risks) as a one-page PDF report and triggers a download.
 */
export function exportContractAnalysisToPdf(contract, risks) {
  if (!contract) return;

  const doc = new jsPDF({ unit: 'pt', format: 'a4' });
  const marginX = 40;
  let cursorY = 50;

  doc.setFontSize(18);
  doc.setFont(undefined, 'bold');
  doc.text('ProcureMind Contract Analysis Report', marginX, cursorY);

  cursorY += 28;
  doc.setFontSize(11);
  doc.setFont(undefined, 'normal');

  const vendor = contract.vendorName || 'Unknown Vendor';
  const fileName = contract.fileName || 'Untitled document';
  const generatedAt = new Date().toLocaleString();

  const summaryLines = [
    `Vendor: ${vendor}`,
    `File: ${fileName}`,
    `Contract Type: ${contract.contractType || 'Agreement'}`,
    `Status: ${contract.status || 'UNKNOWN'}`,
    `Value: $${Number(contract.amount || 0).toLocaleString()}`,
    `Risk Score: ${contract.riskScore ?? 'N/A'} / 10`,
    `Uploaded: ${formatDate(contract.uploadedAt) || 'N/A'}`,
    `Report generated: ${generatedAt}`,
  ];

  summaryLines.forEach((line) => {
    doc.text(line, marginX, cursorY);
    cursorY += 16;
  });

  cursorY += 8;
  doc.setFont(undefined, 'bold');
  doc.text('Recommendation', marginX, cursorY);
  cursorY += 16;
  doc.setFont(undefined, 'normal');
  const recommendationText = contract.recommendation || 'No analysis recommendation available.';
  const wrappedRecommendation = doc.splitTextToSize(recommendationText, 515);
  doc.text(wrappedRecommendation, marginX, cursorY);
  cursorY += wrappedRecommendation.length * 14 + 12;

  if (contract.summary) {
    doc.setFont(undefined, 'bold');
    doc.text('Summary', marginX, cursorY);
    cursorY += 16;
    doc.setFont(undefined, 'normal');
    const wrappedSummary = doc.splitTextToSize(contract.summary, 515);
    doc.text(wrappedSummary, marginX, cursorY);
    cursorY += wrappedSummary.length * 14 + 12;
  }

  const riskRows = Array.isArray(risks) ? risks : [];
  if (riskRows.length > 0) {
    autoTable(doc, {
      startY: cursorY + 8,
      margin: { left: marginX, right: marginX },
      head: [['Severity', 'Category', 'Description', 'Recommendation']],
      body: riskRows.map((risk) => [
        (risk.severity || 'MEDIUM').toUpperCase(),
        risk.category || 'Risk Flag',
        risk.description || '',
        risk.recommendation || '',
      ]),
      styles: { fontSize: 9, cellPadding: 6, overflow: 'linebreak' },
      headStyles: { fillColor: [30, 41, 59] },
      columnStyles: { 2: { cellWidth: 170 }, 3: { cellWidth: 140 } },
    });
  } else {
    doc.setFont(undefined, 'italic');
    doc.text('No high-risk flags identified for this agreement.', marginX, cursorY + 8);
  }

  const safeVendor = vendor.replace(/[^a-zA-Z0-9._-]+/g, '_');
  doc.save(`procuremind-analysis-${safeVendor}.pdf`);
}
