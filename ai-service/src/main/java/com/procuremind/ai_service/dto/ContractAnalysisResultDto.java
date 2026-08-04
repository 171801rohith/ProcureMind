package com.procuremind.ai_service.dto;

import java.util.List;

public record ContractAnalysisResultDto(
        double riskScore,
        String contractType,
        Double amount,
        List<RiskItem> risks,
        String recommendation
) {
    public record RiskItem(
       String severity,
       String description
    ) {
    }
}
