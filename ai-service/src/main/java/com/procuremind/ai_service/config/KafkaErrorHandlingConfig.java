package com.procuremind.ai_service.config;

import com.procuremind.ai_service.service.kafka.ContractEventProducer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.UUID;

/**
 * Retry and dead-letter policy for the ai-service pipeline.
 *
 * <p>Before this existed the listeners caught everything, so Spring Kafka's default
 * handler was unreachable and every failure committed the offset and vanished. Now a
 * failed record is retried in-process and, once attempts are exhausted, is
 * <b>dead-lettered</b> to {@code <topic>.DLT} <i>and</i> the contract is marked FAILED via
 * {@code contract.failed} — so an operator can see both the poisoned message and the
 * affected contract instead of a row stuck at UPLOADED forever.
 *
 * <p><b>Timing constraint.</b> These retries block the consumer thread, so the total worst
 * case must stay under the consumer's {@code max.poll.interval.ms} (30m) or the broker
 * evicts us mid-work and the partition is reprocessed. With 3 attempts and an 8m indexing
 * budget per attempt the worst case is ~24m + backoff, comfortably inside that window.
 * Indexing is incremental, so each retry resumes rather than restarting.
 */
@Slf4j
@Configuration
public class KafkaErrorHandlingConfig {

    private static final long RETRY_INTERVAL_MS = 5_000L;
    private static final long MAX_RETRIES = 2L; // 3 attempts total

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate,
                                          ContractEventProducer eventProducer) {
        return new DefaultErrorHandler(
                deadLetterRecoverer(kafkaTemplate, eventProducer),
                new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
    }

    /**
     * What happens to a record once its retries are exhausted: it is dead-lettered and its
     * contract is marked FAILED. Package-private so the policy can be exercised directly
     * rather than through the error handler's blocking backoff.
     */
    ConsumerRecordRecoverer deadLetterRecoverer(KafkaTemplate<String, Object> kafkaTemplate,
                                                ContractEventProducer eventProducer) {

        // Let the broker choose the DLT partition: the source topic may be repartitioned
        // later, and a fixed partition number would then fail to publish.
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));

        return (record, ex) -> {
            markContractFailed(record.key(), record.topic(), eventProducer, ex);
            deadLetter.accept(record, ex);
        };
    }

    private void markContractFailed(Object key, String topic, ContractEventProducer producer, Exception ex) {
        if (key == null) {
            log.error("Dead-lettering a record from {} with no key; cannot mark a contract FAILED", topic, ex);
            return;
        }
        try {
            UUID contractId = UUID.fromString(key.toString());
            log.error("Exhausted retries for contract [{}] on topic {}; dead-lettering and marking FAILED",
                    contractId, topic, ex);
            producer.publishProcessingFailed(contractId);
        } catch (IllegalArgumentException e) {
            log.error("Dead-lettering a record from {} with non-UUID key '{}'", topic, key, ex);
        }
    }

    // Declare the auxiliary topics explicitly rather than relying on broker auto-creation,
    // which is commonly disabled outside local development.
    @Bean
    NewTopic contractFailedTopic() {
        return TopicBuilder.name("contract.failed").partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic contractUploadedDltTopic() {
        return TopicBuilder.name("contract.uploaded.DLT").partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic contractIndexedDltTopic() {
        return TopicBuilder.name("contract.indexed.DLT").partitions(1).replicas(1).build();
    }
}
