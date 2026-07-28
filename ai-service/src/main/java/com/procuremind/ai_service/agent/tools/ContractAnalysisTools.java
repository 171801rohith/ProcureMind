package com.procuremind.ai_service.agent.tools;

import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.entity.PageIndexNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ContractAnalysisTools {
    private final PageIndexNodeRepository nodeRepository;

    @Tool(description = "Fetches the Table of Contents (node IDs, titles, summaries) for a contract or document. Use this FIRST to locate relevant sections.")
    public String getContractSummary(String documentId) {
        log.info("Tool executed: getContractSummary for {}", documentId);
        List<PageIndexNode> nodes = nodeRepository.findByDocumentIdOrderByNodeOrderAsc(UUID.fromString(documentId));

        return nodes.stream()
                .map(node -> String.format("Node %s: %s - %s", node.getId(), node.getTitle(), node.getSummary()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Fetches the full, exact legal raw text for a specific clause node ID.")
    public String getClauseContent(String nodeId) {
        log.info("Tool executed: getClauseContent for {}", nodeId);
        return nodeRepository.findById(UUID.fromString(nodeId))
                .map(PageIndexNode::getRawContext)
                .orElse("Clause not found");
    }

    @Tool(description = "Fetches historical risk metrics for a vendor.")
    public String getVendorHistory(String vendorName) {
        log.info("Tool executed: getVendorHistory for {}", vendorName);

        // Temp
        return "Vendor: " + vendorName + " | Past Issues: Strict 15-day foreign notice requirement. Board approval mandatory above $1M.";
    }
}
