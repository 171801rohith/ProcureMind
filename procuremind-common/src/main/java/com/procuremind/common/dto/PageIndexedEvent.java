package com.procuremind.common.dto;

import java.util.UUID;

public record PageIndexedEvent (
        UUID contractId,
        String status
) {
}
