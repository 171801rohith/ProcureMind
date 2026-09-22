package com.procuremind.ai_service.service.kafka;

import com.procuremind.ai_service.service.AnalysisService;
import com.procuremind.ai_service.service.IndexingService;
import com.procuremind.ai_service.service.PdfParsingService;
import com.procuremind.common.dto.ContractUploadedEvent;
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
 * Drives the parse -&gt; index -&gt; analyze pipeline from Kafka.
 *
 * <p>These methods deliberately <b>do not catch</b> exceptions. Swallowing them made the
 * container treat every failure as a success, commit the offset, and leave the contract
 * pinned at UPLOADED or INDEXED forever with no retry and no signal. Letting them
 * propagate hands the record to {@code KafkaErrorHandlingConfig}'s error handler, which
 * retries with backoff and then routes the record to a {@code .DLT} topic and marks the
 * contract FAILED.
 *
 * <p>Each handler restores the contract id into MDC for the duration of processing, from the
 * {@link CorrelationIds#HEADER} header when present (falling back to the event's own id for
 * messages published before this header existed), so every log line for one contract's
 * journey — across this and the other three services — can be found by grepping one id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractEventListener {

    private final PdfParsingService pdfParsingService;
    private final IndexingService indexingService;
    private final AnalysisService analysisService;

    @KafkaListener(topics = "contract.uploaded", groupId = "ai-processing-group")
    public void handleContractUploaded(ContractUploadedEvent event,
            @Header(value = CorrelationIds.HEADER, required = false) byte[] correlationId) {
        withCorrelationId(correlationId, event.contractId(), () -> {
            UUID documentId = event.contractId();
            log.info("[KAFKA] Received contract.uploaded: {}", documentId);
            try {
                log.info("[KAFKA] Processing contract.uploaded: {}", documentId);
                pdfParsingService.parseAndIndexPdf(documentId, event.minioObjName());
                indexingService.indexContractNodes(documentId);
                log.info("[KAFKA] Indexing succeeded: {}", documentId);
            } catch (RuntimeException e) {
                // Logged and rethrown: the error handler still needs the exception to retry and
                // dead-letter, but without this line the reason never reaches the log.
                log.error("[KAFKA] Indexing failed: {}, reason={}", documentId, e.toString());
                throw e;
            }
        });
    }

    @KafkaListener(topics = "contract.indexed", groupId = "ai-processing-group")
    public void handleContractIndexed(PageIndexedEvent event,
            @Header(value = CorrelationIds.HEADER, required = false) byte[] correlationId) {
        withCorrelationId(correlationId, event.contractId(), () -> {
            UUID contractId = event.contractId();
            log.info("[KAFKA] Received contract.indexed: {}", contractId);
            try {
                log.info("[KAFKA] Processing contract.indexed: {}", contractId);
                analysisService.processContract(contractId);
                log.info("[KAFKA] Analysis succeeded: {}", contractId);
            } catch (RuntimeException e) {
                log.error("[KAFKA] Analysis failed: {}, reason={}", contractId, e.toString());
                throw e;
            }
        });
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
