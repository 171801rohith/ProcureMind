package com.procuremind.ai_service.service;

import com.procuremind.ai_service.Repository.ContractAnalysisRepository;
import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.dto.AnalysisResponseDto;
import com.procuremind.ai_service.dto.ClauseContentDto;
import com.procuremind.ai_service.dto.TocNodeDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisQueryService {
    private final ContractAnalysisRepository analysisRepository;
    private final PageIndexNodeRepository nodeRepository;

    @Transactional(readOnly = true)
    public Optional<AnalysisResponseDto> getAnalysis(UUID contractId) {
        log.info("Fetching analysis for contract {}", contractId);
        return analysisRepository.findByContractId(contractId)
                .map(analysis -> AnalysisResponseDto.builder()
                        .contractId(analysis.getContractId())
                        .vendorName(analysis.getVendorName())
                        .riskScore(analysis.getRiskScore())
                        .recommendation(analysis.getRecommendation())
                        .status(analysis.getStatus())
                        .risks(
                                analysis.getRisks().stream()
                                        .map(r -> new AnalysisResponseDto.RiskDto(r.getSeverity(), r.getDescription()))
                                        .toList()
                        )
                        .createdAt(analysis.getCreatedAt())
                        .build());
    }

    @Transactional(readOnly = true)
    public Optional<List<AnalysisResponseDto.RiskDto>> getRisks(UUID contractId) {
        log.info("Fetching analysis risks for contract {}", contractId);
        return analysisRepository.findByContractId(contractId)
                .map(analysis -> analysis.getRisks().stream()
                        .map(risk -> new AnalysisResponseDto.RiskDto(risk.getSeverity(), risk.getDescription()))
                        .toList()
                );
    }

    @Transactional(readOnly = true)
    public List<TocNodeDto> getTableOfContent(UUID contractId) {
        log.info("Fetching analysis TOC for contract {}", contractId);
        return nodeRepository.findByDocumentIdOrderByNodeOrderAsc(contractId).stream()
                .map(node -> TocNodeDto.builder()
                        .id(node.getId())
                        .type(node.getNodeType().name())
                        .title(node.getTitle() != null ? node.getTitle() : "Untitled")
                        .summary(node.getSummary() != null ? node.getSummary() : "No summary available")
                        .build()
                ).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ClauseContentDto> getClauseContent(UUID nodeId) {
        log.info("Fetching analysis clause content for node {}", nodeId);
        return nodeRepository.findById(nodeId)
                .map(node -> ClauseContentDto.builder()
                        .title(node.getTitle() != null ? node.getTitle() : "Untitled")
                        .rawContent(node.getRawContext())
                        .build()
                );
    }

}
