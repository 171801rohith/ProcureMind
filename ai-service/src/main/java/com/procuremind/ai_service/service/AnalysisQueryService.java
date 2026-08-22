package com.procuremind.ai_service.service;

import com.procuremind.ai_service.Repository.AnalysisRiskRepository;
import com.procuremind.ai_service.Repository.ContractAnalysisRepository;
import com.procuremind.ai_service.Repository.ContractMetadataRepository;
import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.dto.AnalysisResponseDto;
import com.procuremind.ai_service.dto.ClauseContentDto;
import com.procuremind.ai_service.dto.DashboardDtos;
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
    private final ContractMetadataRepository metadataRepository;

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
    public DashboardDtos.DashboardMetricsDto getDashboardMetrics() {
        log.info("Fetching dashboard aggregated metrics");

        long total = analysisRepository.count();
        long highRisk = analysisRepository.countHighRiskContracts();
        Double avgRisk = analysisRepository.getAverageRiskScore();
        double avg = (avgRisk != null) ? avgRisk : 0.0;

        return DashboardDtos.DashboardMetricsDto.builder()
                .totalAnalyzed(total)
                .highRiskCount(highRisk)
                .averageRiskScore(Math.round(avg * 10.0) / 10.0)
                .build();
    }

    @Transactional(readOnly = true)
    public List<AnalysisRiskRepository.RiskFrequency> getTopRiskyClauses() {
        log.info("Fetching top risky clauses");
        return riskRepository.findTopRisks().stream().limit(5).toList();
    }

    @Transactional(readOnly = true)
    public List<DashboardDtos.FinancialExposureDto> getFinancialExposure() {
        log.info("Fetching financial exposure insights");
        return analysisRepository.getFinancialExposureData().stream()
                .map(p -> DashboardDtos.FinancialExposureDto.builder()
                        .contractId(p.getContractId())
                        .contractType(p.getContractType())
                        .amount(p.getAmount())
                        .riskScore(p.getRiskScore())
                        .highRiskCount(p.getHighRiskCount() != null ? p.getHighRiskCount() : 0L)
                        .build()
                ).toList();
    }

    @Transactional(readOnly = true)
    public List<DashboardDtos.RiskDistributionDto> getRiskDistribution() {
        log.info("Fetching risk severity distribution");
        return riskRepository.getRiskSeverityDistribution().stream()
                .map(p -> DashboardDtos.RiskDistributionDto.builder()
                        .severity(p.getSeverity())
                        .count(p.getCount())
                        .build()
                ).toList();
    }

    @Transactional(readOnly = true)
    public List<DashboardDtos.ContractTypeDistributionDto> getContractTypeDistribution() {
        log.info("Fetching contract type distribution");
        return metadataRepository.getContractTypeDistribution().stream()
                .map(p -> new DashboardDtos.ContractTypeDistributionDto(p.getContractType(), p.getCount()))
                .toList();
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
