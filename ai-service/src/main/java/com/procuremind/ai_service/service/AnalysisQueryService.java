package com.procuremind.ai_service.service;

import com.procuremind.ai_service.Repository.AnalysisRiskRepository;
import com.procuremind.ai_service.Repository.ContractAnalysisRepository;
import com.procuremind.ai_service.Repository.ContractMetadataRepository;
import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.dto.AnalysisResponseDto;
import com.procuremind.ai_service.dto.ClauseContentDto;
import com.procuremind.ai_service.dto.DashboardDtos;
import com.procuremind.ai_service.dto.TocNodeDto;
import com.procuremind.ai_service.entity.AnalysisRisk;
import com.procuremind.ai_service.entity.ContractAnalysis;
import com.procuremind.ai_service.entity.ContractMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisQueryService {

    /** Mirrors AnalysisAgent's severity ranking, used only to pick the "top" risk for the
     *  derived {@code summary} field below. */
    private static final Map<String, Integer> SEVERITY_ORDER = Map.of("HIGH", 0, "MEDIUM", 1, "LOW", 2);
    private static final int SUMMARY_DESCRIPTION_MAX_CHARS = 160;

    private final ContractAnalysisRepository analysisRepository;
    private final AnalysisRiskRepository riskRepository;
    private final PageIndexNodeRepository nodeRepository;
    private final ContractMetadataRepository metadataRepository;

    @Transactional(readOnly = true)
    public Optional<AnalysisResponseDto> getAnalysis(UUID contractId) {
        log.info("Fetching analysis for contract {}", contractId);
        return analysisRepository.findByContractId(contractId)
                .map(analysis -> {
                    ContractMetadata metadata = metadataRepository.findByContractId(contractId).orElse(null);
                    return AnalysisResponseDto.builder()
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
                            .summary(deriveSummary(analysis))
                            .highRiskCount(countHighRisk(analysis))
                            .contractType(metadata != null ? metadata.getContractType() : null)
                            .amount(metadata != null ? metadata.getAmount() : null)
                            .build();
                });
    }

    /**
     * Builds a one-line synopsis from the risk score and the most severe finding. There is no
     * dedicated summary text stored anywhere in the schema (checked {@link ContractAnalysis},
     * {@link com.procuremind.ai_service.dto.ContractAnalysisResultDto}, and {@code AnalysisAgent}'s
     * prompt output) so, per ARCHITECTURE_REVIEW.md finding #11, this is a computed field rather
     * than a stored one.
     */
    private String deriveSummary(ContractAnalysis analysis) {
        List<AnalysisRisk> risks = analysis.getRisks() != null ? analysis.getRisks() : List.of();
        String riskScoreText = analysis.getRiskScore() != null
                ? "Risk score %.1f/10".formatted(analysis.getRiskScore())
                : "Risk score unavailable";

        return risks.stream()
                .min(Comparator.comparing(r -> SEVERITY_ORDER.getOrDefault(r.getSeverity(), Integer.MAX_VALUE)))
                .map(top -> "%s. Top risk (%s): %s".formatted(
                        riskScoreText, top.getSeverity(), truncate(top.getDescription())))
                .orElse(riskScoreText + ". No risks identified.");
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= SUMMARY_DESCRIPTION_MAX_CHARS
                ? text
                : text.substring(0, SUMMARY_DESCRIPTION_MAX_CHARS - 1).stripTrailing() + "…";
    }

    private static long countHighRisk(ContractAnalysis analysis) {
        List<AnalysisRisk> risks = analysis.getRisks();
        if (risks == null) {
            return 0L;
        }
        return risks.stream().filter(r -> "HIGH".equalsIgnoreCase(r.getSeverity())).count();
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
                        .vendorName(p.getVendorName())
                        .fileName(p.getFileName())
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

    @Transactional(readOnly = true)
    public List<AnalysisResponseDto> compareContracts(List<UUID> contractIds) {
        log.info("Comparing contracts: {}", contractIds);

        Map<UUID, ContractMetadata> metadataByContractId = metadataRepository.findByContractIdIn(contractIds).stream()
                .collect(Collectors.toMap(ContractMetadata::getContractId, m -> m));

        return analysisRepository.findByContractIdIn(contractIds).stream()
                .map(analysis -> {
                    ContractMetadata metadata = metadataByContractId.get(analysis.getContractId());
                    return AnalysisResponseDto.builder()
                            .contractId(analysis.getContractId())
                            .riskScore(analysis.getRiskScore())
                            .recommendation(analysis.getRecommendation())
                            .status(analysis.getStatus())
                            .risks(List.of())
                            .createdAt(analysis.getCreatedAt())
                            .summary(deriveSummary(analysis))
                            .highRiskCount(countHighRisk(analysis))
                            .contractType(metadata != null ? metadata.getContractType() : null)
                            .amount(metadata != null ? metadata.getAmount() : null)
                            .build();
                }).toList();
    }
}
