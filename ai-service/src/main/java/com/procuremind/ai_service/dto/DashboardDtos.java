package com.procuremind.ai_service.dto;

import lombok.Builder;

import java.util.UUID;

public class DashboardDtos {
    @Builder
    public record DashboardMetricsDto(
            long totalAnalyzed,
            long highRiskCount,
            Double averageRiskScore
    ) {
    }

    @Builder
    public record FinancialExposureDto(
            UUID contractId,
            String contractType,
            Double amount,
            Double riskScore,
            Long highRiskCount,
            String vendorName,
            String fileName
    ) {
    }

    @Builder
    public record RiskDistributionDto(
            String severity,
            Long count
    ) {
    }

    @Builder
    public record ContractTypeDistributionDto(
            String contractType,
            Long count
    ) {
    }
}
