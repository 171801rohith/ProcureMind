package com.procuremind.ai_service.dto;

import java.util.UUID;

public record PageIndexedEvent (
        UUID contractId,
        String status
) {
}
