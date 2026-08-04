package com.procuremind.ai_service.service;

import com.procuremind.ai_service.Repository.AnalysisRiskRepository;
import com.procuremind.ai_service.Repository.ContractAnalysisRepository;
import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.dto.AnalysisResponseDto;
import com.procuremind.ai_service.dto.ClauseContentDto;
import com.procuremind.ai_service.dto.DashboardMetricsDto;
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
    private final AnalysisRiskRepository riskRepository;
    private final PageIndexNodeRepository nodeRepository;

    @Transactional(readOnly = true)
    public Optional<AnalysisResponseDto> getAnalysis(UUID contractId) {
        log.info("Fetching analysis for contract {}", contractId);
        return analysisRepository.findByContractId(contractId)
                .map(analysis -> AnalysisResponseDto.builder()
                        .contractId(analysis.getContractId())
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

    @Transactional(readOnly = true)
    public DashboardMetricsDto getDashboardMetrics() {
        log.info("Fetching dashboard aggregated metrics");

        long total = analysisRepository.count();
        long highRisk = analysisRepository.countHighRiskContracts();
        double avgRisk = analysisRepository.getAverageRiskScore();

        return DashboardMetricsDto.builder()
                .totalAnalyzed(total)
                .highRiskCount(highRisk)
                .averageRiskScore(Math.round(avgRisk * 10.0) / 10.0)
                .build();
    }

    @Transactional(readOnly = true)
    public List<AnalysisRiskRepository.RiskFrequency> getTopRiskyClauses() {
        log.info("Fetching top risky clauses");
        return riskRepository.findTopRisks().stream().limit(5).toList();
    }

    public List<AnalysisResponseDto> compareContracts(List<UUID> contractIds) {
        log.info("Comparing contracts: {}", contractIds);
        return analysisRepository.findByContractIdIn(contractIds).stream()
                .map(analysis -> new AnalysisResponseDto(
                        analysis.getContractId(),
                        analysis.getRiskScore(),
                        analysis.getRecommendation(),
                        analysis.getStatus(),
                        List.of(),
                        analysis.getCreatedAt()
                )).toList();
    }
}
