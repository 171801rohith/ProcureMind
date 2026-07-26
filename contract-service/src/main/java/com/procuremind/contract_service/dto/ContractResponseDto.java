package com.procuremind.contract_service.dto;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record ContractResponseDto(
        UUID id,
        String fileName,
        String vendorName,
        String status,
        LocalDateTime uploadedAt
) {
}
