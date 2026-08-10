package com.procuremind.ai_service.service;

import com.procuremind.ai_service.Repository.ContractAnalysisRepository;
import com.procuremind.ai_service.Repository.ContractMetadataRepository;
import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.dto.ChatDtos;
import com.procuremind.ai_service.entity.PageIndexNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {
    private final PageIndexNodeRepository nodeRepository;
    private final ContractMetadataRepository metadataRepository;
    private ContractAnalysisRepository analysisRepository;

    public String getContractSummary(String documentId) {
        log.info("Tool executing: getContractSummary for {}", documentId);
        List<PageIndexNode> nodes = nodeRepository.findByDocumentIdOrderByNodeOrderAsc(UUID.fromString(documentId));

        return nodes.stream()
                .map(node -> String.format("Node %s: %s - %s", node.getId(), node.getTitle(), node.getSummary()))
                .collect(Collectors.joining("\n"));
    }

    public String getClauseContent(String nodeId) {
        log.info("Tool executing: getClauseContent for {}", nodeId);
        return nodeRepository.findById(UUID.fromString(nodeId))
                .map(node -> String.format("NodeID: %s\nLevel: %s\nRaw Text: %s",
                        node.getId(), node.getLevel(), node.getRawContext()))
                .orElse("Clause content not found");
    }

    public String getCachedAnalysis(String contractId) {
        log.info("Executing cache retrieval for contract: {}", contractId);
        return analysisRepository.findByContractId(UUID.fromString(contractId))
                .map(a -> String.format("Risk Score: %s\\nRecommendation: %s\\nStatus: %s",
                        a.getRiskScore(), a.getRecommendation(), a.getStatus()))
                .orElse("No cached analysis available for this contract ID.");
    }

    public String getVendorHistory(String vendorName) {
        log.info("Tool executed: getVendorHistory for {}", vendorName);
        // Temp
        return "Vendor: " + vendorName + " | Past Issues: Strict 15-day foreign notice requirement. Board approval mandatory above $1M.";
    }

    public String discoverContracts(ChatDtos.SearchCriteria criteria) {
        log.info("Executing contract discovery for criteria: {}", criteria);
        // Note: For production, this would use a JPA Specification based on the criteria.
        // Mocking the response to guide the LLM for now.
        return "Found 1 result: ID: 550e8400-e29b-41d4-a716-446655440000 | Type: MSA | Risk: 8.2";
    }
}
