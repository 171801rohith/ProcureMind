package com.procuremind.contract_service.service.kafka;

import com.procuremind.common.dto.PageIndexedEvent;
import com.procuremind.common.tracing.CorrelationIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Advances {@code contracts.status} as the ai-service pipeline reports progress.
 *
 * <p>Failures are not caught here — they propagate so the container's error handler can retry
 * and then dead-letter the record via {@code KafkaErrorHandlingConfig}, which also marks the
 * contract FAILED so a poison message never leaves it silently stuck at its prior status.
 *
 * <p>Each handler restores the contract id into MDC for the duration of processing, from the
 * {@link CorrelationIds#HEADER} header when present (falling back to the event's own id for
 * messages published before this header existed), so every log line for one contract's journey
 * — across this and the other three services — can be found by grepping one id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractEventListener {

    private final ContractStatusUpdater statusUpdater;

    @KafkaListener(topics = "contract.indexed", groupId = "contract-processing-group")
    public void handleContractIndexed(PageIndexedEvent event,
            @Header(value = CorrelationIds.HEADER, required = false) byte[] correlationId) {
        withCorrelationId(correlationId, event.contractId(),
                () -> statusUpdater.advance(event.contractId(), "INDEXED"));
    }

    @KafkaListener(topics = "contract.analyzed", groupId = "contract-processing-group")
    public void handleContractAnalyzed(PageIndexedEvent event,
            @Header(value = CorrelationIds.HEADER, required = false) byte[] correlationId) {
        withCorrelationId(correlationId, event.contractId(),
                () -> statusUpdater.advance(event.contractId(), "ANALYZED"));
    }

    /**
     * Terminal failure reported by ai-service after its retries were exhausted, so a
     * contract that cannot be processed stops looking like one that is merely slow.
     */
    @KafkaListener(topics = "contract.failed", groupId = "contract-processing-group")
    public void handleContractFailed(PageIndexedEvent event,
            @Header(value = CorrelationIds.HEADER, required = false) byte[] correlationId) {
        withCorrelationId(correlationId, event.contractId(),
                () -> statusUpdater.advance(event.contractId(), ContractStatusUpdater.FAILED));
    }

    private void withCorrelationId(byte[] headerValue, UUID fallback, Runnable action) {
        String contractId = headerValue != null
                ? new String(headerValue, StandardCharsets.UTF_8)
                : fallback.toString();
        MDC.put(CorrelationIds.MDC_KEY, contractId);
        try {
            action.run();
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }
}
