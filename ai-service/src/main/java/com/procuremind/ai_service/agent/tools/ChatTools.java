package com.procuremind.ai_service.agent.tools;

import com.procuremind.ai_service.dto.ChatDtos;
import com.procuremind.ai_service.service.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatTools {
    private final RetrievalService retrievalService;

    @Tool(description = "Discovers contracts based on metadata (vendor, risk, type). Use FIRST if Contract ID is unknown.")
    public String discoverContracts(ChatDtos.SearchCriteria criteria) {
        return retrievalService.discoverContracts(criteria);
    }

    @Tool(description = "Retrieves pre-computed AI risk analysis and historical risks. Use SECOND to avoid reading the whole document.")
    public String getCachedAnalysis(String contractId) {
        return retrievalService.getCachedAnalysis(contractId);
    }

    @Tool(description = "Retrieves the document's hierarchical Table of Contents (section order, titles, summaries). Use THIRD if the cached analysis does not answer the user's specific question.")
    public String getContractSummary(String contractId) {
        return retrievalService.getContractSummary(contractId);
    }

    @Tool(description = "Retrieves the exact, raw legal text for a specific section. Use FOURTH for deep evidence retrieval.")
    public String getClauseContent(String nodeId) {
        return retrievalService.getClauseContent(nodeId);
    }
}
