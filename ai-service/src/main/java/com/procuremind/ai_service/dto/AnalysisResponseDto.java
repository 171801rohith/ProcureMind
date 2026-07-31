package com.procuremind.ai_service.dto;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Builder
public record AnalysisResponseDto(
        UUID contractId,
        String vendorName,
        Double riskScore,
        String recommendation,
        String status,
        List<RiskDto> risks,
        LocalDateTime createdAt
) {
    public record RiskDto(
            String severity,
            String description
    ) {
    }
}
