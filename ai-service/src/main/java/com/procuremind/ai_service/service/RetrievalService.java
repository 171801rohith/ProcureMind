package com.procuremind.ai_service.service;

import com.procuremind.ai_service.Repository.ContractAnalysisRepository;
import com.procuremind.ai_service.Repository.ContractMetadataRepository;
import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.dto.ChatDtos;
import com.procuremind.ai_service.entity.ContractAnalysis;
import com.procuremind.ai_service.entity.ContractMetadata;
import com.procuremind.ai_service.entity.PageIndexNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.AbstractMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {
    private final PageIndexNodeRepository nodeRepository;
    private final ContractMetadataRepository metadataRepository;
    private final ContractAnalysisRepository analysisRepository;

    @Transactional(readOnly = true)
    public String getContractSummary(String documentId) {
        log.info("Tool executing: getContractSummary for {}", documentId);
        List<PageIndexNode> nodes = nodeRepository.findByDocumentIdOrderByNodeOrderAsc(UUID.fromString(documentId));

        return nodes.stream()
                .map(node -> String.format("Node %s: %s - %s", node.getId(), node.getTitle(), node.getSummary()))
                .collect(Collectors.joining("\n"));
    }

    @Transactional(readOnly = true)
    public String getClauseContent(String nodeId) {
        log.info("Tool executing: getClauseContent for {}", nodeId);
        return nodeRepository.findById(UUID.fromString(nodeId))
                .map(node -> String.format("NodeID: %s\nLevel: %s\nRaw Text: %s",
                        node.getId(), node.getLevel(), node.getRawContext()))
                .orElse("Clause content not found");
    }

    @Transactional(readOnly = true)
    public String getCachedAnalysis(String contractId) {
        log.info("Executing cache retrieval for contract: {}", contractId);
        return analysisRepository.findByContractId(UUID.fromString(contractId))
                .map(a -> {
                    String baseInfo = String.format("Risk Score: %s\\nRecommendation: %s\\nStatus: %s\\n",
                            a.getRiskScore(), a.getRecommendation(), a.getStatus());
                    String detailedRisks = a.getRisks().stream()
                            .map(risk -> String.format("- [Severity: %s] %s", risk.getSeverity(), risk.getDescription()))
                            .collect(Collectors.joining("\n"));

                    return baseInfo + "\nIdentified Risks:\n" + (detailedRisks.isEmpty() ? "None" : detailedRisks);
                })
                .orElse("No cached analysis available for this contract ID.");
    }

//    @Transactional(readOnly = true)
//    public String getVendorHistory(String vendorName) {
//        log.info("Tool executed: getVendorHistory for {}", vendorName);
//        // Temp
//        return "Vendor: " + vendorName + " | Past Issues: Strict 15-day foreign notice requirement. Board approval mandatory above $1M.";
//    }

    @Transactional(readOnly = true)
    public String discoverContracts(ChatDtos.SearchCriteria criteria) {
        log.info("Executing contract discovery for criteria: {}", criteria);

        List<ContractMetadata> metadataList = metadataRepository.findAll();

        String results = metadataList.stream()
                .filter(m -> criteria.documentType() == null ||
                        (m.getContractId() != null && m.getContractType().equalsIgnoreCase(criteria.documentType())))
                .map(m -> {
                    ContractAnalysis a = analysisRepository.findByContractId(m.getContractId()).orElse(null);
                    return new AbstractMap.SimpleEntry<>(m, a);
                })
                .filter(entry -> criteria.minRiskScore() == null ||
                        entry.getValue().getRiskScore() >= criteria.minRiskScore())
                .map(entry -> String.format("- Contract ID: %s | Type: %s | Amount: %s | Risk Score: %s",
                        entry.getKey().getContractId(),
                        entry.getKey().getContractType(),
                        entry.getKey().getAmount(),
                        entry.getValue().getRiskScore()))
                .collect(Collectors.joining("\n"));

        return results.isEmpty() ? "No contracts found matching the criteria." : "Found the following contracts:\n" + results;
    }
}
