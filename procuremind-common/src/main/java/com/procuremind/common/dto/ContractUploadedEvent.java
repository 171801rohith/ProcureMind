package com.procuremind.common.dto;

import java.util.UUID;

public record ContractUploadedEvent(
        UUID contractId,
        String filename,
        String minioObjName
) {
}
