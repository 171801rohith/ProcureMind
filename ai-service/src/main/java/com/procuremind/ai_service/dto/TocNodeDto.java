package com.procuremind.ai_service.dto;

import lombok.Builder;

import java.util.UUID;

@Builder
public record TocNodeDto(
        UUID id,
        String type,
        String title,
        String summary
) {
}
