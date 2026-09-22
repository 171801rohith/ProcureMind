package com.procuremind.ai_service.service.kafka;

import com.procuremind.common.dto.PageIndexedEvent;
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
import java.util.List;
import java.util.UUID;

/**
 * Publishes pipeline lifecycle events.
 *
 * <p>Two dual-write hazards are addressed here without changing any event contract:
 * <ul>
 *   <li><b>Publish-after-commit.</b> Sends used to fire while the surrounding JPA
 *       transaction was still open, so a rollback could still leave the event on the
 *       broker — downstream would react to a state that never existed. When a transaction
 *       is active the send is now deferred to {@code afterCommit}.</li>
 *   <li><b>Non-silent failures.</b> {@code send()} is asynchronous and the old code
 *       discarded the future, so a broker outage lost the event with no trace at all. The
 *       future is now observed and a failure is logged loudly with the contract id.</li>
 * </ul>
 *
 * <p>What this still does <em>not</em> give you is atomicity between the database commit
 * and the publish: if the broker is unreachable after a successful commit the event is
 * lost and the contract stalls. Closing that gap needs a transactional outbox — see
 * {@code docs/architecture.md} "Known gap: no transactional outbox".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContractEventProducer {

    static final String TOPIC_INDEXED = "contract.indexed";
    static final String TOPIC_ANALYZED = "contract.analyzed";
    static final String TOPIC_FAILED = "contract.failed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishPageIndexed(UUID documentId) {
        publish(TOPIC_INDEXED, documentId, new PageIndexedEvent(documentId, "INDEXED"));
    }

    public void publishAnalysisCompleted(UUID documentId) {
        publish(TOPIC_ANALYZED, documentId, new PageIndexedEvent(documentId, "ANALYSIS_COMPLETED"));
    }

    /** Terminal failure, emitted once the container's retries are exhausted. */
    public void publishProcessingFailed(UUID documentId) {
        publish(TOPIC_FAILED, documentId, new PageIndexedEvent(documentId, "FAILED"));
    }

    private void publish(String topic, UUID key, Object payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(topic, key, payload);
                }
            });
            return;
        }
        send(topic, key, payload);
    }

    private void send(String topic, UUID key, Object payload) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, null, key.toString(), payload,
                List.of(new RecordHeader(CorrelationIds.HEADER, key.toString().getBytes(StandardCharsets.UTF_8))));

        kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish {} for contract [{}] — downstream will not observe this "
                        + "transition and the contract may stall", topic, key, ex);
            } else {
                log.info("Published {} for contract [{}]", topic, key);
            }
        });
    }
}
