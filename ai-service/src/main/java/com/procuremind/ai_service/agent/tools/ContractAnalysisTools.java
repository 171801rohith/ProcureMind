package com.procuremind.ai_service.agent.tools;

import com.procuremind.ai_service.service.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContractAnalysisTools {
    private final RetrievalService retrievalService;

    @Tool(description = "Fetches the Table of Contents (node IDs, titles, summaries) for a contract or document. Use this FIRST to locate relevant sections.")
    public String getContractSummary(String documentId) {
        return retrievalService.getContractSummary(documentId);
    }

    @Tool(description = "Fetches the full, exact legal raw text for a specific clause node ID.")
    public String getClauseContent(String nodeId) {
        return retrievalService.getClauseContent(nodeId);
    }

    @Tool(description = "Fetches historical risk metrics for a vendor.")
    public String getVendorHistory(String vendorName) {
        return retrievalService.getVendorHistory(vendorName);
    }
}
