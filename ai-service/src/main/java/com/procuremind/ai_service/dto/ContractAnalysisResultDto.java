package com.procuremind.ai_service.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

public record ContractAnalysisResultDto(
        @JsonPropertyDescription("Calculated overall risk score between 1.0 and 10.0 based on identified risks. Mandatory.")
        Double riskScore,

        @JsonPropertyDescription("The legal classification of the contract (e.g., MSA, NDA, SOW)")
        String contractType,

        @JsonPropertyDescription("Total contract amount/value if stated, otherwise null")
        Double amount,

        List<RiskItem> risks,

        @JsonPropertyDescription("Executive procurement recommendation")
        String recommendation
) {
    public record RiskItem(
            String severity,
            String description
    ) {
    }
}
