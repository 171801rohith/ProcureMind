package com.procuremind.ai_service.dto;

import java.util.List;

public record ContractAnalysisResult(
        String vendorName,
        double riskScore,
        List<RiskItem> risks,
        String recommendation
) {
    public record RiskItem(
       String severity,
       String description
    ) {
    }
}
