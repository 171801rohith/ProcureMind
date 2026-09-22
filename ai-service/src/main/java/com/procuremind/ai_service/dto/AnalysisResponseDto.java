package com.procuremind.ai_service.dto;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Builder
public record AnalysisResponseDto(
        UUID contractId,
        Double riskScore,
        String recommendation,
        String status,
        List<RiskDto> risks,
        LocalDateTime createdAt,
        // Derived (not a stored column): a one-line synopsis built from riskScore + the most
        // severe finding, since no dedicated summary text exists anywhere in the schema today
        // (see ARCHITECTURE_REVIEW.md finding #11). Revisit if a real summary field is ever
        // added, e.g. as a stored AnalysisAgent output.
        String summary,
        // Derived from `risks` (count of severity == HIGH), not a stored column.
        Long highRiskCount,
        // Sourced from ContractMetadata (ai-service's own entity, no cross-service join).
        String contractType,
        Double amount
) {
    public record RiskDto(
            String severity,
            String description
    ) {
    }
}
