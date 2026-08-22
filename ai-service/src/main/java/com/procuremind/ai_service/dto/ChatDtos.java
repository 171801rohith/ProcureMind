package com.procuremind.ai_service.dto;

import java.util.List;
import java.util.UUID;

public class ChatDtos {
    public record ChatRequestDto(
            String userMessage,
            String conversationId
    ) {
    }

    public record ChatResponseDto(
            String answer,
            List<String> agentTrace,
            List<CitationDto> citations
    ) {
    }

    public record CitationDto(
            UUID nodeId, String hierarchyPath
    ) {
    }

    public record SearchCriteria(
            String vendorName,
            String documentType,
            Double minRiskScore,
            Integer expiringInDays
    ) {
    }

    public record EnrichedClauseDto(
            UUID nodeId,
            String title,
            Integer level,
            String parentTitle,
            String rawText
    ) {
    }
}
