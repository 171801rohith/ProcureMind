package com.procuremind.ai_service.dto;

import lombok.Builder;

@Builder
public record ClauseContentDto(
        String title,
        String rawContent
) {
}
