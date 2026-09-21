package com.procuremind.contract_service.service.kafka;

import com.procuremind.common.dto.ContractUploadedEvent;
import com.procuremind.common.tracing.CorrelationIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Publishes {@code contract.uploaded} once a contract row is durably committed.
 *
 * <p>The send used to happen inside the upload transaction with its future discarded, so a
 * rollback could still emit the event (ai-service would parse an object for a contract row
 * that never existed) and a broker outage lost it silently. The send is now deferred to
 * {@code afterCommit} and its outcome is logged.
 *
 * <p>This narrows but does not eliminate the dual write: a commit followed by a broker
 * failure still loses the event. See {@code docs/architecture.md} "Known gap: no
 * transactional outbox".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContractEventProducer {

    static final String TOPIC_UPLOADED = "contract.uploaded";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishContractUploadEvent(UUID contractId, String filename, String minioObjName) {
        ContractUploadedEvent event = new ContractUploadedEvent(contractId, filename, minioObjName);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(contractId, event);
                }
            });
            return;
        }
        send(contractId, event);
    }

    private void send(UUID contractId, ContractUploadedEvent event) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(TOPIC_UPLOADED, null, contractId.toString(),
                event, java.util.List.of(new RecordHeader(CorrelationIds.HEADER,
                        contractId.toString().getBytes(StandardCharsets.UTF_8))));

        kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish {} for contract [{}] — the contract will stay at UPLOADED "
                        + "until the event is replayed", TOPIC_UPLOADED, contractId, ex);
            } else {
                log.info("Published {} for contract [{}]", TOPIC_UPLOADED, contractId);
            }
        });
    }
}
