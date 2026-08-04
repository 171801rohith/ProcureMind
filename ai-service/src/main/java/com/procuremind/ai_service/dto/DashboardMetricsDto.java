package com.procuremind.ai_service.dto;

import lombok.Builder;

@Builder
public record DashboardMetricsDto(
        long totalAnalyzed,
        long highRiskCount,
        Double averageRiskScore
) {
}
